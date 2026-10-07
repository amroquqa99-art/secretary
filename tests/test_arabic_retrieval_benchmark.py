"""Unit coverage for the Arabic retrieval benchmark harness."""

import copy
import json
from pathlib import Path

import pytest

pytestmark = pytest.mark.unit

ROOT = Path(__file__).resolve().parent.parent
DATASET = ROOT / "benchmarks" / "arabic_retrieval_v1.json"


def test_dataset_is_valid_and_contains_required_arabic_slices():
    from scripts.benchmark_arabic_retrieval import load_dataset

    data = load_dataset(DATASET)

    categories = {query["category"] for query in data["queries"]}
    assert {
        "msa",
        "palestinian_dialect",
        "code_switch",
        "cross_lingual_en_to_ar",
        "arabic_diacritics",
    } <= categories
    assert any("\u0600" <= ch <= "\u06ff" for ch in "".join(
        doc["text"] for doc in data["documents"]
    ))


def test_dataset_validation_rejects_unknown_relevance_document():
    from scripts.benchmark_arabic_retrieval import load_dataset, validate_dataset

    data = load_dataset(DATASET)
    broken = copy.deepcopy(data)
    broken["queries"][0]["relevance"] = {"missing": 3}

    with pytest.raises(ValueError, match="unknown docs"):
        validate_dataset(broken)


def test_metrics_have_expected_values():
    from scripts.benchmark_arabic_retrieval import (
        ndcg_at_k,
        recall_at_k,
        reciprocal_rank,
    )

    ranking = ["noise", "best", "secondary"]
    relevance = {"best": 3, "secondary": 1}

    assert recall_at_k(ranking, relevance, 1) == 0.0
    assert recall_at_k(ranking, relevance, 3) == 1.0
    assert reciprocal_rank(ranking, relevance, 10) == 0.5
    assert 0.0 < ndcg_at_k(ranking, relevance, 3) < 1.0


def test_rrf_rewards_documents_found_by_both_arms():
    from scripts.benchmark_arabic_retrieval import reciprocal_rank_fusion

    fused = reciprocal_rank_fusion(
        ["vector_only", "shared", "tail"],
        ["bm25_only", "shared", "other"],
    )

    assert fused[0] == "shared"
    assert set(fused[:3]) == {"shared", "vector_only", "bm25_only"}


def test_bm25_benchmark_runs_without_model_or_network():
    from scripts.benchmark_arabic_retrieval import bm25_rankings, load_dataset

    data = load_dataset(DATASET)
    rankings = bm25_rankings(data, limit=5)

    assert set(rankings) == {query["id"] for query in data["queries"]}
    # Exact identifier and exact Arabic wording should be present in the
    # production lexical path without loading an embedding model.
    assert "d05" in rankings["q03"][:3]
    assert "d25" in rankings["q17"][:3]


def test_dataset_file_is_utf8_json():
    decoded = json.loads(DATASET.read_text(encoding="utf-8"))
    assert decoded["name"] == "LifeOS Arabic Retrieval Benchmark v1"

def test_model_profiles_are_valid_and_include_multilingual_candidates():
    from scripts.benchmark_arabic_retrieval import load_profiles

    data = load_profiles(PROFILES)
    profiles = data["profiles"]

    assert {"current_lifeos", "mxbai_recommended", "qwen3_0_6b", "bge_m3"} <= set(profiles)
    assert profiles["current_lifeos"]["query_template"] == "{query}"
    assert profiles["mxbai_recommended"]["query_template"].startswith("Represent this sentence")
    assert profiles["qwen3_0_6b"]["embedding_model"] == "Qwen/Qwen3-Embedding-0.6B"
    assert profiles["qwen3_0_6b"]["reranker_model"] == "Qwen/Qwen3-Reranker-0.6B"
    assert profiles["qwen3_0_6b"]["min_sentence_transformers"] == "5.4.0"
    assert "{query}" in profiles["qwen3_0_6b"]["query_template"]


def test_profile_resolution_applies_defaults_but_preserves_explicit_overrides():
    from argparse import Namespace
    from scripts.benchmark_arabic_retrieval import apply_profile

    args = Namespace(
        profile="qwen3_0_6b",
        profiles_file=PROFILES,
        embedding_model=None,
        reranker_model="custom/reranker",
        modes=None,
        query_template=None,
    )

    resolved = apply_profile(args)

    assert resolved.embedding_model == "Qwen/Qwen3-Embedding-0.6B"
    assert resolved.reranker_model == "custom/reranker"
    assert resolved.modes == "bm25,vector,hybrid,rerank"
    assert resolved.query_template.startswith("Instruct:")


def test_query_template_is_applied_only_to_queries():
    from scripts.benchmark_arabic_retrieval import format_query

    template = "Instruct: retrieve relevant notes\\nQuery:{query}"
    assert format_query("خطة المشروع", template) == (
        "Instruct: retrieve relevant notes\\nQuery:خطة المشروع"
    )


def test_profile_validation_rejects_template_without_query_placeholder(tmp_path: Path):
    from scripts.benchmark_arabic_retrieval import load_profiles

    bad = tmp_path / "profiles.json"
    bad.write_text(
        json.dumps({
            "version": 1,
            "profiles": {
                "broken": {
                    "embedding_model": "example/model",
                    "query_template": "fixed text only",
                }
            },
        }),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="must contain"):
        load_profiles(bad)

def test_version_tuple_handles_release_suffixes():
    from scripts.benchmark_arabic_retrieval import _version_tuple

    assert _version_tuple("5.4.0") == (5, 4, 0)
    assert _version_tuple("5.4.1.dev2") == (5, 4, 1)

