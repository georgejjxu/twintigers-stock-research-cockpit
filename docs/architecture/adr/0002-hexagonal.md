# ADR 0002: Hexagonal architecture (ports and adapters)

Date: 2026-10-19. Status: accepted.

## Context
The valuable part is the rules and calculations; the data sources will change (Yahoo is unofficial, a paid
real-time feed may come later, Ghostfolio's API evolves, SEC tags drift). The rules must be testable without the
network and replayable over history.

## Decision
Modules `domain` → `ports` → `application` → `adapters` → `app-mcp`/`cli`. The domain is pure Java (no I/O, no
clock, no JSON; time enters as data). Ports are interfaces owned by the application side. Adapters implement them.
ArchUnit enforces the direction of dependencies, a pure domain, and no cycles.

## Consequences
- Every rule is a pure function of bars/facts: unit tests, property tests and replays need no mocks.
- Swapping Yahoo for another provider, or adding a streaming port, touches one adapter.
- A little more ceremony (interfaces, wiring class) than a single module.
