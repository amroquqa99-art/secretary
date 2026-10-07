#!/usr/bin/env python3
"""Benchmark Arabic and multilingual retrieval for LifeOS.

The default run is network-free and measures the production BM25 implementation.
Vector and reranker modes are opt-in and use locally cached sentence-transformer
models unless --allow-download is passed explicitly.

Examples:
    python scripts/benchmark_arabic_retrieval.py
    python scripts/benchmark_arabic_retrieval.py --profile qwen3_0_6b
    python scripts/benchmark_arabic_retrieval.py --profile bge_m3 --allow-download
    python scripts/benchmark_arabic_retrieval.py \
        --modes bm25,vector,hybrid \
        --embedding-model <model> --query-template '{query}'
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import tempfile
import time
from collections import defaultdict
from collections.abc import Iterable
from importlib import metadata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "benchmarks" / "arabic_retrieval_v1.json"
DEFAULT_PROFILES = ROOT / "benchmarks" / "arabic_retrieval_profiles.json"


def load_dataset(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    validate_dataset(data)
    return data


def load_profiles(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("version") != 1:
        raise ValueError("unsupported profile version")
    profiles = data.get("profiles")
    if not isinstance(profiles, dict) or not profiles:
        raise ValueError("profiles must be a non-empty object")
    for name, profile in profiles.items():
        if not isinstance(profile, dict):
            raise TypeError(f"profile {name} must be an object")
        if not profile.get("embedding_model"):
            raise ValueError(f"profile {name} has no embedding_model")
        template = profile.get("query_template", "{query}")
        if "{query}" not in template:
            raise ValueError(f"profile {name} query_template must contain {{query}}")
    return data


def apply_profile(args: argparse.Namespace) -> argparse.Namespace:
    if args.profile:
        profiles = load_profiles(args.profiles_file)["profiles"]
        if args.profile not in profiles:
            raise SystemExit(
                f"unknown profile {args.profile!r}; available: {', '.join(sorted(profiles))}"
            )
        profile = profiles[args.profile]
        if args.embedding_model is None:
            args.embedding_model = profile.get("embedding_model")
        if args.reranker_model is None:
            args.reranker_model = profile.get("reranker_model")
        if args.modes is None:
            args.modes = profile.get("modes", "bm25")
        if args.query_template is None:
            args.query_template = profile.get("query_template", "{query}")
        args.min_sentence_transformers = profile.get("min_sentence_transformers")
    if not hasattr(args, "min_sentence_transformers"):
        args.min_sentence_transformers = None
    if args.modes is None:
        args.modes = "bm25"
    if args.query_template is None:
        args.query_template = "{query}"
    if "{query}" not in args.query_template:
        raise SystemExit("--query-template must contain {query}")
    return args


def format_query(query: str, template: str) -> str:
    return template.replace("{query}", query)


def _version_tuple(value: str) -> tuple[int, ...]:
    parts = [int(part) for part in re.findall(r"\d+", value)]
    return tuple(parts[:3])


def require_sentence_transformers(minimum: str | None) -> None:
    if not minimum:
        return
    try:
        installed = metadata.version("sentence-transformers")
    except metadata.PackageNotFoundError as exc:
        raise SystemExit(
            f"sentence-transformers>={minimum} is required by this profile"
        ) from exc
    if _version_tuple(installed) < _version_tuple(minimum):
        raise SystemExit(
            f"sentence-transformers>={minimum} is required by this profile; "
            f"found {installed}"
        )


def validate_dataset(data: dict) -> None:
    if data.get("version") != 1:
        raise ValueError("unsupported benchmark version")
    documents = data.get("documents")
    queries = data.get("queries")
    if not isinstance(documents, list) or not documents:
        raise ValueError("documents must be a non-empty list")
    if not isinstance(queries, list) or not queries:
        raise ValueError("queries must be a non-empty list")

    doc_ids = [doc.get("id") for doc in documents]
    if any(not isinstance(doc_id, str) or not doc_id for doc_id in doc_ids):
        raise ValueError("every document needs a non-empty string id")
    if len(doc_ids) != len(set(doc_ids)):
        raise ValueError("document ids must be unique")
    known = set(doc_ids)

    query_ids = set()
    for query in queries:
        query_id = query.get("id")
        if not isinstance(query_id, str) or not query_id:
            raise ValueError("every query needs a non-empty string id")
        if query_id in query_ids:
            raise ValueError(f"duplicate query id: {query_id}")
        query_ids.add(query_id)
        if not isinstance(query.get("text"), str) or not query["text"].strip():
            raise ValueError(f"query {query_id} has no text")
        relevance = query.get("relevance")
        if not isinstance(relevance, dict) or not relevance:
            raise ValueError(f"query {query_id} has no relevance judgments")
        unknown = set(relevance) - known
        if unknown:
            raise ValueError(f"query {query_id} references unknown docs: {sorted(unknown)}")
        for grade in relevance.values():
            if not isinstance(grade, int) or grade <= 0:
                raise ValueError(f"query {query_id} has invalid relevance grade")


def reciprocal_rank_fusion(*rankings: list[str], k: int = 60) -> list[str]:
    scores: dict[str, float] = defaultdict(float)
    for ranking in rankings:
        seen = set()
        for rank, doc_id in enumerate(ranking, start=1):
            if doc_id in seen:
                continue
            seen.add(doc_id)
            scores[doc_id] += 1.0 / (k + rank)
    return [doc_id for doc_id, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))]


def recall_at_k(ranking: list[str], relevance: dict[str, int], k: int) -> float:
    relevant = set(relevance)
    if not relevant:
        return 0.0
    return len(set(ranking[:k]) & relevant) / len(relevant)


def reciprocal_rank(ranking: list[str], relevance: dict[str, int], k: int = 10) -> float:
    relevant = set(relevance)
    for rank, doc_id in enumerate(ranking[:k], start=1):
        if doc_id in relevant:
            return 1.0 / rank
    return 0.0


def ndcg_at_k(ranking: list[str], relevance: dict[str, int], k: int = 10) -> float:
    def dcg(grades: Iterable[int]) -> float:
        total = 0.0
        for rank, grade in enumerate(grades, start=1):
            total += (2**grade - 1) / math.log2(rank + 1)
        return total

    observed = [relevance.get(doc_id, 0) for doc_id in ranking[:k]]
    ideal = sorted(relevance.values(), reverse=True)[:k]
    ideal_score = dcg(ideal)
    return dcg(observed) / ideal_score if ideal_score else 0.0


def score_rankings(dataset: dict, rankings: dict[str, list[str]]) -> dict:
    per_query = {}
    buckets: dict[str, list[dict]] = defaultdict(list)

    for query in dataset["queries"]:
        query_id = query["id"]
        ranking = rankings.get(query_id, [])
        relevance = query["relevance"]
        row = {
            "query_id": query_id,
            "category": query["category"],
            "recall@1": recall_at_k(ranking, relevance, 1),
            "recall@3": recall_at_k(ranking, relevance, 3),
            "recall@5": recall_at_k(ranking, relevance, 5),
            "mrr@10": reciprocal_rank(ranking, relevance, 10),
            "ndcg@10": ndcg_at_k(ranking, relevance, 10),
        }
        per_query[query_id] = row
        buckets["overall"].append(row)
        buckets[query["category"]].append(row)

    metric_names = ("recall@1", "recall@3", "recall@5", "mrr@10", "ndcg@10")
    summary = {}
    for bucket, rows in sorted(buckets.items()):
        summary[bucket] = {
            metric: sum(row[metric] for row in rows) / len(rows)
            for metric in metric_names
        }
        summary[bucket]["queries"] = len(rows)

    return {"summary": summary, "per_query": per_query}


def bm25_rankings(dataset: dict, limit: int) -> dict[str, list[str]]:
    from api.services.bm25_index import BM25Index

    with tempfile.NamedTemporaryFile(suffix=".db") as tmp:
        index = BM25Index(db_path=tmp.name)
        for doc in dataset["documents"]:
            index.add_document(doc["id"], doc["text"], doc["title"])
        return {
            query["id"]: [
                row["doc_id"] for row in index.search(query["text"], limit=limit)
            ]
            for query in dataset["queries"]
        }


def _load_sentence_transformer(model_name: str, allow_download: bool):
    if not allow_download:
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    from sentence_transformers import SentenceTransformer
    return SentenceTransformer(model_name, local_files_only=not allow_download)


def vector_rankings(
    dataset: dict,
    model_name: str,
    limit: int,
    allow_download: bool,
    query_template: str = "{query}",
) -> dict[str, list[str]]:
    import numpy as np

    model = _load_sentence_transformer(model_name, allow_download)
    documents = dataset["documents"]
    doc_texts = [f"{doc['title']}\n{doc['text']}" for doc in documents]
    doc_vectors = model.encode(
        doc_texts,
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=False,
    )

    rankings = {}
    for query in dataset["queries"]:
        query_vector = model.encode(
            format_query(query["text"], query_template),
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        scores = np.asarray(doc_vectors) @ np.asarray(query_vector)
        order = np.argsort(-scores)[:limit]
        rankings[query["id"]] = [documents[int(i)]["id"] for i in order]
    return rankings


def rerank_rankings(
    dataset: dict,
    candidates: dict[str, list[str]],
    model_name: str,
    limit: int,
    allow_download: bool,
) -> dict[str, list[str]]:
    if not allow_download:
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    from sentence_transformers import CrossEncoder

    model = CrossEncoder(model_name)
    docs = {doc["id"]: doc for doc in dataset["documents"]}
    rankings = {}
    for query in dataset["queries"]:
        candidate_ids = candidates[query["id"]]
        pairs = [
            (query["text"], f"{docs[doc_id]['title']}\n{docs[doc_id]['text']}")
            for doc_id in candidate_ids
        ]
        scores = model.predict(pairs, show_progress_bar=False)
        ordered = sorted(
            zip(candidate_ids, scores),
            key=lambda item: (-float(item[1]), item[0]),
        )
        rankings[query["id"]] = [doc_id for doc_id, _ in ordered[:limit]]
    return rankings


def print_summary(name: str, result: dict) -> None:
    overall = result["summary"]["overall"]
    print(
        f"{name:>8}  "
        f"R@1={overall['recall@1']:.3f}  "
        f"R@3={overall['recall@3']:.3f}  "
        f"R@5={overall['recall@5']:.3f}  "
        f"MRR@10={overall['mrr@10']:.3f}  "
        f"nDCG@10={overall['ndcg@10']:.3f}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--profiles-file", type=Path, default=DEFAULT_PROFILES)
    parser.add_argument("--profile")
    parser.add_argument("--modes")
    parser.add_argument("--embedding-model")
    parser.add_argument("--reranker-model")
    parser.add_argument(
        "--query-template",
        help="Embedding-query template containing {query}; documents stay unmodified.",
    )
    parser.add_argument("--candidate-limit", type=int, default=20)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = apply_profile(parse_args())
    dataset = load_dataset(args.dataset)
    modes = {mode.strip() for mode in args.modes.split(",") if mode.strip()}
    unknown = modes - {"bm25", "vector", "hybrid", "rerank"}
    if unknown:
        raise SystemExit(f"unknown modes: {', '.join(sorted(unknown))}")
    if {"vector", "hybrid", "rerank"} & modes:
        require_sentence_transformers(args.min_sentence_transformers)
    if {"vector", "hybrid", "rerank"} & modes and not args.embedding_model:
        raise SystemExit("--embedding-model is required for vector/hybrid/rerank")
    if "rerank" in modes and not args.reranker_model:
        raise SystemExit("--reranker-model is required for rerank")

    started = time.perf_counter()
    rankings: dict[str, dict[str, list[str]]] = {}
    results = {}

    bm25 = bm25_rankings(dataset, args.candidate_limit)
    if "bm25" in modes or "hybrid" in modes or "rerank" in modes:
        rankings["bm25"] = bm25
        results["bm25"] = score_rankings(dataset, {
            qid: docs[:args.top_k] for qid, docs in bm25.items()
        })

    vector = None
    if {"vector", "hybrid", "rerank"} & modes:
        vector = vector_rankings(
            dataset,
            args.embedding_model,
            args.candidate_limit,
            args.allow_download,
            args.query_template,
        )
        rankings["vector"] = vector
        if "vector" in modes:
            results["vector"] = score_rankings(dataset, {
                qid: docs[:args.top_k] for qid, docs in vector.items()
            })

    hybrid = None
    if "hybrid" in modes or "rerank" in modes:
        hybrid = {
            query["id"]: reciprocal_rank_fusion(
                vector[query["id"]],
                bm25[query["id"]],
            )[:args.candidate_limit]
            for query in dataset["queries"]
        }
        rankings["hybrid"] = hybrid
        if "hybrid" in modes:
            results["hybrid"] = score_rankings(dataset, {
                qid: docs[:args.top_k] for qid, docs in hybrid.items()
            })

    if "rerank" in modes:
        reranked = rerank_rankings(
            dataset,
            hybrid,
            args.reranker_model,
            args.top_k,
            args.allow_download,
        )
        rankings["rerank"] = reranked
        results["rerank"] = score_rankings(dataset, reranked)

    report = {
        "benchmark": dataset["name"],
        "dataset_version": dataset["version"],
        "document_count": len(dataset["documents"]),
        "query_count": len(dataset["queries"]),
        "profile": args.profile,
        "embedding_model": args.embedding_model,
        "reranker_model": args.reranker_model,
        "query_template": args.query_template,
        "allow_download": args.allow_download,
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "results": results,
        "rankings": rankings,
    }

    for name in ("bm25", "vector", "hybrid", "rerank"):
        if name in results:
            print_summary(name, results[name])

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"wrote {args.output}")

    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(ROOT))
    raise SystemExit(main())
