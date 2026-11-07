# Computer access: querying NOOP on the phone from an agent

**Fork-only, Android only.** The NOOP Android app (this fork, `zeqing-guo/noop`) can open a
ten-minute, Wi-Fi-only HTTP window so an AI agent on a computer on the same network can read what
the AI Coach is allowed to see and run read-only SQL over the app database. Upstream's scope
([`AGENTS.md`](../AGENTS.md)) has no listener of any kind. This feature departs from that on
purpose, so keep it in the fork and do not shape it for upstreaming.

This page is written for two readers. The wearer needs [Starting a window](#starting-a-window).
An agent needs [For agents](#for-agents) onward.

## Starting a window

1. Phone and computer on the **same Wi-Fi**. Cellular, VPN and Tailscale addresses are never used.
2. NOOP → More → **AI Coach**, scroll to the **Computer access** card (电脑查询) at the bottom, tap
   **Start for 10 minutes** and confirm.
3. The card shows the address (for example `http://192.168.1.151:8765`) and a countdown, and a
   notification shows the same countdown with a **Stop** action.
4. **Copy instructions for the agent** copies a short English brief with the address. Paste it
   into the agent.
5. When the agent asks for access, the phone shows **Allow this computer?** with the agent's name
   and IP address, on whatever screen is open. Allow it only if you just asked an agent to connect.
   Dismissing the dialog denies the request.

The window closes by itself after ten minutes, or on **Stop** in the card or the notification.
Closing it invalidates every token. Start a new window for more time.

## For agents

Base URL: whatever the wearer gave you, `http://<phone-ip>:8765` by default (the port falls back
to a random one when 8765 is taken, so use the URL as given). Every reply is JSON. There is no
TLS: this is a LAN-only, short-lived window.

### Discovery (optional)

While a window is open the phone advertises `_noop-coach._tcp` over mDNS:

```bash
dns-sd -B _noop-coach._tcp local.                  # macOS: browse
dns-sd -L "NOOP coach" _noop-coach._tcp local.     # resolve host and port
```

Using the resolved `<name>.local:<port>` as the host is accepted, as is the IP address.

### 1. Read the usage document

```bash
curl -s http://192.168.1.151:8765/
```

No authentication. Returns `expires_at_ms`, `expires_in_s`, the endpoint list and the row limits.

### 2. Pair, and wait for the wearer to allow it

```bash
curl -s -X POST -d '{"client":"claude-code"}' http://192.168.1.151:8765/v1/pair
# 202 {"request_id":"65ab…","status":"pending","poll":"/v1/pair/65ab…"}

curl -s http://192.168.1.151:8765/v1/pair/65ab…
# {"status":"pending"}                          keep polling, every ~2 s
# {"status":"approved","token":"…","expires_at_ms":…}
# 403 {"status":"denied", …}                    the wearer said no; stop
```

`client` is the name the wearer sees in the dialog (40 printable characters at most). Tell the
wearer to look at the phone when you send it. The token is handed only to the IP address that made
the request, and is accepted only from that address. Never print the token into a chat or a file.

### 3. Query

Send `Authorization: Bearer <token>` on every request below.

| Endpoint | Returns |
| --- | --- |
| `GET /v1/coach/context` | `{"consent": bool, "include_signals": bool, "context": "<text>"}`, the exact data block the AI Coach is sent (last 14 days, 30-day averages, recent workouts, today's stress line, plus on-device signals when that second opt-in is on). When the wearer has not turned on **Let the coach use my data**, `consent` is false and `context` is only a note, with no numbers. |
| `GET /v1/schema` | `{"tables": [{"name", "type", "columns": [{"name", "type", "not_null", "primary_key"}]}]}` for every app table. |
| `POST /v1/sql` with `{"sql": "SELECT …", "limit": 200}` | `{"columns": [...], "rows": [[...]], "row_count", "truncated", "elapsed_ms"}`. Also `GET /v1/sql?q=<url-encoded SQL>&limit=…`. |

The database is readable whether or not coach consent is on; consent only governs
`/v1/coach/context`.

A minimal client (the bearer token stays in a variable, never in the source):

```python
import json, time, urllib.request

BASE = "http://192.168.1.151:8765"

def call(path, body=None, token=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                 method="POST" if body is not None else "GET")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

pending = call("/v1/pair", {"client": "my-agent"})
while (s := call(pending["poll"]))["status"] == "pending":
    time.sleep(2)
token = s["token"]
print(call("/v1/sql", {"sql": "SELECT day, recovery FROM dailyMetric ORDER BY day DESC", "limit": 7}, token))
```

`urllib` raises on 4xx replies; read the error body (`e.read()`) for the message.

### SQL rules and limits

- **One statement**, starting with `SELECT`, `WITH`, `VALUES` or `EXPLAIN`. A trailing `;` is fine;
  a second statement is rejected.
- `ATTACH`, `DETACH`, `PRAGMA`, `VACUUM` and every write keyword are rejected. For table
  structure use `/v1/schema`, or the table-valued function
  `SELECT name FROM pragma_table_info('workout')`, which is a `SELECT` and allowed.
- The statement runs on a separate read-only connection with `PRAGMA query_only = 1`, so nothing
  can be changed even past the check.
- `limit`: default 200, maximum 5000 rows. A reply over about 4 MB is cut short. `truncated: true`
  means there were more rows: page with `ORDER BY … LIMIT … OFFSET …` or narrow the query.
- Each query is cancelled after **15 seconds** (`query failed: interrupted`). One query runs at a
  time.
- BLOB cells come back as `{"blob_base64": "…", "bytes": n}`, or `{"bytes": n, "omitted": true}`
  above 64 KB.

### Writing fast queries

The large tables (`hrSample`, `rrInterval`, `stepSample`, `ppgWaveformSample`, …) hold hundreds
of thousands of rows or more. Their primary key is `(deviceId, ts)`, so a query is fast only when
it pins `deviceId` with `=` and bounds `ts`:

```sql
SELECT ts, bpm FROM hrSample
WHERE deviceId = 'my-whoop' AND ts BETWEEN 1793890327 AND 1793892600
ORDER BY ts
```

A correlated subquery over `hrSample` for every workout row, or a filter on `ts` alone, scans the
table and hits the 15-second limit. Fetch the outer rows first, then one bounded query per row.

### What is in the database

Start with the ids, because the same table holds rows from several sources:

```sql
SELECT deviceId, count(*), min(day), max(day) FROM dailyMetric GROUP BY deviceId
```

Typical ids: `my-whoop` (strap and imported WHOOP data), `my-whoop-noop` (scores NOOP computed
from a live strap), `whoop-<uuid>` (a live strap banked under its own id), `health-connect` and `apple-health` (imports), `noop-journal` (in-app journal
answers). The app merges these for display; a raw query does not, so filter or group by
`deviceId`.

| Table | What a row is | Useful columns |
| --- | --- | --- |
| `dailyMetric` | One day per source | `day` (local `YYYY-MM-DD`), `recovery` (charge %), `strain` (effort), `totalSleepMin`, `deepMin`, `remMin`, `lightMin`, `efficiency`, `avgHrv` (ms), `restingHr`, `spo2Pct`, `respRateBpm`, `skinTempDevC`, `steps`, `activeKcalEst` |
| `sleepSession` | One sleep | `startTs`, `endTs`, `efficiency`, `restingHr`, `avgHrv`, `stagesJSON` |
| `workout` | One session | `startTs`, `endTs`, `sport`, `source` (`manual`, `health-connect`, …), `avgHr`, `maxHr`, `strain`, `energyKcal`, `distanceM`, `zonesJSON` |
| `dismissedWorkout`, `dismissedSleep` | Sessions the wearer hid | `deviceId`, `startTs`; exclude these to match the app |
| `hrSample` | Heart rate, about 1 Hz while worn | `ts`, `bpm` |
| `rrInterval` | Beat-to-beat intervals | `ts`, `rrMs` |
| `journal` | A yes/no journal answer | `day`, `question`, `answeredYes`, `numericValue`, `notes` |
| `labMarker` | A Lab Book value | `day`, `markerKey`, `category`, `value`, `valueText`, `unit` |
| `coachMessage` | The AI Coach conversation | |

Times: `ts`, `startTs` and `endTs` are **Unix seconds** (UTC); render them with
`datetime(ts, 'unixepoch', 'localtime')`. `day` is the phone's local date. The full schema with
types is `GET /v1/schema`; the Room entities are in
[`android/app/src/main/java/com/noop/data/Entities.kt`](../android/app/src/main/java/com/noop/data/Entities.kt).

### Errors

| Status | Meaning | What to do |
| --- | --- | --- |
| 400 | Bad JSON, a rejected statement, or an SQL error (message in `error`) | Fix the query |
| 401 | Missing or unknown token, or a token used from another IP | Pair again |
| 403 | The wearer denied the pairing request | Stop and ask the wearer |
| 404 | Unknown endpoint or pairing id | `GET /` |
| 410 | The window has ended | Ask the wearer to start a new one; tokens do not carry over |
| 421 | The `Host` header is not the phone's address | Use the IP URL or the `.local` name as given |
| 429 | Four pairing requests are already waiting | Wait for the wearer to answer |
| connection refused / timeout | No window open, the phone left Wi-Fi, or the IP changed | Ask the wearer to check the card |

## Security model

- Nothing listens until the wearer taps Start; the window always ends after ten minutes.
- The server binds the Wi-Fi interface address only and drops peers outside private ranges.
- Every computer is approved on the phone; tokens are random per window and bound to the
  requesting IP.
- `Host` must be the bound address or a `.local` name on its port, which stops a web page the
  computer has open from reading replies through DNS rebinding.
- SQL is read-only in three layers: the statement check, `OPEN_READONLY`, and `query_only`.
- The token is never logged. The log records only that a window opened (with its address) and
  closed (with a query count).
- Anyone allowed can read the **whole** database, including raw strap samples, journal,
  Lab Book and coach messages. The confirmation dialog says so.

## Source

| File | Role |
| --- | --- |
| [`ai/CoachLanServer.kt`](../android/app/src/main/java/com/noop/ai/CoachLanServer.kt) | Window lifecycle, socket, address choice, mDNS |
| [`ai/CoachLanRouter.kt`](../android/app/src/main/java/com/noop/ai/CoachLanRouter.kt) | Endpoints, auth, Host check |
| [`ai/CoachLanSession.kt`](../android/app/src/main/java/com/noop/ai/CoachLanSession.kt) | Pairing requests, approvals, tokens, deadline |
| [`ai/ReadOnlySql.kt`](../android/app/src/main/java/com/noop/ai/ReadOnlySql.kt) | Statement check and the read-only connection |
| [`ai/LanHttp.kt`](../android/app/src/main/java/com/noop/ai/LanHttp.kt) | Minimal HTTP/1.1 parsing and replies |
| [`ai/CoachLanService.kt`](../android/app/src/main/java/com/noop/ai/CoachLanService.kt) | Foreground service and notification |
| [`ui/CoachLanCard.kt`](../android/app/src/main/java/com/noop/ui/CoachLanCard.kt) | Coach-page card and the approval dialog |
| [`ai/AiCoach.kt`](../android/app/src/main/java/com/noop/ai/AiCoach.kt) `coachDataContext` | The coach's data block, shared with the chat |

Paths are under `android/app/src/main/java/com/noop/`. Tests:
`android/app/src/test/java/com/noop/ai/CoachLanTest.kt` and `AndroidReadOnlySqlTest.kt`.

On macOS there is a separate, stdio-only route with no network listener:
[`Packages/NoopLocalAccess`](../Packages/NoopLocalAccess/README.md) (`noop-local-access mcp`).
