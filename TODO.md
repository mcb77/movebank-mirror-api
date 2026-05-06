# TODO

## Known gaps (the 20%)

Closed in v0.0.1 (verified by [`compatibility/move-r/`](compatibility/move-r/)):

- ~~`event` without `sensor_type_id`~~ — server now enumerates every sensor type in the study's metadata when no sensor type is specified.
- ~~`attributes` projection~~ — response is now projected to the requested columns in the requested order; missing-from-source columns emit `NA`.
- ~~CSV-list parameters~~ — `sensor_type_id`, `individual_id`, and `deployment_id` accept either a single id or a comma-separated list.
- ~~event row enrichment~~ — `tag_id`, `individual_id`, `deployment_id`, and a synthetic `event_id` are injected per row from the directory layout + deployment-window lookup.

Still open:

- **`sort` parameter** — currently ignored. Low priority for batch/offline use.

- **`limit` parameter** — currently ignored.

- **Authentication** — credentials passed in HTTP headers (`user`, `password`) are accepted without validation. Add configurable credential checking if the server is exposed beyond localhost.

## Nice to have

- **In-memory cache for study JSONs** — currently each request re-reads the JSON file from disk. A simple `Map<String, StudyJson>` cache with file-modified-time invalidation would be faster for repeated queries against large mirrors.

- **`deployment` response: merge of default + id/individual_id/tag_id attributes** — the live API requires two requests to get the full deployment record (see `Mirror.getAllDeploymentRefDataForStudy2()`). The server currently returns whatever is in the study JSON, which was already merged by the mirror. No action needed unless re-mirroring changes this.

- **Study JSON hot-reload** — new study JSON files dropped into the mirror directory are picked up automatically on the next request (no restart needed), but a running server currently re-scans the whole directory on every `entity_type=study` list request. Could scope to a single file for `id`-based lookups (already done) and add a directory-watch for caching.

- **Structured error responses** — currently returns a bare HTTP 400 for unknown `entity_type`. Could return a more informative body.

- **Tests** — `DirectReadIntegrationTest` covers the controller wiring and the `tag_type` happy path. Extend it to drive each entity type against a populated tmp mirror via `MovebankApiClient`.
