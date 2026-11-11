# iLabs — design

Status: draft for owner approval · Date: 2026-11-11

## Intent and why

iLabs ("i" as in AI) is a small public repository where the owner builds and
shows **small, reusable AI modules** that can be dropped into any project:
optimized chat context, structured LLM output, provider abstraction with
fallback, evaluation harnesses, and later RAG, guardrails, on-device vision and
game AI.

Why: the owner's profile is full stack plus mobile. A master's degree in AI
development covered the "AI developer" topics (LLMs, AI architectures,
integration). The goal is to practice and broaden that side and to show it in
the portfolio, with pieces that are practical (usable in other projects), not
large AI apps.

## Goals

- Small modules, each with one clear purpose, tests and a README.
- Every module has a **visible demo**: an Android screen (emulator) and a web
  page (browser), so behaviour can be seen, not just read.
- Each module exists in **Kotlin (KMP)** and **TypeScript**, specified once and
  checked against shared fixtures so both give the same results.
- A **full-stack demo** (web app + Cloudflare Worker) that uses several modules
  together, proving they compose.
- Extract and polish the prototypes already living in `nanodemo` (router/PII
  gateway, RAG, agent, guard, bench) instead of rewriting them.

## Non-goals

- Not a framework or a published SDK with a stability promise (for now).
- Not large end-user AI products.
- Not a training/fine-tuning repo.

## Anti-goals (failure even if it "works")

- Demos that only work with the owner's private keys.
- Kotlin and TS implementations drifting apart silently.
- Modules coupled to nanodemo or to one UI.

## Constraints

- Public repo under `Sebas1705`, MIT (like Owlsplit). **No secrets in it.**
  Secrets, if any, live in Doppler (project name to be created, e.g. `ilabs`).
- Cloud providers (Gemini etc.) are optional and reached only through the
  Worker proxy or a user-supplied key; default demos run with a **mock
  provider** and, on Android, a local model.
- Repo content in English (code, docs, commits, PRs). Demo UI texts in es + en.
- Commits as the owner, no trailers. Push/PR/tag only when the owner asks.
- Free-tier realities: Gemini free limits (~5 req/min) must be handled
  (the nanodemo retry executor is the prior art).

## Approaches considered

1. **A. Kotlin Multiplatform library + nanodemo as client.** Coherent with the
   owner's stack and reuses prototypes. Less web visibility.
2. **B. Standalone mini-projects, one demo per module.** Fast and showy, but
   little reuse.
3. **C. Same modules in Kotlin and TypeScript.** Shows full stack and mobile;
   roughly double the work.

**Picked by the owner: a hybrid of all three** — modules specified once,
implemented in Kotlin (KMP) and TypeScript, each with built-in demos, plus a
full-stack demo. Why: matches the goal of multiplatform modules that also apply
to a full-stack project, with demos to observe them.

## Decided design

```
ilabs/
├─ modules/<name>/{kotlin,ts,README.md}
├─ demos/{android,web,fullstack}
├─ contracts/      shared specs and fixtures (JSON)
└─ docs/           ADRs, per-module docs, specs
```

- **Contracts first**: each module has a spec and JSON fixtures in
  `contracts/<name>/`. Both implementations run the same fixtures in CI
  (same approach as Owlsplit's ledger port).
- **First batch (4 modules):** `context` (sliding window, incremental summary,
  token budget), `structured` (JSON with schema, validation, retry),
  `providers` (one interface, fallback, rate-limit handling), `evals`
  (compare models/prompts on a small suite).
- **Later:** RAG-lite (embeddings + SQLite vector search), guardrails
  (PII, prompt injection), on-device vision (LiteRT), game NPC with a local
  model — extracted from nanodemo where prototypes exist.
- **Demos:** Android = one screen per module in a single demo app;
  web = one page per module; fullstack = web app + Cloudflare Worker proxy
  composing the modules. Demos must work offline with the mock provider.
- Repo name: **iLabs** (GitHub `Sebas1705/ilabs`, local folder
  `Repos\Sebas1705\ilabs`).

## Calls made after the builder check (mine, to confirm)

- **"Same results" = deterministic logic only.** Fixtures cover windowing,
  budget math, schema validation, retry/backoff and fallback state machines,
  driven by scripted mock-provider responses. Prompt text and real LLM output
  are NOT in the contract. Token counting is a documented approximation
  (chars/4 by default, pluggable counter), identical in both languages.
- **One provider contract and one scripted mock, shared.** `providers` defines
  the interface, the error taxonomy (rate limit, timeout, invalid output,
  unavailable) and the mock script format (`call N → response | error`) in
  `contracts/providers/`; both languages implement it. It is built first.
- **Local on-device model is NOT in the first batch.** The first Android demo
  is mock-only (plus optional cloud through the Worker). Local models arrive
  with the later vision/NPC/RAG modules, delivered as a download (never in the
  repo), reusing nanodemo's approach.
- **Worker = thin proxy, in the first batch but last.** Pass-through to Gemini
  with the key as a Worker secret, simple rate limiting and an origin
  allowlist; a user-supplied key stays in the browser and is sent per request,
  never stored. It is not deployed publicly until the owner approves.
- **nanodemo reuse = copy, not move.** nanodemo is Kotlin-only, so the TS side
  is greenfield. nanodemo does not depend on iLabs at first; a later PR may
  flip that. Both are the owner's repos, so licensing is not a blocker.
- **Kotlin targets: Android + JVM** (wasm/JS and iOS later, as needed). One
  Gradle module per module, built from source; no Maven/JitPack publishing yet.
- **Delivery in vertical slices per module** (contract → Kotlin → TS → demos →
  README), full-stack demo last. No big-bang release; first tag after the
  first batch and only when the owner asks.
- **`evals`:** hand-written cases with deterministic graders (exact, regex,
  schema). An LLM-as-judge grader exists but needs a real provider, so CI and
  default demos use mock providers only.

## Left to the builder / my calls (to confirm)

- Build tooling: Gradle (KMP) for Kotlin; pnpm workspace + Vite + Vitest for TS.
  *(my call)*
- Android demo: Compose, Material 3, shared theme; web demo: plain
  Vite + TS or lightweight React. *(my call, leaning Vite + React as in the
  portfolio)*
- Module order inside the first batch: `providers` → `structured` → `context`
  → `evals`. *(my call: providers is the base the others plug into)*
- CI: GitHub Actions running both test suites and the contract fixtures.
- Deployment of the web demo (Cloudflare Pages vs Firebase Hosting) and of the
  Worker: decide at implementation; deploys to production require asking.
- Exact fixtures format and token-counting approximation (no heavy tokenizer
  dependency by default).
