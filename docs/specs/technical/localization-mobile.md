# Localization and Mobile Client Architecture

**Status:** Partial  
**Last Updated:** 2026-10-07  
**Owner:** Frontend / Platform

LifeOS remains a self-hosted, server-authoritative personal assistant. This
spec defines how localization, bidirectional text, Android-class browser
clients, and any future native mobile companion fit that architecture without
weakening the existing privacy and network boundaries.

## Scope

This design covers:

- locale selection and persistence for browser clients;
- Arabic and other right-to-left locales;
- mixed-direction user and model content;
- locale-aware formatting;
- multilingual retrieval and tool-use quality gates;
- Android browser / installed-web-app behavior;
- the boundary for an optional native Android companion;
- model-provider and subscription integration constraints that affect mobile
  clients.

This design does not turn the Python server into an Android application, expose
the main API directly to the public Internet, or make offline claims for
features that still require the LifeOS server.

## Architectural principles

### Server remains authoritative

Calendar actions, tasks, email operations, search, agent execution, memory,
scheduling, and policy enforcement continue to live on the LifeOS server.
Mobile clients are presentation and capture surfaces.

### English behavior is the compatibility baseline

English remains the default locale. Adding localization must not change the
existing API contract, agent semantics, or English interaction behavior.

### Localization is broader than translated strings

Arabic support is considered complete only when the relevant surface works for:

- visible interface text;
- layout direction;
- mixed Arabic/English content;
- dates, times, and numbers;
- search and retrieval;
- tool calling and argument normalization;
- speech recognition and synthesis where voice is enabled.

A translated shell with degraded search or tool use is not first-class
localization.

### Private-network access is the initial mobile trust model

The primary LifeOS HTTP surface is designed for a trusted single-operator
network. An Android browser client therefore connects over HTTPS through the
operator's trusted private network, such as Tailscale.

A future public mobile transport requires its own authentication protocol and
threat model before it can be enabled.

## Locale model

### Supported locale identifier

Browser surfaces use a normalized BCP 47 locale identifier. The first supported
values are:

- `en`
- `ar`

The implementation may later accept regional forms such as `ar-PS`, while
translation fallback remains `ar` unless a regional catalog exists.

### Locale precedence

For a browser surface, locale resolution follows this order:

1. an explicit user-selected locale stored by LifeOS for that client;
2. a client-local preference when no server preference exists;
3. the browser's preferred language when supported;
4. English.

An explicit choice always wins over browser inference.

### Direction

The locale resolver owns the page-level language and direction:

```
document.documentElement.lang = resolvedLocale
document.documentElement.dir = localeDirection(resolvedLocale)
```

Arabic resolves to `rtl`; English resolves to `ltr`.

Generated user/model content is not forced to the page direction. Content
blocks that can contain arbitrary natural language use `dir="auto"` or an
equivalent safe bidirectional strategy.

### Translation catalogs

Translation catalogs are data, not executable markup.

Requirements:

- stable semantic keys;
- English catalog is complete;
- Arabic catalog is complete for any surface that advertises Arabic support;
- missing keys fall back to English and are test-visible;
- user-controlled data is never interpolated through `innerHTML`;
- translated text is assigned through safe text/attribute APIs;
- HTML fragments are not accepted as ordinary translation values.

The first implementation should stay compatible with the existing no-build
frontend and avoid introducing a framework solely for localization.

## Bidirectional UI rules

Physical direction and semantic direction are not the same.

Use CSS logical properties for semantic layout:

- `margin-inline-*`
- `padding-inline-*`
- `inset-inline-*`
- `border-inline-*`
- `text-align: start/end`

Do not mechanically mirror:

- graph coordinates;
- charts;
- timeline chronology whose direction is explicitly defined;
- media controls whose physical orientation has independent meaning;
- code, URLs, identifiers, or filesystem paths.

Text fields that accept arbitrary user input must remain usable for Arabic,
English, and mixed content.

## Locale-aware formatting

Human-facing dates, times, numbers, and relative-time labels use
`Intl`-family browser APIs or an equivalent locale-aware formatter.

Machine/API values remain locale-neutral. Localization never changes the JSON
shape, ISO timestamp representation, identifiers, enum values, or tool
arguments exchanged with the server.

## Arabic retrieval quality

Search defaults are not changed only because a model advertises Arabic
support.

Any multilingual embedding, reranker, tokenizer, or normalization change must
be evaluated against synthetic and consented benchmark material covering:

- Modern Standard Arabic;
- Levantine / Palestinian colloquial phrasing;
- Arabic-English code switching;
- names with common spelling and hamza variants;
- Arabic and Western numerals;
- dates;
- exact identifiers and URLs;
- Arabic query to English source;
- English query to Arabic source;
- cases where lexical exact match must outrank semantic similarity.

Measurements include retrieval quality and resource cost. Stored source text is
never destructively normalized to improve benchmark scores.

## Arabic tool-use quality

Arabic response fluency is insufficient for agent correctness.

Agent/tool regression coverage must include Arabic and bilingual requests for:

- calendar reads and writes;
- task and reminder operations;
- email search and draft creation;
- explicit send confirmation;
- destructive-action confirmation;
- date and time expressions;
- relative phrases such as "بكرة" and "بعد ساعتين";
- cancellation and ambiguity handling.

Tests assert both the selected tool and normalized structured arguments.

## Voice localization

Voice locale is a capability profile, not a single hardcoded language flag.

Where voice is enabled, the configured speech stack must define:

- STT provider/model;
- TTS provider/voice;
- preferred locale;
- wake phrases;
- cancel/stop phrases.

Arabic wake/cancel matching must be conservative. A wake-listening transcript
must not become a persisted conversation turn.

Changes to Arabic STT/TTS defaults require measured evidence on representative,
consented speech rather than model-card claims.

## Android browser client

The first Android target is the existing web client running in a secure browser
context.

Required properties:

- HTTPS;
- responsive layout;
- correct LTR/RTL behavior;
- microphone permission handling;
- reconnect behavior compatible with server-owned turns;
- installable web-manifest metadata where supported;
- deep-link behavior that does not bypass server authorization.

The presence of a web manifest does not imply offline capability.

### Offline boundary

LifeOS currently depends on the server for most meaningful operations. A
service worker may cache static application assets later, but the product must
not describe chat, search, scheduling, or agent execution as offline unless
those paths have an explicit offline data/execution design and tests.

Third-party CDN assets must be pinned or vendored before the mobile surface can
make a strong offline or privacy-contained asset claim.

## Optional native Android companion

A native companion is justified only for capabilities a browser cannot provide
reliably.

Candidate responsibilities:

- secure device pairing;
- Android Keystore-backed device credentials;
- system notifications;
- foreground/background execution allowed by Android policy;
- native wake-word processing;
- offline capture queue;
- optional on-device inference;
- synchronization with the LifeOS server after connectivity returns.

The native companion does not reimplement the LifeOS task, calendar, email,
memory, or agent backends.

The archived pre-LifeOS Secretary code may be mined for isolated ideas or
components after review; it is not merged wholesale into the LifeOS tree.

## Mobile authentication boundary

The existing trusted-network browser model is the only default mobile access
mode.

Before any public mobile endpoint is enabled, the design must provide:

1. pairing initiated from an already trusted operator session;
2. one revocable credential per device;
3. secure credential storage on the device;
4. rotation and revocation;
5. scoped authorization for dangerous actions;
6. replay resistance appropriate to the chosen protocol;
7. rate limits for pairing and authentication attempts;
8. negative tests for bypass, revoked-device access, origin mistakes, and token
   replay;
9. logs that avoid message bodies and other unnecessary personal content.

The MCP OAuth transport may provide reusable concepts, but a mobile client does
not inherit that threat model automatically.

## Model provider boundary

LifeOS supports three conceptually separate ways of reaching model capability.

### Local OpenAI-compatible inference

Local inference remains a first-class path. The provider interface must not
assume that a local server is the only OpenAI-compatible implementation.

### Hosted OpenAI-compatible API

Hosted providers use server-side credentials. API credentials are never sent
to the browser or Android client.

Provider-specific pricing, request fields, and capabilities must be derived
from the resolved provider path rather than inferred from a class name shared
with local llama-server plumbing.

### Subscription-backed CLI engines

CLI engines such as Codex are separate execution surfaces. When their official
authentication supports an eligible subscription, LifeOS may use that
authenticated CLI path according to that product's supported behavior.

LifeOS must not scrape browser cookies, reuse ChatGPT web sessions, or emulate
an unsupported subscription API.

## Local model capability profiles

A local deployment may use different models for different jobs:

- orchestration/tool calling;
- general chat;
- embeddings;
- reranking;
- speech recognition;
- speech synthesis.

Hardware profiles should be benchmarked independently. A low-memory profile may
mix GPU and CPU components rather than forcing every capability onto one model.

## Verification

Localization/mobile changes require evidence appropriate to the affected
surface.

### Browser behavior

Playwright coverage should verify:

- English LTR remains unchanged;
- Arabic switches the document to `lang=ar` and `dir=rtl`;
- locale choice persists according to the defined precedence;
- mixed Arabic/English/URL/code messages render without destructive reordering;
- composer, sidebar, dialogs, and selectors remain usable at Android-sized
  viewports;
- unsafe HTML is not introduced through translations.

### Retrieval and tool use

Benchmark fixtures use synthetic identities and content. Quality measurements
and structured tool assertions gate any model-default change.

### Security

Any change that widens network reach or introduces device credentials requires
a threat-boundary review and negative authentication tests before merge.

## Privacy considerations

Localization must not create new telemetry or external translation calls.
Translation catalogs ship with the application.

Arabic benchmark fixtures committed to the repository use synthetic content.
Consented real speech used for local evaluation is not committed to the public
repository.

Mobile credentials, API keys, and provider tokens remain outside browser
bundles and repository content.

## Related Documents

### Design Context
- [Privacy & Security](security-privacy.md) — Network exposure and privacy
  invariants.
- [Client Surfaces](client-surfaces.md) — Existing web, voice, agent, Hermes,
  and MCP contracts.

### Specifications
- [Frontend Architecture](frontend.md) — Existing vanilla HTML/JS patterns.
- [Search and Indexing](search-indexing.md) — Hybrid retrieval architecture.

### Operational
- [Voice Setup](../../guides/voice-setup.md) — Current voice deployment model.
- [Configuration](../../guides/configuration.md) — Provider and service
  settings.

### Code References
- [Chat surface](../../../web/index.html) — Primary browser client.
- [Chat modules](../../../web/chat/) — Browser chat behavior.
- [LLM client](../../../api/services/llm_client.py) — OpenAI-compatible client
  plumbing.
