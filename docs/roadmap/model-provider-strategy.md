# Model Provider Strategy

**Scope:** LifeOS fork model execution and user-facing provider selection  
**Status:** design contract; implementation follows focused provider PRs.

## 1. Provider goals

LifeOS must remain useful in four materially different deployment modes:

1. **Fully local** — generation, retrieval, and voice can run without a hosted
   model provider when hardware permits.
2. **OpenAI-compatible hosted API** — any standards-compatible endpoint can be
   configured without provider-specific code in the agent loop.
3. **First-party hosted providers** — integrations such as Anthropic retain
   native capabilities where they add value.
4. **Subscription-backed delegated tools** — official product flows such as
   Codex signed in with ChatGPT, or participating applications using
   Sign in with ChatGPT, remain separate from API-key billing.

The UI must never imply that a ChatGPT subscription is an OpenAI API key.

## 2. Provider classes

### 2.1 Local OpenAI-compatible runtime

Examples include llama.cpp / llama-server and other local servers that implement
the OpenAI-compatible chat-completions surface.

Characteristics:

- endpoint commonly lives on localhost or a trusted LAN;
- no metered hosted-provider price is assumed;
- local runtimes may accept vendor-specific fields such as reasoning/template
  controls;
- hardware/VRAM limits matter;
- offline operation is possible when all required models are present.

LifeOS-specific local request fields stay capability-gated. They are not sent
to arbitrary OpenAI-compatible providers.

### 2.2 Generic hosted OpenAI-compatible provider

This is the primary abstraction for hosted providers that expose compatible
chat-completions semantics.

Configuration includes:

- base URL;
- model ID;
- API credential;
- timeout;
- input/output pricing when known;
- capability flags proven by tests.

A provider may be OpenAI-compatible without being llama-server-compatible.
Transport compatibility does not authorize sending local-runtime extensions.

### 2.3 Anthropic-native provider

Anthropic remains a distinct provider when native tool or streaming semantics
are used directly. Provider selection should not leak Anthropic assumptions
into generic orchestration.

### 2.4 OpenAI API

OpenAI API access belongs in the hosted-provider/API-key class.

Properties:

- API credentials are server-side secrets;
- API usage follows API billing;
- ChatGPT subscription entitlements are not substituted for an API key;
- the adapter should rely on supported OpenAI API contracts rather than browser
  session state.

### 2.5 Codex authenticated with ChatGPT

LifeOS already has Codex handoff capabilities. Codex clients can officially be
authenticated with a ChatGPT account, in which case usage follows the user's
eligible ChatGPT/Codex allowance rather than an OpenAI API key.

This is a **tool/agent handoff**, not a transparent replacement for the core
LifeOS chat provider. The integration should preserve that distinction.

### 2.6 Sign in with ChatGPT for participating applications

OpenAI supports participating applications that let eligible users sign in with
ChatGPT and opt in to using their ChatGPT plan for supported AI requests.

This path is implemented only through the official participating-application
flow and only when the project has access to it.

The fork does not:

- scrape ChatGPT sessions;
- copy browser cookies;
- automate consumer web endpoints;
- claim subscription-backed inference when the application is not an eligible
  participant.

The provider boundary is designed so this delegated provider can be added
without changing local/API behavior.

## 3. Capability model

Provider choice is not only a model string. Runtime behavior depends on
capabilities.

The provider layer should expose a small capability record, for example:

```text
provider_kind
supports_tools
supports_parallel_tools
supports_streaming
supports_usage_in_stream
supports_reasoning_control
supports_vision
supports_audio
pricing_source
credential_source
```

Capabilities are established by adapter contract and regression tests. Unknown
capabilities default to false.

## 4. Selection precedence

Per-turn explicit selection has priority over a configured global default.

Expected order:

1. explicit per-turn local choice;
2. explicit per-turn hosted/remote choice;
3. configured global provider;
4. documented fallback if the configured provider is unavailable.

The selected provider determines:

- request fields;
- model label;
- accounting/pricing;
- retry policy;
- telemetry metadata;
- capability validation.

The same resolved provider identity is used for all of those behaviors. The
agent loop must not infer "local" merely because a generic OpenAI-compatible
client class is used.

## 5. Credentials

### Server-side provider credentials

Hosted API keys live only on the LifeOS server and follow LifeOS secret-storage
rules. They are never synchronized to the Android companion.

### Android device credentials

The Android companion stores only its LifeOS device credential in Android
Keystore. A device credential authenticates the phone to LifeOS; it is not a
model-provider secret.

### Delegated subscription credentials

OAuth/delegated credentials for supported subscription-backed integrations are
stored and refreshed according to the official provider contract. They are not
converted into reusable API keys.

## 6. Local model profiles

A single "local model" setting is insufficient for a complete assistant.

Profiles distinguish:

- **orchestrator** — tool selection and structured arguments;
- **assistant** — general conversation/synthesis;
- **embedding** — retrieval vectors;
- **reranker** — cross-encoder ranking;
- **STT** — speech recognition;
- **TTS** — speech synthesis;
- optional **wake word** model on Android.

A low-memory profile targets the 6 GB VRAM class and permits deliberate CPU
fallbacks. A model is selected from benchmark evidence, not model-card claims.

## 7. Arabic acceptance

A provider/model profile is not "Arabic capable" merely because it can answer
in Arabic.

Arabic acceptance covers:

- fluent Modern Standard Arabic;
- Palestinian/Levantine colloquial commands;
- Arabic/English code switching;
- structured tool calls with Arabic source text;
- dates and relative-time expressions;
- retrieval across Arabic and English documents;
- safe confirmation/cancellation language;
- STT/TTS where voice is enabled.

Generation, retrieval, tool use, and voice receive separate scores.

## 8. Security invariants

- Untrusted model output never grants authority.
- Model-selected tools pass through the existing LifeOS authorization and
  confirmation policy.
- Provider-specific request options are allow-listed.
- Secrets do not appear in prompts, logs, benchmark fixtures, or browser code.
- A provider switch cannot silently change the confirmation requirements of a
  tool.
- Remote-provider data exposure is visible to the operator.
- Local mode does not silently fall back to a hosted model unless the configured
  policy explicitly allows it.

## 9. Testing matrix

Every production provider path is covered by synthetic tests for:

- simple streamed text;
- tool call followed by tool result and final response;
- multiple tool calls where supported;
- terminal chunks with empty or null deltas;
- usage present and usage absent;
- timeout and disconnect;
- malformed provider payload;
- pricing/accounting;
- explicit local override;
- explicit remote override;
- configured global local;
- configured global remote;
- unsupported capability rejection.

Network-independent provider fakes are the default CI path. Live-provider
smoke tests remain opt-in and never run with contributor secrets.

## 10. Implementation sequence

1. Make existing local/remote resolution internally consistent.
2. Keep the current OpenAI-compatible client small and well tested.
3. Introduce an explicit capability record only where behavior currently relies
   on provider-type guesses.
4. Add a dedicated OpenAI API configuration preset if operator UX requires it;
   do not fork the transport without need.
5. Preserve Codex handoff as an official ChatGPT-authenticated tool path.
6. Add Sign in with ChatGPT only through the supported participating-app
   program.
7. Benchmark local model profiles for Arabic before changing defaults.
