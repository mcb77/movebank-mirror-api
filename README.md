# movebank-mirror-api

[![CI](https://github.com/mcb77/movebank-mirror-api/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/mcb77/movebank-mirror-api/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/mcb77/movebank-mirror-api)](https://github.com/mcb77/movebank-mirror-api/releases)
[![license](https://img.shields.io/badge/license-LGPL--2.1-blue.svg)](LICENSE)

A read-only HTTP server that **replays** the [Movebank REST API (v1)](https://github.com/movebank/movebank-api-doc/blob/master/movebank-api.md)
on top of a local on-disk mirror produced by [`movebank-mirror`](https://github.com/mcb77/movebank-mirror).

This is **not** a Movebank clone. It has no database, no UI, no ingest, no
license enforcement, and no auth. It is a single endpoint
(`/movebank/service/direct-read`) that reads from your `movebank-mirror`
directory and serves the same CSV that `movebank-api-client` expects, so any
tool written against the live Movebank API works against the mirror without
changes — useful for offline analysis, reproducible pipelines, and avoiding
hammering Movebank during development.

```
                       ┌──────────────────────────────────────┐
                       │  movebank-mirror (CLI / library)     │
  Movebank API ──────► │  pulls metadata + event CSVs to disk │
                       └────────────────┬─────────────────────┘
                                        │ <mirror-dir>/
                                        ▼
                       ┌──────────────────────────────────────┐
                       │  movebank-mirror-api  (this repo)    │  ◄── movebank-api-client / curl / etc.
                       │  serves Movebank's read API surface  │
                       └──────────────────────────────────────┘
```

---

## Compatibility

Verified to be a drop-in replacement for the live Movebank API for the
canonical client libraries used in practice:

| Client                                                                      | Language | Verified by                                                       |
|-----------------------------------------------------------------------------|----------|-------------------------------------------------------------------|
| [`movebank-api-client`](https://github.com/mcb77/movebank-api-client)       | Java     | [`DirectReadIntegrationTest`](src/test/java/de/firetail/compat/movebank/mirror/api/DirectReadIntegrationTest.java) (in-process, every build) |
| [`move`](https://cran.r-project.org/package=move)                           | R        | [`compatibility/move-r/`](compatibility/compat/move-r/) (script, run before each release) |
| [`move2`](https://cran.r-project.org/package=move2)                         | R        | [`compatibility/move2-r/`](compatibility/compat/move2-r/) (script, run before each release) |

Adding a check for another client: see [`compatibility/`](compatibility/).

⚠ Contributors: before refactoring `EventService` / `MirrorService` /
`DirectReadController`, read [`BUG_COMPATIBILITY.md`](BUG_COMPATIBILITY.md).
The "weird-looking" code is mostly load-bearing — it replicates Movebank's
own quirks so existing clients (especially the R `move` package) work
unchanged.

---

## Quick start

```bash
# Build the runnable distribution
./gradlew installDist
export PATH="$PWD/build/install/movebank-mirror-api/bin:$PATH"

# Serve a mirror on localhost:8080
movebank-mirror-api -d /var/lib/movebank-mirror

# Point a client at it
curl 'http://localhost:8080/movebank/service/direct-read?entity_type=tag_type'
```

Or with `movebank-api-client`:

```java
MovebankApiClient client = new MovebankApiClient(
    "http://localhost:8080/movebank",
    "ignored", "ignored",     // no auth
    html -> true);
```

---

## CLI reference

```
movebank-mirror-api [OPTIONS]
```

| Flag                 | Env var                       | Default       | Notes                                                           |
|----------------------|-------------------------------|---------------|-----------------------------------------------------------------|
| `-d, --mirror-dir`   | `MOVEBANK_MIRROR_DIR`         | (required)    | Path to a `movebank-mirror` directory.                          |
| `-p, --port`         | `MOVEBANK_MIRROR_API_PORT`    | `8080`        |                                                                 |
| `-b, --bind`         | `MOVEBANK_MIRROR_API_BIND`    | `127.0.0.1`   | Use `0.0.0.0` to expose externally — see security note below.   |
| `-v` / `-vv` / `-q`  |                               | info          | `-v` debug, `-vv` trace, `-q` warn.                             |
| `-h, --help` / `-V`  |                               |               |                                                                 |

Spring Boot's standard property/arg/env-var precedence applies on top: any
`--server.port=...`, `MOVEBANK_MIRROR_BASE-DIR=...`, etc. that you pass also
takes effect.

### Security note

There is no authentication. Default bind is `127.0.0.1` deliberately. If you
expose with `-b 0.0.0.0`, put a reverse proxy with auth in front of it.

### Exit codes

| Code | Meaning              |
|------|----------------------|
| `0`  | Clean shutdown       |
| `2`  | Usage / bad args     |
| `5`  | I/O error (missing/unreadable mirror dir) |

---

## Supported entity types

| `entity_type`     | Required parameters                                     | Source                                                  |
|-------------------|---------------------------------------------------------|---------------------------------------------------------|
| `study`           | none (optional `id`, `name`)                            | All `*.json` files in mirror root                       |
| `tag`             | `study_id`                                              | `tags` array in study JSON                              |
| `individual`      | `study_id`                                              | `individuals` array in study JSON                       |
| `deployment`      | `study_id` (optional `tag_id`, `individual_id`)         | `deployments` array in study JSON                       |
| `sensor`          | `study_id` (or `tag_study_id`)                          | `sensors` array in study JSON                           |
| `study_attribute` | `study_id`, `sensor_type_id`                            | `attributesBySensorTypeIDs` in study JSON               |
| `event`           | `study_id`, `sensor_sensor_type_id` (+ filters)         | CSV files under `<study>/<tag>/`                        |
| `tag_type`        | none                                                    | Hardcoded (9 sensor types, as live `/tag_type`)         |

`event` accepts `tag_id`, `individual_id`, or `deployment_id` to scope output.
With `individual_id` or `deployment_id` the server consults the deployment
windows in the study JSON and sends only the events of those deployments. A
window runs from `deploy_on_timestamp` to `deploy_off_timestamp`; a blank bound
is open. An event belongs to the first deployment of its tag whose window
contains it.

Rows come in live Movebank's order: sensor type, then individual, then
timestamp, merged across all tags an individual carried. Without a filter,
events outside every deployment follow at the end, tag by tag. Details and
measurements: [`BUG_COMPATIBILITY.md`](BUG_COMPATIBILITY.md).

---

## Known gaps (the 20%)

Tracked in [`TODO.md`](TODO.md). Highlights:

- **`sort` / `limit` parameters** — currently ignored. (Live Movebank answers `limit=` on `/event` with HTTP 500.)
- **`/event` with `attributes=all`** lacks four columns live derives from its own tables: `study_id`, `individual_local_identifier`, `tag_local_identifier`, `individual_taxon_canonical_name`. Neither `move` nor `move2` needs them.
- **`/event` column order** differs from live. Clients that read columns by name (both R packages) are unaffected; positional clients would break.
- **Authentication** — credentials are accepted without a check; do not expose without a proxy.
- **In-memory cache** — every request re-reads study JSON from disk.

Most clients work fine with these gaps; they hit the 80% the API is used for.

---

## On-disk format

Whatever `movebank-mirror` writes:

```
<mirror-dir>/
├── 000002911040.json              study metadata
├── 000002911040-license.json      (ignored by this server)
└── 000002911040/<tagId>/
    ├── state_<sensorTypeId>.json  (ignored by this server)
    └── <sensorTypeId>_<ts>.csv    event data
    └── <sensorTypeId>_update_<ts>.csv
```

Reading is done by `movebank-mirror`'s `reader` package (`MirrorReader`,
`MovebankEventCsv`), so this server stays in lockstep with the writer and
shares its event order and deployment assignment with
[movebank-mirror-mcp](https://github.com/mcb77/movebank-mirror-mcp). This server adds the
HTTP layer and the quirks of Movebank's entity responses.

---

## Building

Requires JDK 21.

```bash
./gradlew build                # compile + tests (incl. HTTP integration test)
./gradlew installDist          # build/install/movebank-mirror-api/bin/movebank-mirror-api
./gradlew bootDistTar bootDistZip   # release archives in build/distributions/
./gradlew bootRun --args='--movebank.mirror.base-dir=/var/lib/movebank-mirror'
```

### Local development against unreleased dependencies

If you have `movebank-api-client` and/or `movebank-mirror` checked out as
sibling directories, build against them via Gradle's `includeBuild`: pass
`-PlocalMovebankApiClient` / `-PlocalMovebankMirror`, or uncomment those lines
in `gradle.properties` (and comment them out again for a release/CI build).

The current code needs movebank-mirror 0.0.4 (the `reader` package). Until
that is on Maven Central, build with `-PlocalMovebankMirror`.

## Related projects

Together these form a toolchain for working with Movebank data offline:

- [movebank-api-client](https://github.com/mcb77/movebank-api-client): Java client for the Movebank REST API
- [movebank-mirror](https://github.com/mcb77/movebank-mirror): mirrors Movebank studies to plain files on your disk; resumable, incremental
- **movebank-mirror-api** (this repository): serves the Movebank REST API from a local mirror, a drop-in for `move`/`move2` and the Java client
- [movebank-rocker](https://github.com/mcb77/movebank-rocker): pinned R environment (rocker/geospatial + move, move2, moveVis, sf)

Background: [Keep a Movebank study on your own computer](https://www.schaeuffelhut-berger.de/en/blog/keep-a-movebank-study-on-your-own-computer/?ref=github)

---

## License

LGPL-2.1, matching the rest of the family. See [LICENSE](LICENSE).
