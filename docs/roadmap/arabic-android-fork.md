# Arabic + Android Development Roadmap for LifeOS

**Status:** Working design for the `amroquqa99-art/secretary` fork  
**Upstream:** `nbramia/LifeOS`  
**Pinned baseline commit:** `bae2bfba66c542e17c4aa08aaebae6509b4a3e69`  
**Pinned baseline tree:** `830ddf96b14f54a69059ac0f47ad4972dde55cb2`  
**Legacy Secretary rollback branch:** `archive/pre-lifeos-2026-10-07`  
**Legacy Secretary head:** `15b17f5b6f7ddfff4dbd1a324eab0ce74f17d95e`

This document defines the development sequence before any contribution is
proposed to the LifeOS upstream repository. It is intentionally stricter than a
feature wishlist: each phase has compatibility, security, rollback, and
verification gates.

## 1. Goals

The fork should add four capabilities without weakening LifeOS:

1. First-class Arabic support, including RTL, mixed Arabic/English content,
   Arabic search/retrieval, Arabic tool use, and Arabic voice.
2. A strong Android experience, initially as a secure remote client and later
   as an optional native companion for capabilities browsers cannot provide.
3. Provider-independent model execution, including local OpenAI-compatible
   models and hosted OpenAI-compatible providers.
4. A contribution path made of small, reviewable, upstream-quality changes.

## 2. Non-goals

- Do not rewrite LifeOS into a native Android application.
- Do not expose the existing unauthenticated LifeOS API directly to the public
  Internet.
- Do not depend on unofficial ChatGPT browser/session cookies.
- Do not replace proven upstream modules merely to make the fork look
  different.
- Do not change embedding, reranker, STT, TTS, or generation defaults from
  model-card claims alone; benchmark first.
- Do not submit upstream pull requests until the fork is integrated and tested
  end-to-end.

## 3. Baseline and rollback policy

The migration preserved the original Secretary commit graph. The branch
`archive/pre-lifeos-2026-10-07` is the permanent rollback pointer for the
native Android code that existed before the LifeOS migration.

The LifeOS baseline was imported as file content, not as a shallow copy claim.
Before promotion to `main`, the fork's tree SHA was verified to be exactly the
same as the pinned upstream tree SHA:

```
830ddf96b14f54a69059ac0f47ad4972dde55cb2
```

Fork development branches originate from a verified main commit. The archive
branch is immutable, and routine development updates main without force-pushes.

## 4. Audit findings

### 4.1 Architecture

LifeOS is a suitable server-side foundation:

- FastAPI service layer.
- SQLite + FTS5 and ChromaDB search/indexing.
- Google, communication, finance, health, and knowledge integrations.
- A task/schedule/agent system.
- Web, voice, Telegram, MCP, Claude Code, Codex, and Hermes surfaces.
- OpenAI-compatible local and remote LLM plumbing.

The correct Android architecture is therefore **client + optional companion**,
not a port of the entire Python/ML server stack.

### 4.2 Arabic gaps

Arabic is not currently a first-class product locale:

- Primary HTML surfaces declare `lang="en"`.
- There is no general i18n resource layer.
- No product-wide RTL mode exists.
- Several layouts use physical left/right CSS values.
- Current search defaults are English-oriented.
- Wake/cancel voice phrase logic is currently English-oriented.

A translated shell alone would be insufficient. Arabic quality must be tested
through UI, retrieval, agent/tool calling, and voice.

### 4.3 Android gaps

The web UI already contains significant mobile behavior and installable
web-manifest metadata, but it is not yet a complete offline PWA:

- no service worker was found;
- no Workbox/offline asset strategy was found;
- browser voice uses secure-context media APIs;
- the server remains authoritative for most behavior.

This is a good base for Android Chrome / Home Screen use, but not for a
fully-offline Android assistant.

### 4.4 Provider gaps

LifeOS has a useful generic OpenAI-compatible client, but provider semantics
must be tested across the full agentic path.

The first fork fix addresses:
- terminal OpenAI-compatible SSE chunks with `delta: null`;
- remote-provider pricing when `LIFEOS_LLM_BACKEND=remote`;
- keeping llama-server-only request controls away from remote providers.

The associated regression test exercises an actual two-round tool-calling
agent turn with a synthetic provider and no network dependency.

### 4.5 Security boundary

The main LifeOS API is designed for a single-user trusted network. It should
not be treated as a public authenticated API merely because an Android client
exists.

Initial Android access MUST stay behind:
- HTTPS; and
- a trusted private network such as Tailscale.

Public/mobile access is a later security project with device pairing, scoped
credentials, revocation, rotation, and explicit threat modeling.

## 5. Phase plan

### Phase 0 — Upstream-compatible baseline

**Status:** complete.

Acceptance:
- legacy Secretary history remains reachable;
- current main starts from an exact LifeOS tree;
- pinned upstream commit/tree recorded;
- no upstream contribution made.

### Phase 1 — Provider correctness

Implement and verify a minimal fix for the global remote backend path.

Required matrix:
- Anthropic global backend;
- local global backend;
- remote global backend;
- explicit per-turn local override;
- explicit per-turn remote override;
- tool calls on OpenAI-compatible streaming;
- terminal `delta: null`;
- local-only reasoning controls;
- priced and unpriced remote providers.

No new provider abstraction should be introduced unless the existing
`LocalLLMClient` seam is proven insufficient.

### Phase 2 — i18n and RTL foundation

Add a lightweight localization layer compatible with the existing vanilla
frontend.

Requirements:
- English remains the default and regression baseline.
- Arabic can be selected explicitly.
- `document.documentElement.lang` and `dir` follow the locale.
- Visible product strings use stable translation keys.
- Dates, times, and numbers use locale-aware formatting.
- Text inputs support bidirectional content.
- User/model-generated blocks use `dir=auto` or an equivalent safe
  bidirectional strategy.
- CSS logical properties replace physical direction only where the direction
  is semantic. Graph coordinates and deliberately physical controls must not
  be mirrored blindly.
- No translation string may be interpolated as raw HTML.

Verification:
- Playwright tests for English LTR and Arabic RTL.
- Mixed Arabic/English/URL/code messages.
- Chat, tasks/agents, CRM person names, journal, and dialogs.
- Visual/interaction regression at desktop and Android-size viewports.

### Phase 3 — Arabic retrieval benchmark

Do not change search defaults before measurement.

Create a synthetic benchmark containing:
- Modern Standard Arabic;
- Palestinian/Levantine colloquial language;
- Arabic-English code switching;
- names with spelling/hamza variants;
- Arabic and Western numerals;
- dates;
- exact identifiers and URLs;
- Arabic query → English source;
- English query → Arabic source;
- adversarial cases where lexical exact-match must outrank semantic similarity.

Metrics:
- Recall@k;
- MRR/nDCG where appropriate;
- exact-match preservation;
- indexing latency;
- query latency;
- RAM/VRAM;
- index size.

Candidate families may include multilingual Qwen3 embedding/reranking and
other established multilingual baselines, but selection is benchmark-driven.

Normalization rules must be versioned and conservative. Never mutate stored
source text just to improve retrieval.

### Phase 4 — Arabic agent/tool benchmark

Arabic response fluency is not enough. Build synthetic end-to-end tests for:

- create/read/update calendar actions;
- task/reminder operations;
- Gmail search and draft workflows;
- explicit send confirmation;
- destructive-action confirmation;
- ambiguous Arabic cancellations;
- bilingual commands;
- dates expressed in Arabic prose;
- references such as "بكرة", "بعد ساعتين", and locale-specific day names.

Every test must assert both the tool selected and the normalized arguments.

### Phase 5 — Arabic voice

Make speech configuration locale-aware rather than hardcoding one language.

Requirements:
- pluggable STT and TTS providers;
- Arabic/English code switching;
- configurable wake phrases;
- Arabic cancel/stop phrases with conservative matching;
- no wake-check transcript becomes a persisted chat turn;
- interruption/cancel behavior remains compatible with current voice tests.

Benchmark candidate Arabic STT models on real, consented Levantine/Palestinian
speech before changing defaults.

### Phase 6 — Android web client

First deliver the smallest robust Android surface:

- Chrome/standalone install behavior;
- responsive RTL/LTR UI;
- microphone permissions over HTTPS;
- deep links;
- reliable reconnect/replay behavior;
- optional Web Push only if the security and browser support matrix is clear.

Do not claim offline support until there is a tested service-worker strategy.

Third-party web assets should be pinned or vendored before an offline/privacy
claim is made.

### Phase 7 — Native Android companion

Create a native companion only for capabilities the web surface cannot
reliably provide.

Candidate responsibilities:
- secure device pairing;
- Android Keystore-backed device credential;
- notifications;
- foreground/background service where Android policy permits it;
- native wake-word pipeline;
- offline capture queue;
- optional local on-device inference;
- connectivity/state synchronization with the LifeOS server.

The archived pre-LifeOS Secretary branch may be used as a source of reviewed
ideas or isolated components. It must not be merged wholesale into LifeOS.

### Phase 8 — Android/public security model

Before allowing access outside a private tailnet:

1. Pair a device from an already trusted operator session.
2. Mint one credential per device.
3. Store private credential material in Android Keystore.
4. Use short-lived/scoped access where feasible.
5. Support server-side revocation and key rotation.
6. Bind dangerous actions to explicit authorization policies.
7. Add brute-force/rate-limit controls to pairing/auth endpoints.
8. Add security headers and a documented browser-origin model.
9. Log metadata/IDs rather than personal message bodies.
10. Add negative tests for authentication bypass, path confusion, origin/CSRF
    mistakes, token replay, and revoked-device access.

Do not reuse the MCP OAuth transport blindly for the mobile application. Reuse
its hardened concepts where they fit, but define a separate mobile threat
model and protocol contract first.

### Phase 9 — Local model profiles

LifeOS should expose capability profiles instead of assuming one local model
can do everything equally well.

Profiles should distinguish:
- tool-calling orchestrator;
- general assistant;
- embeddings;
- reranker;
- STT;
- TTS.

The 6 GB VRAM class must have a tested low-memory profile, with graceful CPU
fallback where practical. Quality gates take precedence over forcing every
component local.

### Phase 10 — OpenAI / ChatGPT paths

Treat these as separate products:

**OpenAI API**
- supported through a standards-compatible hosted-provider path;
- API usage is separately billed;
- credentials stay server-side.

**Codex CLI**
- LifeOS already has a Codex handoff path;
- users who authenticate Codex through an eligible ChatGPT plan can use that
  official subscription-backed CLI path within its product limits.

**Sign in with ChatGPT subscription sharing**
- do not emulate or scrape ChatGPT sessions;
- implement only through OpenAI's supported delegated integration if/when the
  project has access to the participating-app program;
- keep this behind a provider adapter so the absence of that program never
  blocks local/API operation.

## 6. Upstream contribution strategy

Nothing is submitted upstream until the fork is complete enough to validate the
architecture in real use.

Potential future contributions should be independent:

1. Remote agentic-provider correctness/regression fix.
2. Generic i18n + RTL foundation, English unchanged by default.
3. Multilingual benchmark tooling/configurability.
4. Mobile web fixes that benefit LifeOS generally.
5. Security hardening that is independently useful.

A native Android companion may remain a sibling project unless LifeOS
maintainers explicitly want it in the monorepo.

For every candidate upstream PR:
- one problem;
- minimal production diff;
- regression tests;
- docs where behavior is user-visible;
- synthetic fixtures only;
- no fork-specific names or personal data;
- explicit security/privacy analysis for new data or network surfaces;
- full retained test lane before review.

## 7. Quality gates

A phase is not complete because code compiles. Completion requires receipts.

Minimum gates:
- relevant unit tests;
- relevant browser/integration tests;
- full upstream retained test lane before merging a cross-cutting change;
- no new secrets or personal data in git;
- read-back of final changed files/diff;
- rollback path identified;
- documentation updated for operator-facing behavior.

Security-sensitive changes additionally require:
- threat-boundary review;
- negative tests;
- auth/permission read-back;
- no public exposure introduced by default.

## 8. Working branch policy

Recommended fork branch families:

- `fix/provider-*`
- `feat/i18n-*`
- `bench/arabic-retrieval`
- `bench/arabic-tool-use`
- `feat/android-web-*`
- `feat/android-companion-*`
- `security/mobile-auth-*`

Keep benchmark/data changes separate from behavior changes so benchmark results
cannot be biased by the implementation under test.

## 9. Current execution order

1. Finish and verify the remote-agentic regression fix.
2. Add the i18n/RTL foundation with English-parity browser tests.
3. Build Arabic retrieval and tool-use benchmarks.
4. Select search/model changes from benchmark evidence.
5. Harden Android web UX.
6. Design mobile pairing/auth before public access.
7. Build native Android companion only for capabilities proven to require it.
8. Run an integrated fork review.
9. Only then prepare focused upstream pull requests.
