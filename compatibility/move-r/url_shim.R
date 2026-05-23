# url_shim.R — redirect `move`'s Movebank URL to a local replay server.
#
# `move` hardcodes "https://www.movebank.org/movebank" inside getMovebank's
# function body — there's no public override. We inject a wrapper for
# httr::GET into the `move` namespace (and into httr's own namespace,
# because move calls GET through two indirections) that rewrites the URL
# on the way out. Brittle (depends on move's internal name "GET") but
# works for current versions of the package.
#
# Usage:
#     source("url_shim.R")
#     login <- movebankLogin(username = "ignored", password = "ignored")
#     override_url(login, "http://localhost:8080/movebank")
#
# Set VERIFY_TRACE=1 in the environment to log every rewritten URL to
# stderr.

LIVE_BASE <- "https://www.movebank.org/movebank"

override_url <- function(login, target_url) {
    if (target_url == LIVE_BASE) return(login)        # no-op when targeting live
    real_GET <- httr::GET
    rewrite_GET <- function(url, ...) {
        if (is.character(url)) {
            url <- gsub(LIVE_BASE, target_url, url, fixed = TRUE)
        }
        if (nzchar(Sys.getenv("VERIFY_TRACE", ""))) {
            cat("[trace GET]", url, "\n", file = stderr())
        }
        real_GET(url, ...)
    }
    inject <- function(env, label) {
        if (exists("GET", envir = env, inherits = FALSE)) {
            unlockBinding("GET", env)
            assign("GET", rewrite_GET, envir = env)
            lockBinding("GET", env)
            TRUE
        } else FALSE
    }
    # GET reaches move via two indirections: it's exported from httr, and
    # imported into move's imports env. Patch both so the rewrite is robust
    # whether move calls GET() unqualified (via imports) or httr::GET().
    move_imports <- parent.env(asNamespace("move"))
    n_patched <- sum(c(
        inject(asNamespace("httr"), "httr"),
        inject(move_imports,        "move-imports")
    ))
    if (n_patched == 0) {
        warning("Could not patch GET in either httr or move-imports — ",
                "the caller will hit the live API and fail at credential check.")
    }
    login
}
