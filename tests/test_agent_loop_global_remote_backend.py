"""Regression coverage for a globally configured OpenAI-compatible backend.

This exercises the native agentic loop, not only LocalLLMClient in isolation:
- LIFEOS_LLM_BACKEND=remote selects the configured provider without a picker override.
- a tool-calling turn survives providers that emit a terminal delta: null.
- llama-server-only thinking controls never leak to the remote provider.
- usage is priced with the configured remote rates even when force_remote is false.

No network calls are made.
"""
import json
from unittest.mock import AsyncMock, patch

import pytest

pytestmark = pytest.mark.unit


class _StreamResponse:
    def __init__(self, chunks):
        self._chunks = chunks

    def raise_for_status(self):
        return None

    async def aiter_lines(self):
        for chunk in self._chunks:
            yield "data: " + json.dumps(chunk)
        yield "data: [DONE]"


class _StreamContext:
    def __init__(self, response):
        self._response = response

    async def __aenter__(self):
        return self._response

    async def __aexit__(self, *exc):
        return False


class _SequentialAsyncClient:
    """Minimal httpx.AsyncClient-shaped fake with one response per round."""

    is_closed = False

    def __init__(self, responses):
        self._responses = list(responses)
        self.payloads = []

    def stream(self, method, url, **kwargs):
        assert method == "POST"
        assert url == "/v1/chat/completions"
        self.payloads.append(kwargs["json"])
        assert self._responses, "unexpected extra LLM round"
        return _StreamContext(_StreamResponse(self._responses.pop(0)))


def _done(finish_reason, *, prompt=0, completion=0):
    # Several OpenAI-compatible providers emit a literal null delta in the
    # terminal choice. The client must treat that shape as an empty delta.
    return {
        "choices": [{"delta": None, "finish_reason": finish_reason}],
        "usage": {
            "prompt_tokens": prompt,
            "completion_tokens": completion,
            "total_tokens": prompt + completion,
        },
    }


@pytest.mark.asyncio
async def test_global_remote_backend_completes_tool_turn_with_null_terminal_delta(monkeypatch):
    from api.services import agent_loop, llm_client

    llm_client.reset_local_llm()
    monkeypatch.setattr(agent_loop.settings, "llm_backend", "remote")
    monkeypatch.setattr(agent_loop.settings, "remote_llm_base_url", "https://provider.example/v1")
    monkeypatch.setattr(agent_loop.settings, "remote_llm_model", "synthetic/tool-model")
    monkeypatch.setattr(agent_loop.settings, "remote_llm_api_key", "test-key")
    monkeypatch.setattr(agent_loop.settings, "remote_llm_timeout", 30)
    monkeypatch.setattr(agent_loop.settings, "remote_llm_input_price_per_mtok", 1.0)
    monkeypatch.setattr(agent_loop.settings, "remote_llm_output_price_per_mtok", 2.0)
    # If the global remote path is mistaken for local, this would add
    # chat_template_kwargs to the provider request.
    monkeypatch.setattr(agent_loop.settings, "local_agent_enable_thinking", False)

    client = llm_client.get_local_llm()
    assert isinstance(client, llm_client.LocalLLMClient)
    assert client.model == "synthetic/tool-model"

    fake_http = _SequentialAsyncClient([
        [
            {
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_1",
                            "type": "function",
                            "function": {
                                "name": "search_vault",
                                "arguments": "{}",
                            },
                        }]
                    },
                    "finish_reason": None,
                }]
            },
            _done("tool_calls", prompt=100, completion=10),
        ],
        [
            {
                "choices": [{
                    "delta": {"content": "final answer"},
                    "finish_reason": None,
                }]
            },
            _done("stop", prompt=50, completion=5),
        ],
    ])
    client._async_client = fake_http

    try:
        with patch.object(
            agent_loop,
            "execute_tool_parallel",
            AsyncMock(return_value="synthetic vault result"),
        ):
            events = [
                event
                async for event in agent_loop.run_agent_loop(
                    "find the synthetic note",
                    max_tool_rounds=2,
                )
            ]
    finally:
        llm_client.reset_local_llm()

    result = next(event["result"] for event in events if event["type"] == "result")
    assert result.model == "synthetic/tool-model"
    assert result.full_text == "final answer"
    assert [call["tool"] for call in result.tool_calls_log] == ["search_vault"]

    # 150 input at $1/M + 15 output at $2/M = $0.00018.
    assert result.total_input_tokens == 150
    assert result.total_output_tokens == 15
    assert result.total_cost_usd == pytest.approx(0.00018)
    assert result.unpriced is False

    assert len(fake_http.payloads) == 2
    assert all(payload["model"] == "synthetic/tool-model" for payload in fake_http.payloads)
    assert all("chat_template_kwargs" not in payload for payload in fake_http.payloads)


def test_force_local_still_overrides_global_remote_backend(monkeypatch):
    """An explicit local per-turn choice overrides the global remote backend."""
    from api.services import agent_loop, llm_client

    llm_client.reset_local_llm()
    monkeypatch.setattr(agent_loop.settings, "llm_backend", "remote")
    monkeypatch.setattr(agent_loop.settings, "local_llm_url", "http://local.example:8080")
    monkeypatch.setattr(agent_loop.settings, "local_llm_timeout", 30)

    try:
        client = agent_loop._select_client(force_local=True)
    finally:
        llm_client.reset_local_llm()

    assert isinstance(client, llm_client.LocalLLMClient)
    assert client.model == "local"
    assert client.base_url == "http://local.example:8080"
