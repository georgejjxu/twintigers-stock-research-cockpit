# ADR 0007: Windows toast and ntfy, through a durable outbox

Date: 2026-10-19. Status: accepted; the Windows toast part is superseded by ADR 0009 (ntfy only, in Docker).

## Context
The user wants alerts on the PC and on their phone, with nothing lost and nothing sent twice. Options: e-mail (needs
SMTP credentials), SMS (paid), Telegram/Pushover (accounts, keys), Windows toast (built in), ntfy (free app, no
account, self-hostable).

## Decision
- Windows toast through the built-in Windows Runtime API, invoked via Windows PowerShell; title and body are passed
  in environment variables, never in script text. No install.
- ntfy push (default server ntfy.sh, topic = 40 random hex characters in `secrets.env`), via its JSON API.
- Both behind a `Notifier` port. Every notification is first written to an `outbox` table, one row per (event key,
  channel) with a unique constraint, then dispatched. A failure is retried with back-off (1, 5, 15, 30, 60 min, then
  2 h, max 12 tries); a success is marked sent and never repeated. Event keys: `signal|symbol|rule|bar date`,
  `alert|id|trigger time`, `fundamental|issuer|rule|period end`, `stale|last run date`.

## Consequences
- Works with no accounts and no cost. A failed phone push does not repeat the desktop toast.
- Privacy: an ntfy.sh topic is readable by anyone who knows its name. Messages carry symbol, rule and prices only
  (no account data); a self-hosted ntfy server in Docker is the stronger option (documented in the user guide).
- Toasts appear under "Windows PowerShell" and only while the user is logged on.
