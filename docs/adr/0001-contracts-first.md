# 0001. Contracts first, deterministic only

- Status: accepted
- Date: 2026-11-11
- Area: contracts

## Context

Each module exists in Kotlin and TypeScript. Without a shared source of truth the two implementations drift apart. Real LLM output is non-deterministic and cannot be asserted in tests.

## Decision

Shared JSON contracts and fixtures in `contracts/<name>/` define module behavior. The Kotlin and TypeScript implementations must pass the same fixtures. Only deterministic logic is part of a contract; real LLM output never is.

## Consequences

- Both languages stay behaviorally identical, checked by running the same fixtures in each test suite.
- Tests are reproducible and need no API keys or network.
- Non-deterministic provider behavior is not covered by contracts and is tested separately with mock providers.
