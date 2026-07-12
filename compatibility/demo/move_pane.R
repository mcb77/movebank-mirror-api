# move (v1) demo — the same study through the older `move` package.
#
# move v1 hardcodes the live Movebank URL inside its function bodies, so unlike
# move2 it can't be redirected via an option. We source url_shim.R from the
# compat harness, which the rocker container mounts at /work — so it resolves at
# /work/move-r/url_shim.R. (This demo runs from ~/demo; see README.md for the
# two mounts.)
source("/work/move-r/url_shim.R")

library(move)

# Mirror URL from the same env var the container / baked .Rprofile use, so this
# works under either networking mode with no edits:
#   host networking        -> http://localhost:8080/movebank
#   Docker Desktop bridge  -> http://host.docker.internal:8080/movebank
api <- Sys.getenv("MOVEBANK_MIRROR_API_URL", unset = "http://host.docker.internal:8080/movebank")

login <- movebankLogin(username = "ignored", password = "ignored")   # mirror ignores creds
override_url(login, api)                                             # redirect move -> mirror
study <- getMovebankData(study = 2911040, login = login)

plot(study, col = rainbow(n.indiv(study)), pch = ".")
