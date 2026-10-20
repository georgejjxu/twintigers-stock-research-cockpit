# ADR 0006: Windows Task Scheduler runs the monitor

Date: 2026-10-19. Status: superseded by ADR 0009 (the monitor runs in Docker).

## Context
Alerts must be evaluated during market hours (thresholds) and after the close (rules on the final bar), on
The user's Windows PC, without a server. Options: a long-running Java service, a Windows service wrapper, or the
built-in Task Scheduler.

## Decision
A per-user scheduled task (`scripts\install-worker.ps1`, no admin rights) starts `javaw -jar research-worker.jar run
--mode auto` every 30 minutes on weekdays 09:30–16:30 Eastern and at 16:30 and 18:00. The worker decides from the
clock and the holiday files (`java\config\holidays`) whether to do an intraday run, the once-a-day close run, or
nothing; it exits after each run. "Run only when logged on" so Windows notifications can be shown; "start when
available" so a run missed during sleep happens on wake.

## Consequences
- No process to keep alive, no memory held between runs; each run is a fresh JVM (about 1–3 s).
- If the PC is off, nothing runs. The next run detects the gap (heartbeat table `runs`) and sends a
  "monitor was not running" notice; `alert_status` shows it too.
- State lives in SQLite (alert states, outbox, runs), so runs are independent and idempotent.
- Holidays must be added yearly; the calendar can report a missing year (`MarketCalendar.covers`).
