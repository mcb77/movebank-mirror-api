# Demo — your R workflow, running offline against the mirror

The visual, human-facing companion to the `verify.R` scripts in
`../compat/move-r/` and `../compat/move2-r/`. Those prove compatibility for a
release gate; this shows what it looks like in practice: the same `move` /
`move2` code you'd run against the live Movebank API, reading from a local
mirror instead — unchanged.

Fixture study: **2911040** ("Galapagos Albatrosses"). Both panes plot the 28
tracked individuals.

| Script          | Client   | What you see                                             |
|-----------------|----------|----------------------------------------------------------|
| `move2_pane.R`  | `move2`  | `ggplot`/`sf` map — multicolour tracks over the E Pacific |
| `move_pane.R`   | `move`   | base-graphics `plot` of the same `MoveStack`             |

`move2_pane.R` has *nothing* mirror-specific in it — not even a comment: it is
deliberately a plain script, as you'd write it against the live API. The
redirect comes from the container's baked `.Rprofile`, which sets
`options(move2_movebank_api_url = ...)`. (The release-gate version of the same
check is `../compat/move2-r/verify.R`.) `move_pane.R` sources `url_shim.R` from the compat
harness (mounted at `/work/move-r/`) because `move` v1 hardcodes the live URL
(see that file for why). Its `api` line reads the same `MOVEBANK_MIRROR_API_URL`
the container uses, so it works under either networking mode with no edits
(`http://localhost:8080/movebank` with host networking,
`http://host.docker.internal:8080/movebank` on Docker Desktop); the mirror
ignores the login's credentials.

## Prerequisites

1. A mirror containing the fixture study:
   ```bash
   movebank-mirror -d /tmp/movebank-mirror metadata --study 2911040
   movebank-mirror -d /tmp/movebank-mirror eventdata --once
   ```
2. The replay server running against it:
   ```bash
   movebank-mirror-api -d /tmp/movebank-mirror &
   ```
3. The pinned R environment — [`mcb77/movebank-rocker`](https://github.com/mcb77/movebank-rocker)
   — with **two mounts** (run the commands below from the repo root):
   - `compatibility/compat` → `/work` — the shim + verify scripts
     (`/work/move-r/…`, `/work/move2-r/…`).
   - `compatibility/demo` → `/home/rstudio/demo` — these panes, inside RStudio's
     home so they appear in the Files pane at `~/demo`.

## Run

**Linux (host networking — server stays loopback-only):**

```bash
docker run -d --network=host \
  -e DISABLE_AUTH=true \
  -e MOVEBANK_MIRROR_API_URL=http://localhost:8080/movebank \
  -v "$PWD/compatibility/compat:/work:ro" \
  -v "$PWD/compatibility/demo:/home/rstudio/demo:ro" \
  mcb77/movebank-rocker:v0.1.0
```

**macOS / Windows (Docker Desktop — bridge; `host.docker.internal` reaches the host):**

```bash
docker run -d -p 8787:8787 --add-host=host.docker.internal:host-gateway \
  -e DISABLE_AUTH=true \
  -v "$PWD/compatibility/compat:/work:ro" \
  -v "$PWD/compatibility/demo:/home/rstudio/demo:ro" \
  mcb77/movebank-rocker:v0.1.0
```

> On Linux the bridge variant additionally needs the server bound to all
> interfaces (`movebank-mirror-api -d … -b 0.0.0.0`), which exposes the
> unauthenticated server on your LAN — prefer host networking there.

`podman` works too (rootless): use `--network=host`, add the `docker.io/`
registry prefix on the image, and the same `-e MOVEBANK_MIRROR_API_URL=…`.

Then open <http://localhost:8787> (RStudio Server), and in the editor run
`~/demo/move2_pane.R`, then `~/demo/move_pane.R` (they're in the Files pane). The
map renders in the Plots pane. No credentials, no network.
