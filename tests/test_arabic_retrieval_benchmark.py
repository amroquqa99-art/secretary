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
