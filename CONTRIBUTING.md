# Contributing

Thanks for your interest. Issues and pull requests are welcome.

## Build and test

- JDK 25 or newer and Maven 3.9: `cd java` then `mvn verify`. All tests are offline (HTTP is faked with WireMock or a
  local fake server).
- Run the app: `docker compose up -d --build` (or `start.cmd` on Windows), then <http://localhost:8099>.

## Guidelines

- The architecture is hexagonal: `domain` (pure Java, no I/O) → `ports` → `application` → `adapters` → apps. ArchUnit
  tests enforce the layering; only `adapters/Wiring` may name adapters. Read
  [docs/architecture/overview.md](docs/architecture/overview.md) and the ADRs first.
- Every number shown must be traceable: carry its source (filing, provider) or its formula, and test calculations on
  hand-checkable examples.
- No feature may place orders or present a signal as a recommendation.
- Write tests with the change; keep `mvn verify` green.
- Never commit secrets or personal data (`secrets.env`, `portfolio.toml`, `backups/`).
