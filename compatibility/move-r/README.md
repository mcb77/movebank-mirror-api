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
  rocker/geospatial \
  bash -c 'Rscript -e "install.packages(\"move\", quiet=TRUE)" && Rscript /work/verify.R'
```

(`rocker/geospatial` is a ~4 GB image with the full r-spatial stack
preinstalled — `move` itself isn't bundled but installs cleanly on top.)

## URL override caveat

The `move` package historically hardcodes the live Movebank URL in some
helpers. The script tries two override paths in order: an `options()` hook
(`move_movebank_api_url`) and, if present, a slot on the login object. If
your installed `move` version routes through neither, the script will hit
the live API and the first check will surface a credential error.

Workarounds when that happens:

1. Patch the script's `override_url()` helper to set whatever your `move`
   version expects (file an issue with the call shape so we can fold it in).
2. As a last resort, run the verification through `httptest`/`vcr` recording
   to intercept calls — but that's heavier than this script wants to be.

## Snapshot fixtures (optional)

`expected/` is reserved for committed snapshots of expected output (CSV
tables, summary stats) you want to diff against future runs to catch silent
regressions. Empty by default.
