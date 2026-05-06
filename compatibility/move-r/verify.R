#!/usr/bin/env Rscript
# verify.R — exercise movebank-mirror-api through the `move` R package
#
# Run:    Rscript verify.R
# Env:    MOVEBANK_MIRROR_API_URL  (default http://localhost:8080/movebank)
#         STUDY_ID                  (default 2911040)
#
# Exits 0 if every check passes, 1 otherwise.

suppressPackageStartupMessages(library(move))

# %||% is base-R only since 4.4; define locally for older interpreters.
`%||%` <- function(a, b) if (is.null(a)) b else a

# ── Config ─────────────────────────────────────────────────────────────────
base_url <- Sys.getenv("MOVEBANK_MIRROR_API_URL",
                      unset = "http://localhost:8080/movebank")
study_id <- as.integer(Sys.getenv("STUDY_ID", unset = "2911040"))

# ── Tally + reporter ───────────────────────────────────────────────────────
results <- list()
record  <- function(name, ok, msg = NULL) {
    results[[length(results) + 1]] <<- list(name = name, ok = ok, msg = msg)
    cat(sprintf("[%s] %s%s\n",
                if (ok) "PASS" else "FAIL",
                name,
                if (!is.null(msg) && nchar(msg) > 0) paste0(": ", msg) else ""))
}

check <- function(name, fn) {
    out <- tryCatch(fn(),
                    error = function(e) {
                        record(name, FALSE, conditionMessage(e))
                        return(invisible(NULL))
                    })
    if (!is.null(out) || (!is.null(out) && !inherits(out, "try-error"))) {
        record(name, TRUE)
    }
    invisible(out)
}

# ── URL override ───────────────────────────────────────────────────────────
# `move` hardcodes "https://www.movebank.org/movebank" inside getMovebank's
# function body — there's no public override. We inject a wrapper for httr::GET
# into the `move` namespace that rewrites the URL on the way out. This is
# brittle (depends on move's internal name "GET") but works for current versions.
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
                "verify.R will hit the live API and fail at credential check.")
    }
    login
}

cat(sprintf("=== verifying movebank-mirror-api at %s\n", base_url))
cat(sprintf("=== fixture study: %d\n\n", study_id))

login <- movebankLogin(username = "ignored", password = "ignored")
login <- override_url(login, base_url)

# ── 1. Study listing ───────────────────────────────────────────────────────
check("getMovebank('study') returns rows", function() {
    studies <- getMovebank("study", login)
    if (!is.data.frame(studies))    stop("expected data.frame")
    if (nrow(studies) == 0)         stop("expected non-empty result")
    studies
})

# ── 2. Animals (individuals) ───────────────────────────────────────────────
check(sprintf("getMovebankAnimals(%d) returns rows", study_id), function() {
    animals <- getMovebankAnimals(study = study_id, login = login)
    if (!is.data.frame(animals))    stop("expected data.frame")
    if (nrow(animals) == 0)         stop("expected non-empty result")
    animals
})

# ── 3. Sensors ─────────────────────────────────────────────────────────────
check(sprintf("getMovebankSensors(%d) returns rows", study_id), function() {
    sensors <- getMovebankSensors(study = study_id, login = login)
    if (!is.data.frame(sensors))    stop("expected data.frame")
    if (nrow(sensors) == 0)         stop("expected non-empty result")
    sensors
})

# ── 4. Tracking data ───────────────────────────────────────────────────────
check(sprintf("getMovebankData(%d) returns Move/MoveStack", study_id), function() {
    data <- getMovebankData(study = study_id, login = login)
    if (!inherits(data, c("Move", "MoveStack"))) {
        stop(sprintf("expected Move/MoveStack, got %s",
                     paste(class(data), collapse = ",")))
    }
    n <- if (inherits(data, "MoveStack")) length(data) else nrow(coordinates(data))
    if (n == 0) stop("expected non-empty Move object")
    data
})

# ── Summary ────────────────────────────────────────────────────────────────
n_pass  <- sum(vapply(results, function(r) isTRUE(r$ok), logical(1)))
n_total <- length(results)
cat(sprintf("\n=== %d/%d checks passed\n", n_pass, n_total))

if (n_pass < n_total) {
    cat("\nFailing checks:\n")
    for (r in results) if (!isTRUE(r$ok)) {
        cat(sprintf("  - %s: %s\n", r$name, r$msg %||% "(no message)"))
    }
    quit(status = 1)
}

quit(status = 0)
