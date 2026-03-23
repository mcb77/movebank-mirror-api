# TODO

## Known gaps (the 20%)

- **`attributes` projection** — the `attributes` request parameter is currently ignored; all columns are always returned. Should filter the CSV output to the requested columns, and reorder to match the requested order. This matters when callers build requests via `StudyBrowser.getRequestBuilderEvent()` which explicitly lists attributes.

- **`sort` parameter** — currently ignored. Low priority for batch/offline use.

- **`limit` parameter** — currently ignored.

- **Authentication** — credentials passed in HTTP headers (`user`, `password`) are accepted without validation. Add configurable credential checking if the server is exposed beyond localhost.

## Nice to have

- **In-memory cache for study JSONs** — currently each request re-reads the JSON file from disk. A simple `Map<String, StudyJson>` cache with file-modified-time invalidation would be faster for repeated queries against large mirrors.

- **`deployment` response: merge of default + id/individual_id/tag_id attributes** — the live API requires two requests to get the full deployment record (see `Mirror.getAllDeploymentRefDataForStudy2()`). The server currently returns whatever is in the study JSON, which was already merged by the mirror. No action needed unless re-mirroring changes this.

- **Study JSON hot-reload** — new study JSON files dropped into the mirror directory are picked up automatically on the next request (no restart needed), but a running server currently re-scans the whole directory on every `entity_type=study` list request. Could scope to a single file for `id`-based lookups (already done) and add a directory-watch for caching.

- **Structured error responses** — currently returns a bare HTTP 400 for unknown `entity_type`. Could return a more informative body.

- **Tests** — add an integration test that starts the server against the sample mirror in `/tmp/mullet-mirror` and exercises each entity type via `MulletRestClient`.
