# ADR 0003: The Python server is the reference until parity is shown

Date: 2026-10-19. Status: accepted; completed in 1.0.0 (2026-10-20): the Python research-tool was removed from the project.

## Context
The Python research-tool already produces sourced health tables that the user has used. A rewrite risks silent
numerical differences (period matching, restatements, tag fallbacks, rounding).

## Decision
Keep the Python server running and registered in `.mcp.json`. The Java port must reproduce its numbers on the
same inputs before it replaces anything:
- Offline golden test in `mvn verify`: Python's output for the synthetic SEC fixture is committed; Java must match
  every cell and every ported flag.
- Live parity: `research-cli parity` runs `java/parity/python_dump.py` and the Java table on the same cached SEC
  files (both read `~/.cache/research-tool`) and compares cell by cell. Differences are either listed as
  intended (with the reason) or fail the check.

## Consequences
- First run (2026-10-19): 347 of 357 cells identical on ENB, TU, FTS, BNS, IBM; 10 intended differences, all in
  Enbridge's total/net debt (the debt fix), plus the bank exemption for Scotiabank; 0 unexpected.
- Deliberate improvements are visible and reviewable, not accidental.
- The Python server is retired only when the Java engine covers everything it does (PDF reports are the gap).
