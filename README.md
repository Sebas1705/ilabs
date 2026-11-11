# iLabs

iLabs ("i" as in AI) is a collection of small, reusable AI modules written in Kotlin and TypeScript, each with a visible demo.

**Status:** early. The first module (providers) is done, with a web demo and an Android demo.

## Modules

| Module | Status | What it is |
|---|---|---|
| [providers](modules/providers/README.md) | done | Mock, retrying and fallback AI providers, identical in Kotlin and TypeScript |
| structured | planned | Structured output with validation and repair |
| context | planned | Context windows and prompt budgeting |
| evals | planned | Deterministic evals |

## Layout

```
modules/<name>/{kotlin,ts}   one module, one implementation per language
demos/{android,web}          visible demos that use the modules
contracts/<name>/            shared JSON contracts and fixtures
docs/                        design spec, plans and ADRs
```

## No API keys needed

The demos run with a mock provider, so you can try everything without any API key.

## Prerequisites

- Node.js with corepack (the repo pins pnpm 12 through the `packageManager` field)
- JDK 21 and the Android SDK (set `ANDROID_HOME` or `sdk.dir` in `local.properties`), for the Kotlin module and the Android demo

## Run it

```sh
corepack pnpm install                           # install the TypeScript workspace
corepack pnpm -r test                           # TypeScript tests (module + web demo)
corepack pnpm --filter @ilabs/demo-web dev      # web demo at http://localhost:5173
./gradlew :modules:providers:kotlin:jvmTest     # Kotlin tests against the shared fixtures
./gradlew :demos:android:installDebug           # Android demo on a connected device or emulator
```

## Docs

- [Design spec](docs/superpowers/specs/2026-11-11-ilabs-design.md)
- [ADR 0001: Contracts first, deterministic only](docs/adr/0001-contracts-first.md)

## License

MIT. See [LICENSE](LICENSE).
