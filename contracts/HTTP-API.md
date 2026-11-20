# Host HTTP API (protocol v1)

The Android host runs a small HTTP server on the home Wi-Fi
([`MediaServer.kt`](../android/sync-core/src/main/kotlin/com/theatresync/core/MediaServer.kt)).
It's announced via Bonjour/DNS-SD as `_theatresync._tcp` with TXT keys `ip`, `port`, `v`.
JSON bodies are [`sync-message.v1`](schemas/sync-message.v1.schema.json) envelopes.

| Request | Response |
|---|---|
| `GET /media` (+ `Range: bytes=…`), `HEAD /media` | The movie file. `206` for ranges, `416` if unsatisfiable. Used by the Chromecast and listeners. |
| `GET /clock?seq=<int>&t0=<listener ms>&id=<listener id>` | `clock_pong` with host receive (`t1`) and send (`t2`) times on the host's monotonic clock. `id` counts the listener as connected for 5 s. |
| `GET /state` | `playback_state`: the Chromecast's picture position (`mediaPositionMs`) at `hostTimeMs`. `stopped` before a movie starts. |
| `GET /hello` | `hello` for the host. |
| `GET /listen` (also `/`) | TapIn listening page (HTML) for browsers. |
| `GET\|HEAD /audio` (+ `Range`) | The movie's sound for TapIn pages: a sound-only MP4 when possible, else the whole movie. |
| `GET /guest?id=web-…&name=&drift=<ms>&status=&hidden=0\|1` | TapIn page report (~1/s while listening). The first report adds the guest on the host. Answer (plain JSON, not an envelope): `{"muted":bool,"volume":0-100,"removed":bool}`. A removed id is never re-added. Dropped after 4 s without a report (10 min if the last report said `hidden=1`). |
| `POST\|GET /leave?id=web-…` | The guest tapped Stop or closed the page (`sendBeacon`): removed at once. `204`. |

Listener algorithm (Android and iOS identical):

1. Clock: ping `/clock` until 8 samples, then every ~1 s; offset = median of the fastest 25 % ([ClockSync](../android/sync-core/src/main/kotlin/com/theatresync/core/ClockSync.kt)).
2. Every 250 ms: fetch `/state`; target = picture position at host-now − own sound delay.
3. [DriftCorrector](../android/sync-core/src/main/kotlin/com/theatresync/core/DriftCorrector.kt): drift ≥ 400 ms → seek; drift ≥ 40 ms → speed ±(drift/2000), max ±5 %, until within 10 ms.
