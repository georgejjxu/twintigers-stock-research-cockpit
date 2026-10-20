# Security

The Stock Research Cockpit is meant to run on your own computer for one person. Its design:

- The web app listens on 127.0.0.1 only (Docker publishes the port to the loopback interface) and answers only
  requests addressed to `localhost` (a guard against DNS rebinding).
- Every change (POST) needs a random token issued when the app starts, and a local `Origin` when one is sent.
- A strict Content-Security-Policy: scripts only from the app itself, no framing.
- The containers drop all Linux capabilities, run as a non-root user and see only the files they need.
- Secrets (`secrets.env`) are mounted read-only and never logged; they are in `.gitignore`.
- No order placement exists anywhere in the code.

Do not expose port 8099 to a network or the internet: there is no login. If you need remote access, use a VPN or an
SSH tunnel to the machine.

## Reporting a problem

Please report security issues privately by email to **georgejxu@hotmail.com** rather than in a public issue. Include what you found, how to reproduce it and the version (commit) you used.
