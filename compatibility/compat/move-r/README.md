# Verification against the `move` R package

[`move`](https://cran.r-project.org/package=move) is the de-facto Movebank
client in movement-ecology research. If `move` is happy talking to
`movebank-mirror-api`, the server is faithfully reproducing the parts of the
live API that matter to working researchers — which is exactly the bar this
project promises to clear.

## Prerequisites

- R 4.x with the `move` package:
  ```r
  install.packages("move")
  ```
- A populated mirror directory containing the fixture study, e.g.:
  ```bash
  movebank-mirror -d /var/lib/movebank-mirror metadata --study 2911040
  movebank-mirror -d /var/lib/movebank-mirror eventdata --once
  ```
- `movebank-mirror-api` running against that mirror.

## Run

Start the replay server in one shell:

```bash
movebank-mirror-api -d /var/lib/movebank-mirror -p 8080 -b 127.0.0.1
```

Run the script in another:

```bash
Rscript verify.R
```

Optional env vars:

| Variable                    | Default                              | Notes                                          |
|-----------------------------|--------------------------------------|------------------------------------------------|
| `MOVEBANK_MIRROR_API_URL`   | `http://localhost:8080/movebank`     | replay-server base URL                         |
| `STUDY_ID`                  | `2911040`                            | fixture study id (must be present in mirror)   |

Exit code: `0` if every check passes, `1` otherwise — suitable for release
gating.

## What it checks

| # | Call                                  | Expected                                                |
|---|---------------------------------------|---------------------------------------------------------|
| 1 | `getMovebank("study", login)`         | non-empty data frame of accessible studies              |
| 2 | `getMovebankAnimals(<study>, login)`  | non-empty data frame of individuals                     |
| 3 | `getMovebankSensors(<study>, login)`  | non-empty data frame of sensor records                  |
| 4 | `getMovebankData(<study>, login)`     | a non-empty `Move` / `MoveStack` object                 |

These four cover the ~80% of `move` usage we see in real
movement-ecology workflows. If a critical use case isn't covered, add it to
`verify.R` and update the table above.

## Current status

Last verified against `move` 4.2.7 (rocker/geospatial container) and study
2911040: **4 / 4 checks pass**. `getMovebankData` returns a valid
`MoveStack` containing every event from every location-capable sensor in
the study, joined with individual / tag / deployment metadata — i.e. a
drop-in replacement for the live API call.

Reaching 4/4 required these server-side fixes (closed in v0.0.1):

- Aggregate across all sensor types when `sensor_sensor_type_id` is absent.
- Accept the alternate parameter name `sensor_type_id`, with empty value
  treated as absent. Both `sensor_type_id` and `individual_id` accept CSV
  lists of ids.
- Honour the `attributes=` projection: response columns are exactly those
  requested, in that order; missing-from-source values become `NA` so R's
  `read.csv(colClasses=…)` parses cleanly.
- Enrich event rows with `tag_id` (from directory layout), `individual_id`
  / `deployment_id` (from the deployment window covering each row's
  timestamp), and a synthetic `event_id`.
- `tag_type` returns `id`, `name`, `external_id`, `is_location_sensor` —
  the shape `move::getMovebankData` expects for its sensor-type lookup.
- Use `applyQuotesToAll=false` on the CSV writer so plain values aren't
  wrapped in quotes (matches the live API output and unbreaks
  `read.csv` for `NA` values).

## Running outside Docker

If you have R 4.2+ with the spatial stack already configured (GDAL, PROJ,
GEOS, UDUNITS), `Rscript verify.R` works directly. On a fresh Linux box
the easiest path is the same container we use:

```bash
docker run --rm --network=host \
  -v $PWD:/work:ro \
  -e MOVEBANK_MIRROR_API_URL=http://127.0.0.1:8080/movebank \
  -e STUDY_ID=2911040 \
  -w /work \
  rocker/geospatial \
  bash -c 'Rscript -e "install.packages(\"move\", quiet=TRUE)" && Rscript verify.R'
```

(`verify.R` `source()`s `url_shim.R` from its own directory, so the
container's working directory must be the script's directory — that's
what `-w /work` ensures when you run from this directory on the host.)

(`rocker/geospatial` is a ~4 GB image with the full r-spatial stack
preinstalled — `move` itself isn't bundled but installs cleanly on top.)

## URL override (`url_shim.R`)

The `move` package hardcodes the live Movebank URL inside `getMovebank`'s
function body — there's no public override. `url_shim.R` works around
this by monkey-patching `httr::GET` to rewrite the hardcoded base URL on
the way out, in both `httr`'s own namespace and `move`'s imports
environment (the two paths `GET` reaches `move` through).

The shim is sourced from `verify.R` automatically. If you want to use it
from your own script, the pattern is:

```r
source("url_shim.R")
login <- movebankLogin(username = "ignored", password = "ignored")
override_url(login, "http://localhost:8080/movebank")
# now any move::* call routes to the replay server
```

Set `VERIFY_TRACE=1` in the environment to log every rewritten URL to
stderr — useful when chasing a regression.

The patch depends on `move`'s internal name `GET`. If a future `move`
version renames or restructures its HTTP calls and the shim can no
longer locate `GET` in either namespace, it emits a warning and the
first check will fail at credential lookup. File an issue with the
installed `move` version and the call shape if that happens.

## Snapshot fixtures (optional)

`expected/` is reserved for committed snapshots of expected output (CSV
tables, summary stats) you want to diff against future runs to catch silent
regressions. Empty by default.
