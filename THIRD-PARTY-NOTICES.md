# Third-party software

The project's own code is under the license in [LICENSE](LICENSE). It bundles or depends on the following.

## Shipped in the web app (`java/app-web/src/main/resources/static/vendor/`)

| Component | Version | License | Notes |
|---|---|---|---|
| [htmx](https://htmx.org) | 2.0.11 | 0BSD / BSD-2-Clause (see `vendor/htmx/LICENSE`) | |
| [TradingView Lightweight Charts](https://github.com/tradingview/lightweight-charts) | 5.2.1 | Apache-2.0 (see `vendor/lightweight-charts/LICENSE`) | The license asks for attribution to TradingView with a link; the charts keep the TradingView logo link. |
| Twintiger Retirement Planner stylesheets (`vendor/twintiger/`) | 2026-10 | Apache-2.0 (this project) | From the same owner's Retirement Planner, released with this project; see `vendor/twintiger/VENDOR.md`. |

## Java libraries (Maven, see `java/pom.xml`)

| Library | License |
|---|---|
| MCP Java SDK | MIT |
| Jackson 3 | Apache-2.0 |
| Resilience4j | Apache-2.0 |
| SLF4J | MIT |
| sqlite-jdbc (xerial) | Apache-2.0 |
| Flyway (community) | Apache-2.0 |
| tomlj | Apache-2.0 |
| JUnit 5 / 6 (tests) | EPL-2.0 |
| AssertJ (tests) | Apache-2.0 |
| jqwik (tests) | EPL-2.0 |
| WireMock (tests) | Apache-2.0 |
| ArchUnit (tests) | Apache-2.0 |

## Container images

`maven:3.9-eclipse-temurin-25` (build) and `eclipse-temurin:25-jre` (run): Eclipse Temurin is GPL-2.0 with the
Classpath Exception; the images are pulled from Docker Hub at build time, not redistributed here.

## Data

No market data is redistributed. At run time the app reads SEC EDGAR, Yahoo Finance, CNN Business and alternative.me
endpoints under their own terms of use (see README, "Data sources and their terms").
