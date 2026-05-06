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
   orders, accepts specific value formats (`NA` for missing, `true`/`false`
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
must pass [`compatibility/move-r/verify.R`](compatibility/move-r/) before
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
names**. Columns not in the source data appear as `NA`.

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

### Missing values are `NA`, not empty string

**Live behaviour.** Movebank emits `NA` (the literal two characters) for
missing values in numeric columns.

**Why it matters.** R's `read.csv` defaults `na.strings = "NA"`. Empty
strings become parse errors when `colClasses` declares the column numeric:
`scan() expected 'a real', got ''`. `move` always asks for
`location_lat` / `location_long` as numeric.

**Where we encode it.** `EventService.java` — `streamCsvFiles` projection
block emits `"NA"` when the source row is missing the value, and `safe(...)`
returns `"NA"` for null/empty values from deployment metadata.

---

### Don't quote values that don't need quoting

**Live behaviour.** Movebank's CSV output quotes only values that contain
the field separator, the quote character, or a newline. Plain values appear
unquoted.

**Why it matters.** OpenCSV's default `applyQuotesToAll=true` quotes every
value, so `NA` comes out as the four characters `"NA"` (i.e. the cell value
literally contains the `"` characters after CSV unquoting). R's
`na.strings = "NA"` matches the unquoted literal `NA`, not the four-character
string `"NA"`. With universal quoting, every numeric column whose source
is missing crashes `read.csv`.

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
   `deployment_id` from that window's record. (`"NA"` if no window
   covers the timestamp — happens for events outside any recorded
   deployment.)
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

### `tag_type` is hard-coded, not derived

**Live behaviour.** Movebank's tag-type catalogue is a small, fixed table.
Clients treat it as a stable lookup.

**Where we encode it.** `MirrorService.TAG_TYPES`. Five entries, hard-coded.

**Don't make it dynamic.** A "scan the mirror to discover sensor types"
implementation would behave differently when the mirror is empty (returns
no rows → `move` errors with "no records found"). Keep the catalogue
static, even if it occasionally lags Movebank.

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
