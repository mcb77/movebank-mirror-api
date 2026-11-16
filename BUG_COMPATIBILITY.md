# Bug-to-bug compatibility notes

`movebank-mirror-api` is not "a clean re-implementation of the Movebank API."
It is a **bug-to-bug-compatible** replay of the live API surface as observed
by the clients people actually use — primarily the [`move`](https://cran.r-project.org/package=move)
R package, secondarily [`movebank-api-client`](https://github.com/mcb77/movebank-api-client)
(Java) and anything that speaks Movebank's CSV `direct-read` protocol.

The promise to a downstream user is: *if your code works against
`https://www.movebank.org/movebank`, it works against `http://localhost:8080/movebank`
without modification.* That promise is what makes the project useful — and
keeping it requires us to replicate Movebank's quirks rather than fix them.

This file documents the quirks we deliberately replicate, the reasons each
exists, and what to **not** clean up during refactors.

---

## Why bug compatibility, not clean implementation?

Three reasons:

1. **The clients depend on undocumented behaviour.** The R `move` package
   accesses response columns by name and position. It assumes specific column
   orders, accepts specific value formats (unquoted empty for missing, `true`/`false`
   for booleans), and looks up some columns that the official API docs don't
   mention as guaranteed (`tag_type.is_location_sensor`). Cleaning up our
   response to match the docs would break `move`.

2. **The clients aren't going to change to accommodate us.** `move` is on
   CRAN, has thousands of users, and a release cycle measured in years. Even
   if they accepted a patch tomorrow, the installed base would lag for
   half a decade. We can change the server overnight.

3. **The official API docs are descriptive, not normative.** Movebank's
   actual response format is what it is. The docs lag, the implementation
   leads. Treating Movebank's emitted bytes as the spec — rather than the
   docs — is the only way to be a faithful drop-in replacement.

So: when we find a quirk, we replicate it. When something looks "weird,"
suspect it's intentional. Add a code comment and a `move-r` verification
test, don't refactor it away.

---

## Verification gate

Every change to `EventService` / `MirrorService` / `DirectReadController`
must pass [`compatibility/move-r/verify.R`](compatibility/compat/move-r/) before
release. That script is the regression suite for bug-compat. It currently
covers `getMovebank("study")`, `getMovebankAnimals`, `getMovebankSensors`,
and `getMovebankData` against a populated mirror.

If you find yourself thinking "this code is silly, let me clean it up," run
`verify.R` first. If it still passes after your cleanup, fine. If it
breaks, the silly code was load-bearing — restore and document.

---

## Catalogue of replicated quirks

Each entry describes a behaviour the live Movebank API exhibits, why we copy
it, where the code lives, and (where applicable) which client demands it.

### `tag_type` response shape

**Live behaviour.** The `tag_type` direct-read endpoint returns the columns
`id`, `name`, `external_id`, `is_location_sensor` (and possibly more) — in
that order, with these exact names.

**Why it matters.** `move::getMovebankData` does:

```r
sensorTypes <- getMovebank("tag_type", login)
rownames(sensorTypes) <- sensorTypes$id
locSen <- sensorTypes[as.logical(sensorTypes$is_location_sensor), "id"]
...
trackDF <- merge.data.frame(trackDF, sensorTypes[, c("id", "name")],
                            by.x = "sensor_type_id", by.y = "id")
```

If `is_location_sensor` is missing → `locSen` becomes empty → no events
queried. If `name` is missing → the `merge.data.frame` call throws
"undefined columns selected".

**Where we encode it.** [`MirrorService.java`](src/main/java/de/firetail/compat/movebank/mirror/api/MirrorService.java)
— `TAG_TYPES` constant + `TAG_TYPE_HEADER` constant. Don't reorder, don't
rename, don't drop columns.

**`is_location_sensor` is a string.** Values are the literal strings
`"true"` / `"false"`, not boolean JSON values, because `move` calls
`as.logical(<that string>)`. R's `as.logical("true")` is `TRUE`,
`as.logical("yes")` is `NA`. Stick with `"true"` / `"false"`.

---

### Event response: column projection follows the `attributes=` parameter

**Live behaviour.** When the caller sends `attributes=col1,col2,col3`, the
response columns appear in **exactly that order**, with **exactly those
names**. Columns not in the source data appear empty.

**Why it matters.** `move` uses `colClasses` indexed by name in
`read.csv` and downstream code does positional `df[, k]` access. Any
reordering breaks both.

**Where we encode it.** [`EventService.java`](src/main/java/de/firetail/compat/movebank/mirror/api/EventService.java)
— `streamCsvFiles`'s projection block + `buildProjection`. The output
header is built directly from `requestedAttrs.toArray(new String[0])`
when projection is active.

**Don't "optimise" projection.** A clean rewrite that streams source
columns and lets the client filter would not be acceptable. The order
matters; the name set matters.

---

### Missing values are empty, not `NA`

**Live behaviour.** Movebank sends a missing value as an empty, unquoted
field. Measured 2026-09-29 against study 2911040 with the exact `/event`
requests of `move::getMovebankData` (16,414 rows) and
`move2::movebank_download_study` (`attributes=all`, 114,929 rows): no `NA`
anywhere, empty fields throughout (e.g. `location_lat` of GPS rows without a
fix, every GPS column of acceleration rows). An earlier version of this file
said live emits `NA`; that was wrong, and so was our output.

**Why it matters.** A raw `diff` against live only comes out empty with the
same encoding. R copes with both: `read.csv` reads an *unquoted* empty field
in a numeric column as `NA`. What breaks it is a *quoted* empty field:
`scan() expected 'a real', got '""'` (checked with R, 2026-09-29) — see the
next entry.

**Where we encode it.** `EventService.java` — the `streamCsvFiles`
projection block emits `""` when the source row is missing the value (a
source value of `NA`, from older mirrors, is treated as missing), and
`safe(...)` / `synthetic(...)` / `augmentRow(...)` emit `""` when no
deployment covers the row.

---

### CRLF line endings

**Live behaviour.** Every direct-read response ends its lines with `\r\n`
(measured 2026-09-29, all entity types we request).

**Where we encode it.** `DirectReadController` builds the `CSVWriter` with
`"\r\n"` as line end (OpenCSV's default is `\n`).

---

### Don't quote values that don't need quoting

**Live behaviour.** Movebank's CSV output leaves numbers, timestamps,
booleans and empty fields unquoted. It *does* quote values of
`string`-typed attributes (`data_type` in `study_attribute`), e.g.
`eobs_status` → `"A"`, and the text join columns of `attributes=all`
(`individual_local_identifier`, …) — measured 2026-09-29. We don't
replicate that part yet (see "Quoting of string-typed values" below).

**Why it matters.** OpenCSV's default `applyQuotesToAll=true` quotes every
value, so a missing value comes out as `""`, which `read.csv` rejects in a
numeric column (`scan() expected 'a real', got '""'`). With universal
quoting, every numeric column whose source is missing crashes `read.csv`.

**Where we encode it.** Every `csv.writeNext(...)` call in `EventService`
and `MirrorService` passes `false` as the `applyQuotesToAll` argument.
Don't drop those `false` arguments during cleanup.

---

### Both `sensor_type_id` and `sensor_sensor_type_id` accept on the event endpoint

**Live behaviour.** Different Movebank API surfaces use different parameter
names. The `/event` endpoint historically used `sensor_sensor_type_id`
(the relational join `sensor.sensor_type_id`), but the simpler
`sensor_type_id` is also accepted, and `move::getMovebankData` sends the
latter.

**Where we encode it.**

```java
// EventService.writeEvents
String sensorTypeId = firstNonEmpty(
        params.get("sensor_sensor_type_id"),
        params.get("sensor_type_id"));
```

Both must be accepted indefinitely. Removing either alias would break a
class of clients.

---

### CSV-list query parameters

**Live behaviour.** The `/event` endpoint accepts comma-separated lists for
`individual_id`, `deployment_id`, and `sensor_type_id`. A request with
`individual_id=2911080,2911061,2911067` selects rows for any of those
individuals.

**Why it matters.** `move::getMovebankData` issues exactly this — it batches
up to 200 individual ids per request.

**Where we encode it.** `MirrorService.csvToSet` is shared across the
deployment lookup and the sensor-type list. The single-value string
`equals(...)` pattern was correct for the pre-`move` test fixtures and is
specifically wrong for real clients — that's the bug we replicate.

---

### Empty parameter value means absent

**Live behaviour.** A query string with `&sensor_type_id=` (empty value
after `=`) is treated identically to omitting the parameter. `move` sends
this when its `locSen` vector turns up empty.

**Where we encode it.** `EventService.writeEvents` runs every parameter
through `nullIfEmpty(...)` / `firstNonEmpty(...)` before use. Empty strings
are coerced to `null`, then the absent-parameter branch fires.

---

### Event response is enriched with join columns

**Live behaviour.** When the caller requests `attributes=…,tag_id,
individual_id,deployment_id,event_id,…`, Movebank's `/event` response
includes every requested column populated. `event_id` is the events
table's primary key. The other three are joined in from the deployment
table covering each row's timestamp, and from the tag the row belongs to.

**Where the values come from in our setup.** The on-disk event CSV files
written by `movebank-mirror` carry **only `event_id`** of these four
columns. The other three are deliberately *not* downloaded:

- `tag_id` is implicit in the directory layout — every event under
  `<study>/<tag>/...csv` already has its tag id encoded in its path.
- `individual_id` and `deployment_id` are reconstructable from the
  study's deployment metadata: for each row's `(tag, timestamp)`, look
  up the deployment whose window contains that timestamp; that row gives
  both ids.

Carrying any of those three in every event row would only duplicate data
that already lives in the per-study JSON, and — more importantly — would
introduce drift if a deployment is later edited in Movebank without a
full re-download of historical events. `event_id` is irreducible: it has
no alternative source, so the mirror requests it explicitly.

**The replay server fills in the four columns at response time:**

1. Looks up the per-tag deployment windows for the study (one read of
   the per-study JSON).
2. For each event row, finds the deployment window that contains the
   row's `timestamp` on the row's tag. Emits `individual_id` and
   `deployment_id` from that window's record (empty if no window
   covers the timestamp). The window is `deploy_on_timestamp` …
   `deploy_off_timestamp`, a blank bound being open — **not**
   `timestamp_start` / `timestamp_end`, which are the first and last
   located fix. Live assigns the deployment to events outside those too
   (fixless GPS rows, acceleration bursts): measured 2026-09-29 on study
   2911040, the deploy_on/off rule reproduces live's `deployment_id` on
   all 114,929 events, the old `timestamp_start`/`_end` fallback left 99
   of them undeployed (and `move2` dropped them with a warning). Every
   tag in that study has one deployment, so tags with several
   deployments are untested.
3. Uses the tag directory name as `tag_id`.
4. For `event_id`: prefers the value the CSV carries (mirrors made
   with `movebank-mirror` ≥ 0.0.2 carry it). Falls back to a
   monotonic per-response counter for older mirrors that never asked
   for it. The counter is *not* a stable cross-session identifier; if
   you need stable ids, re-mirror with the current downloader.

**Order of preference for `event_id`.** When the CSV file carries the
column, that value wins. The synthesised counter is the fallback. See
`EventService.streamCsvFiles`'s projection branch.

**Don't drop the synthesis.** Even after `movebank-mirror` ≥ 0.0.2
propagates, old mirrors will exist in the field for years. Keep the
synthesis path.

**Don't add `tag_id` / `individual_id` / `deployment_id` to the
mirror's downloaded columns "for symmetry."** The metadata-driven
reconstruction is the canonical path; making the on-disk CSV the source
of truth for those would create the drift hazard above.

---

### `event_id` is monotonic per response, not globally stable

**Live behaviour (us).** We synthesise `event_id` from a counter scoped to
a single response. The same row in two consecutive requests gets two
different ids.

**Why it matters / why this is OK.** `move` uses `event_id` only for
duplicate-detection within a single result set, not as a stable
cross-session identifier. The "per-response monotonic counter" semantics
satisfy that. If a downstream client relies on `event_id` being stable
across requests, that's a request to layer a stable id on top of file
position — a separate enhancement, not a refactor of the synthesis.

**Where we encode it.** `EventService.EventIdSeq`. The class is
intentionally a per-call instance, not a service-scoped singleton.

---

### `attributes=all` means "no projection"

**Live behaviour.** Movebank's API treats the literal string
`attributes=all` as a sentinel meaning "every column from the source
data, no projection." This is distinct from a column literally named
`all` — there is no such column.

**Why it matters.** `move2::movebank_download_study` issues
`attributes=all` by default for full study downloads. A naive
implementation would split the value on commas, get `["all"]`, and
project the response to a one-column literal-`all` filter — yielding
empty rows.

**Where we encode it.** `EventService.parseAttributes` returns null
(meaning "no projection") when the value is `"all"` (case-insensitive).
`MirrorService.parseAttributes` does the same. The null then routes
through the union-of-source-columns path that `EventService.writeEvents`
synthesises automatically.

---

### `/deployment` omits foreign-key columns by default

**Live behaviour.** When the `/deployment` endpoint is queried *without*
an explicit `attributes=…`, Movebank's response **omits the foreign-key
columns** `tag_id` and `individual_id`. They're returned only when the
caller asks for them by name (e.g.
`attributes=id,tag_id,individual_id`).

You can see the workaround for this baked into our own mirror: see
[`MovebankMirror.getAllDeploymentRefDataForStudy`](https://github.com/mcb77/movebank-mirror/blob/master/lib/src/main/java/de/firetail/compat/movebank/mirror/MovebankMirror.java)
issues two requests per study (one full, one with explicit
`attributes=id,individual_id,tag_id`) and merges them. The on-disk
mirror data therefore *does* carry these columns, but emitting them by
default breaks downstream clients designed against the live API's
shape.

**Why it matters.** `move2::movebank_download_deployment` does:

```r
deployment_data  <- movebank_retrieve("deployment", study_id, rename_columns=TRUE)
deployment_table <- movebank_retrieve("deployment", study_id, attributes=c("id","tag_id","individual_id"), rename_columns=TRUE)

left_join(deployment_table, deployment_data, "deployment_id")          # collision check ↓
left_join(..., individual_data, "individual_id", suffix=c("","_individual"))
```

If `deployment_data` includes `individual_id` (and `tag_id`), the first
join produces `individual_id.x` / `individual_id.y` (default
`.x/.y` suffix). The second join then can't find a column named just
`individual_id` and dies with *"Join columns in `x` must be present in
the data."* Against the live API, `deployment_data` doesn't include
`individual_id` to begin with, so there's no collision.

**Where we encode it.** [`MirrorService.writeDeployments`](src/main/java/de/firetail/compat/movebank/mirror/api/MirrorService.java):
when no `attributes` is requested, the response is filtered to drop the
columns listed in `DEPLOYMENT_LINKAGE_COLUMNS` (currently `tag_id`,
`individual_id`). The primary key column `id` is left in place — the
live API does return that — and after `move2`'s rename pass it becomes
`deployment_id`, which is the join key.

**Don't extend the drop list to `id`.** `id` becomes `deployment_id`
after rename, and dropping it would break the join from the other side
(*"Join columns in `y` must be present in the data."*).

**Don't make this conditional on the user-agent or other request
features.** The behavior is "no `attributes=` ⇒ omit foreign keys."
That's the live API's rule. Replicate it unconditionally.

---

### `/event` row order: sensor type, individual, timestamp

**Live behaviour.** `/event` rows come sorted by `sensor_type_id`
(numeric: 653 before 2365683), then `individual_id`, then `timestamp` —
whatever order the request lists `individual_id`s or sensor types in.
Measured 2026-09-29 on three requests (`move`'s, `move2`'s
`attributes=all`, and a plain single-individual GPS request).

**Where we encode it.** `EventService.writeEvents` sorts the sensor types
with `compareIds` (numeric). `writeSensorType` groups the deployments by
individual (numeric order) and, per individual, runs a k-way merge by
timestamp over every tag that individual carried (`RowStream` per tag, read
file by file in filename order, which is chronological). An event belongs to
the first deployment of its tag whose window contains it, so it is sent at
most once. Without an `individual_id` / `deployment_id` filter, events outside
every deployment follow at the end, tag by tag. No in-memory sort: memory is
one pending row per tag of the current individual, and the response stays
streamed. (Before 0.0.3 whole tag folders were streamed one after the other,
which was out of order for individuals with several tags and for tags moved
between individuals: 196 of 601 studies in a test mirror.)

**Untested against live:** where live puts events outside every deployment
(we put them last), and ties on the timestamp (4 in study 2911040, which came
out in live's order). Covered by unit tests: overlapping tags on one
individual, a tag moved between individuals, open `deploy_on`.

---

### `tag_type` is hard-coded, not derived

**Live behaviour.** Movebank's tag-type catalogue is a small, fixed table.
Clients treat it as a stable lookup. Verified 2026-05-07 by curl against
the live API: 5 columns (alphabetical) — `description`, `external_id`,
`id`, `is_location_sensor`, `name` — and 9 rows.

**Where we encode it.** `MirrorService.TAG_TYPES` + `TAG_TYPE_HEADER`.
The 9 entries match live's IDs exactly. Notable: argos-doppler-shift
is `82798` (not `397` — `397` is `bird-ring`). Don't trust earlier
numeric ids elsewhere; the live `/tag_type` is the source of truth.

**Don't make it dynamic.** A "scan the mirror to discover sensor types"
implementation would behave differently when the mirror is empty (returns
no rows → `move` errors with "no records found"). Keep the catalogue
static, even if it occasionally lags Movebank — refresh the table by
re-running the curl check above when needed.

**Don't reorder the columns.** Live returns columns alphabetically;
clients (e.g. older `move`) sometimes index by position. Stay
alphabetical.

---

## Verified live-API behaviours we deliberately *do not* match yet

These are gaps where our server diverges from live, but no current client
breaks because of it. Listed for visibility; close them if a downstream
need surfaces.

### Live `/event` rejects `limit=` with HTTP 500 — we silently ignore

Live: `?entity_type=event&...&limit=1` returns
`HTTP 500 — limit not supported`. Our server returns the full result with
no limit applied. We're more permissive than the spec, but no client
relies on the rejection (yet). If we ever add real `limit` support,
match live by accepting only the documented values.

### Live `/event` with `attributes=all` includes server-derived join columns

Live response columns for `attributes=all` (verified curl):
```
individual_id, deployment_id, tag_id, study_id, sensor_type_id,
individual_local_identifier, tag_local_identifier,
individual_taxon_canonical_name,
<eobs_*, ground_speed, heading, height_above_ellipsoid,
location_lat, location_long, timestamp, visible>,
event_id
```

The live API joins server-side against the individuals/tags/study tables
to embed the human-readable identifiers (`*_local_identifier`,
`*_taxon_canonical_name`) and the `study_id`. Our response carries
`tag_id`/`individual_id`/`deployment_id`/`event_id`/`sensor_type_id` but
*not* the four derived columns. `move` and `move2` happen not to need
them; other tooling might. Closing this would require adding the joins
in `EventService` (cheap given the deployment lookup is already done).

### Quoting of string-typed values

Live quotes the values of `string`-typed attributes (see "Don't quote
values that don't need quoting"); we quote none. It's the only difference
left between our response and live's for `move`'s exact `/event` request
(measured 2026-09-29). Matching it needs each attribute's `data_type`,
which the mirror doesn't store today (our `study_attribute.data_type` is
empty), so it starts in `movebank-mirror`.

### Column order in `/event` responses

Live order: linkage columns first (individual_id, deployment_id, tag_id,
study_id, sensor_type_id), then derived identifiers, then
sensor measurements alphabetical, then `event_id`. Our order is
union-of-source-headers. Both R clients access by name and don't notice;
position-indexing clients could break.

---

## Things that are NOT bug-compat (regular bugs / TODOs)

The catalogue above is for *intentional* quirks. The following are real
TODOs that should eventually be cleaned up — they're tracked in
[`TODO.md`](TODO.md):

- `sort` parameter is ignored.
- `limit` parameter is ignored.
- No authentication. Don't expose without a reverse proxy.

These aren't load-bearing for `move`, so closing them won't break
compatibility. Closing them just makes us more useful to clients that
*do* use `sort` / `limit` (e.g. ad-hoc curl scripts).

---

## Discovery process for new quirks

When a new client surfaces a compatibility issue, the workflow is:

1. **Reproduce** with `compatibility/move-r/verify.R` (or the equivalent
   for the other client). Add the failing call to `verify.R`.
2. **Trace** what URL the client constructed and what columns / values it
   expects. The `VERIFY_TRACE=1` switch in `verify.R` prints every URL.
3. **Inspect the client source** — the official docs lie (politely). For R,
   `selectMethod("getX", ...)` then `deparse(body(...))` is the standard
   move.
4. **Replicate the quirk**, not the cleaned-up shape. Add the source
   reference (file + line) as a code comment.
5. **Catalogue here**, in a new section. Future maintainers should be
   able to read this file and understand why something that looks weird is
   actually correct.
6. **Re-run `verify.R`** and confirm all checks pass.
