# iLabs

iLabs ("i" as in AI) is a collection of small, reusable AI modules written in Kotlin and TypeScript, each with a visible demo.

## Layout

```
modules/<name>/{kotlin,ts}   one module, one implementation per language
demos/{android,web}          visible demos that use the modules
contracts/<name>/            shared JSON contracts and fixtures
docs/                        design spec, plans and ADRs
```

## No API keys needed

The demos run with a mock provider, so you can try everything without any API key.

## Docs

- [Design spec](docs/superpowers/specs/2026-11-11-ilabs-design.md)
- [ADR 0001: Contracts first, deterministic only](docs/adr/0001-contracts-first.md)

## License

MIT. See [LICENSE](LICENSE).
