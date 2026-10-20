# ADR 0009: Everything runs in Docker

Date: 2026-10-19. Status: accepted. Supersedes ADR 0006 (Task Scheduler) and the Windows-toast part of ADR 0007.

## Context
The user asked for the whole setup in Docker. Until now Ghostfolio ran in Docker, while the Research Cockpit ran as
a hidden Windows Java process, the monitor as a Windows scheduled task, and the Java MCP server as a Windows
process started by Claude Code. They do not need Windows notifications (a container cannot show them).

## Decision
- One `docker-compose.yml` at the project root (project `twintigers`) includes `ghostfolio/docker-compose.yml`
  and adds two services built from `java/Dockerfile` (one image, `twintigers/research:local`, with the web,
  worker, MCP and CLI jars; a multi-stage build, so the PC needs no JDK to run it):
  - `research-cockpit`: the Cockpit, bound inside the container to 0.0.0.0 and published to 127.0.0.1:8099 only;
    its Host/Origin checks are unchanged.
  - `research-monitor`: `research-worker loop`, an AUTO run every 30 minutes on the clock. AUTO already decides
    intraday / close-once-a-day / nothing, so the schedule logic stays in the engine. It sleeps in one-minute steps
    against the wall clock, so it catches up right after the PC wakes. It also copies the database each evening
    (`VACUUM INTO`) to `backups/research-YYYY-MM-DD.db`, newest 14 kept.
  - `ghostfolio-backup` (in the Ghostfolio file): a daily `pg_dump` to `backups/ghostfolio-YYYY-MM-DD.sql`.
- Claude's `research-java` MCP server stays stdio: `.mcp.json` runs `docker exec -i research-cockpit java -jar
  /app/research-mcp.jar`. No HTTP MCP endpoint, so no new listening port and no new authentication to manage.
- The research SQLite database lives in the named volume `twintigers_research-data` (ext4 inside Docker's VM),
  not in a Windows folder: SQLite's WAL mode needs shared-memory locking that Windows bind mounts do not
  provide reliably to several containers. Daily backups make it visible in `backups/`.
- The containers see only what they need from the project: `secrets.env` and `portfolio.toml` read-only,
  `java/config` (rules; `tune-rules` writes it), `docs` (reports) and `backups`. They drop all capabilities.
- The Ghostfolio volume name is fixed (`ghostfolio_postgres`) so the data is the same under the new project name.
- Notifications: ntfy only. The toast notifier stays in the code base but is inactive outside Windows.
- `scripts/move-to-docker.ps1` did the one-off move: backups, stop the Windows Cockpit, remove the scheduled task,
  copy the research database and SEC cache into the volume, re-create the Ghostfolio containers.

## Consequences
- One command starts and stops everything (`scripts/start.ps1`, `stop.ps1`); Docker Desktop starts at login and
  restarts the containers. If Docker Desktop is not running, nothing runs, alerts included; the monitor's stale
  check reports the gap on the next run.
- The Python research-tool stays on Windows (it is being retired, ADR 0003) and keeps its own SEC cache in
  `%USERPROFILE%\.cache\research-tool`. The live `parity` CLI check runs on Windows, where Python is.
- Development is unchanged: `mvn verify` on the PC; `scripts/start.ps1` rebuilds the image.
- When Ghostfolio is retired, its include and backup service go; the research services stay.
