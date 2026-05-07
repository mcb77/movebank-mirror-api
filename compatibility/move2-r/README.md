# Verification against the `move2` R package

[`move2`](https://cran.r-project.org/package=move2) (v0.5.0+) is the modern
successor to `move`, by the same MPI-AB authors. It uses `sf`, `dplyr`, and
`vroom` instead of `sp`/`raster`/`httr`, and is the canonical client for
researchers writing new tracking-data analysis code today. The original
`move` package is still widely deployed; `compatibility/move-r/` covers
that. This directory covers the new client.

## Prerequisites

- R 4.1+ with the `move2` package:
  ```r
  install.packages("move2")
  ```
- A populated mirror directory containing the fixture study, e.g.:
  ```bash
  movebank-mirror -d /var/lib/movebank-mirror metadata --study 2911040
  movebank-mirror -d /var/lib/movebank-mirror eventdata --once
  ```
- `movebank-mirror-api` running against that mirror.

## Run

Start the replay server:

```bash
movebank-mirror-api -d /var/lib/movebank-mirror -p 8080 -b 127.0.0.1
```

Run the script:

```bash
Rscript verify.R
```

Optional env vars:

| Variable                    | Default                              | Notes                                          |
|-----------------------------|--------------------------------------|------------------------------------------------|
| `MOVEBANK_MIRROR_API_URL`   | `http://localhost:8080/movebank`     | replay-server base URL (just the prefix; the script appends `/service/direct-read`) |
| `STUDY_ID`                  | `2911040`                            | fixture study id (must be present in mirror)   |

Exit code: `0` on full success, `1` on any failure.

## What it checks

| # | Call                                                            | Expected                            |
|---|-----------------------------------------------------------------|-------------------------------------|
| 1 | `movebank_download_study_info(handle)`                          | non-empty tibble of studies         |
| 2 | `movebank_retrieve("individual", study_id=…, handle)`           | non-empty tibble of individuals     |
| 3 | `movebank_retrieve("sensor", tag_study_id=…, handle)`           | non-empty tibble of sensor records  |
| 4 | `movebank_download_study(study_id=…, handle, attributes="all")` | non-empty `move2` object            |

## URL override mechanism

Unlike `move` v1 (which hard-coded `https://www.movebank.org/movebank` in
function bodies), `move2` reads the API base URL from
`getOption("move2_movebank_api_url")`. The script sets that option once at
startup; no monkey-patching of `httr::GET` required.

## Current status

Last verified against `move2` 0.5.0 (rocker/geospatial container) and study
2911040: **4 / 4 checks pass**. `movebank_download_study` returns a valid
`move2`/`sf` object containing every event from every location-capable
sensor in the study, joined with individual / tag / deployment metadata.

Reaching 4/4 added two compatibility behaviours beyond what `move` v1
required (full list in [`BUG_COMPATIBILITY.md`](../../BUG_COMPATIBILITY.md)):

- **`attributes=all` is treated as no projection.** `move2` defaults to
  `attributes="all"` for full study downloads; the live API treats that
  as "every column from the source." We must do the same instead of
  projecting to a literal one-column "all" list.

- **`/deployment` omits `tag_id` and `individual_id` by default.** Live
  Movebank's `/deployment` endpoint returns these *foreign-key* columns
  only when explicitly requested via `attributes=…`. Our mirror carries
  them on disk (it does two API roundtrips and merges), but emitting them
  by default breaks `move2`'s `movebank_download_deployment` join
  pipeline. They're now dropped from the response unless the caller asks
  for them by name.

`move` v1 doesn't trigger either gap because it takes a different code
path (always passes explicit `attributes=…`).

## Running outside Docker

Same recipe as `compatibility/move-r/`:

```bash
docker run --rm --network=host \
  -v $PWD:/work:ro \
  -e MOVEBANK_MIRROR_API_URL=http://127.0.0.1:8080/movebank \
  -e STUDY_ID=2911040 \
  rocker/geospatial \
  bash -c 'Rscript -e "install.packages(\"move2\", quiet=TRUE)" && Rscript /work/verify.R'
```
