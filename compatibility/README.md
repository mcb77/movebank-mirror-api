# Compatibility tests

This directory holds verification scripts that exercise `movebank-mirror-api`
through the canonical Movebank clients used by the ecology community. They
are the proof that the replay server is a faithful drop-in for the live
Movebank API — not just for our own `movebank-api-client`.

Each subdirectory targets one client and runs a small, opinionated set of
calls that real users actually make. Scripts exit with code 0 on success
and non-zero on any failure, so they can be wired into release gates.

| Client                                                | Language | Directory                  | Status |
|-------------------------------------------------------|----------|----------------------------|--------|
| [`move`](https://cran.r-project.org/package=move)     | R        | [`move-r/`](move-r/)       | scaffolded |

## Adding a check for a new client

1. Create a sibling directory named after the client (e.g. `python-movebank/`).
2. Add a `verify.<ext>` script that:
   - reads the server URL from `MOVEBANK_MIRROR_API_URL` (default
     `http://localhost:8080/movebank`)
   - reads a fixture study id from `STUDY_ID` (default `2911040`)
   - prints a `[PASS]` / `[FAIL: ...]` line per check
   - exits with status 0 if every check passes, 1 otherwise
3. Add a `README.md` next to it describing prerequisites and the call set.
4. Add a row to the table above.

## Why these aren't part of the JUnit suite

The Java integration test (`DirectReadIntegrationTest`) verifies our own
controller wiring. These scripts verify the server's *external* contract,
using clients that are out of our control. They depend on heavyweight
runtimes (R, Python, …) we don't want to require for every `./gradlew build`.
Treat them as release-gate checks rather than CI-on-every-push.
