# Price alerts and notifications: user guide

The research monitor checks your holdings and your own price alerts on a schedule and notifies you on your phone (ntfy app)
and in the browser (ntfy.sh web page). It only reads market data and your holdings; the only thing it
writes is its own list of alerts. Nothing here is investment advice, and no alert places an order.

## What notifies you, out of the box

| Kind | Default |
|---|---|
| Your threshold alerts | Every one you add (none yet). |
| Price rules (20, see the rule catalog) | **None.** Five years of your holdings' history were split in two: stricter versions of each warning and watch rule were chosen on the first half and checked on the second. None went the expected way ≥ 55 % of the time at both 5 and 20 days in both halves, so all 11 directional rules are now *info* (still shown in the Cockpit and by `price_signals`, not pushed). Details: [rule-tuning.md](rule-tuning.md). Subscribe to a rule by hand if you still want it. |
| Fundamental flags (F01–F11) | Every **warning** flag, once per new filing period (e.g. "Free cash flow fell 44%"). |
| Monitor health | A notice if the monitor missed more than a trading day (PC off or asleep, Docker Desktop not running). |

## When it runs

The monitor runs in Docker as the container **research-monitor** (started with everything else by
`scripts\start.ps1`; it restarts by itself when Docker Desktop starts, and Docker Desktop starts when you log in).
It wakes every 30 minutes on the clock and decides what is due:

- Weekdays from 09:30 to 16:30 Eastern: your threshold alerts against the latest Yahoo quote
  (delayed about 15 minutes).
- Weekdays after 16:15, once a day: the close run. Price rules on the day's final bar, your alerts against the
  close, and fundamental flags. If the PC slept through it, it runs at the next wake-up that evening.
- TSX and NYSE holidays are skipped (`java\config\holidays\*.txt`). Add next year's dates each December.
- Every evening after 18:30 it copies the research database (alerts, watch list, transactions) to
  `backups\research-YYYY-MM-DD.db`, keeping the newest 14.

Log, status, test notification (PowerShell):

```powershell
docker compose logs -f research-monitor
docker exec research-monitor java -jar /app/research-worker.jar status
docker exec research-monitor java -jar /app/research-worker.jar test-notify
```

Or ask Claude: "Is the monitor running?"

## Adding a threshold alert

Ask Claude in this folder, for example:

- "Alert me if ENB.TO goes above 75." → `above`, level 75 (a sell-watch)
- "Alert me if FTS.TO drops below 70." → `below`, level 70 (a buy-watch)
- "Tell me if IBM moves 10 % up from 232." → `move_pct`, pct 10, reference 232 (use −8 for an 8 % drop)
- "Put a 3-ATR trailing stop on KXS.TO from today." → `trailing_atr`, atr_multiple 3: it triggers when the close
  falls below (highest close since today − 3 × the 14-day average true range)

Or on the command line (PowerShell; the command runs inside the Cockpit's container):

```powershell
docker exec research-cockpit java -jar /app/research-cli.jar alerts add above ENB.TO --level 75 --note "trim?"
docker exec research-cockpit java -jar /app/research-cli.jar alerts add move_pct IBM --pct -8 --reference 232
docker exec research-cockpit java -jar /app/research-cli.jar alerts add trailing_atr KXS.TO --atr 3
docker exec research-cockpit java -jar /app/research-cli.jar alerts list
```

Symbols use Yahoo's form: TSX listings end in `.TO`. An alert notifies once when its condition becomes true,
then rests for 24 hours (`alert.cooldownHours` in `java\config\rules.properties`) and re-arms only after the
condition has cleared. A price hovering around your level does not notify again and again.

## Subscribing to rules

- "Notify me of every seller-controlled drop on ENB.TO." → subscription, source price, rule
  `PA02_seller_controlled_drop`, scope `ENB.TO`
- "Notify me of all candlestick warnings on my holdings." → rule `K*`, scope `*`, min severity warning
- "Also send me watch-level fundamental flags." → source fundamental, rule `*`, min severity watch

CLI: `alerts subscribe ENB.TO price PA02_seller_controlled_drop --min-severity warning`.
To re-derive the defaults from a fresh replay: `alerts tune-defaults --years 2 --min-hit 55 --min-fires 10 --out docs\alerts\replay-defaults.md`
(this replaces only the subscriptions marked "default").

## Muting and removing

- "Mute alert 3 for a week." / "Mute subscription 1 for 2 days." → `mute_alert` (it keeps tracking, it just
  stays quiet)
- "Remove alert 3." → `remove_alert` (kept in the database for the record, no longer checked)

CLI: `alerts mute alert 3 --days 7`, `alerts remove subscription 1`.

## Your phone (ntfy)

1. Install **ntfy** from the App Store or Google Play (free, no account needed).
2. Tap **+**, choose "Use another server" only if you self-host; otherwise keep ntfy.sh.
3. Enter the topic name: the `NTFY_TOPIC` value in `secrets.env` (starts with `rj-`).
4. Send yourself a test: `java -jar java\app-worker\target\research-worker.jar test-notify`.

Privacy: ntfy.sh topics are not password-protected. Anyone who knows the topic name can read the messages
(symbol, rule and prices; never account numbers or balances). The topic is a 40-character random string kept
only in `secrets.env`; do not share it. For full control you can run your own ntfy server in Docker and set `NTFY_URL` (and `NTFY_TOKEN` if you enable authentication). To stop phone pushes, delete
the `NTFY_TOPIC` line.

## If something looks wrong

- No notifications at all: `research-worker.jar status` (above) shows the last runs and any failed sends. Failed
  sends are retried (1, 5, 15, 30, 60 minutes, then every 2 hours, up to 12 times) and never sent twice. Check
  that the ntfy app subscribes to the `NTFY_TOPIC` value in `secrets.env`.
- "Research monitor was not running": the PC was off or Docker Desktop was not running. Alerts for the missing days were
  not checked.

## Channels

ntfy only (phone app and web). The monitor runs in Docker, which cannot show desktop notifications. Send a test with
`docker exec research-monitor java -jar /app/research-worker.jar test-notify`.
