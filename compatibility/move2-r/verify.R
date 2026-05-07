#!/usr/bin/env Rscript
# verify.R — exercise movebank-mirror-api through the `move2` R package
#
# Run:    Rscript verify.R
# Env:    MOVEBANK_MIRROR_API_URL  (default http://localhost:8080/movebank)
#         STUDY_ID                  (default 2911040)
#
# Exits 0 if every check passes, 1 otherwise.

suppressPackageStartupMessages({
    library(move2)
    library(dplyr)
})

# ── Config ─────────────────────────────────────────────────────────────────
base_url <- Sys.getenv("MOVEBANK_MIRROR_API_URL",
                      unset = "http://localhost:8080/movebank")
study_id <- as.integer(Sys.getenv("STUDY_ID", unset = "2911040"))

# move2 builds URLs as: <api_url>?entity_type=<type>&… so the option holds the
# /service/direct-read base, *not* just the host. (move2 source: see
# move2:::movebank_construct_url.)
options(move2_movebank_api_url = paste0(base_url, "/service/direct-read"))

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
    if (!is.null(out)) record(name, TRUE)
    invisible(out)
}

cat(sprintf("=== verifying movebank-mirror-api at %s\n", base_url))
cat(sprintf("=== fixture study: %d\n\n", study_id))

handle <- movebank_handle(username = "ignored", password = "ignored")

# ── 1. Study listing ───────────────────────────────────────────────────────
check("movebank_download_study_info() returns rows", function() {
    studies <- movebank_download_study_info(handle = handle)
    if (!inherits(studies, "data.frame"))   stop("expected data.frame/tibble")
    if (nrow(studies) == 0)                 stop("expected non-empty result")
    studies
})

# ── 2. Individuals (animals) ───────────────────────────────────────────────
check(sprintf("movebank_retrieve(individual, study=%d) returns rows", study_id), function() {
    individuals <- movebank_retrieve(
        entity_type = "individual", study_id = study_id, handle = handle)
    if (!inherits(individuals, "data.frame")) stop("expected data.frame/tibble")
    if (nrow(individuals) == 0)               stop("expected non-empty result")
    individuals
})

# ── 3. Sensors ─────────────────────────────────────────────────────────────
check(sprintf("movebank_retrieve(sensor, tag_study=%d) returns rows", study_id), function() {
    sensors <- movebank_retrieve(
        entity_type = "sensor", tag_study_id = study_id, handle = handle)
    if (!inherits(sensors, "data.frame")) stop("expected data.frame/tibble")
    if (nrow(sensors) == 0)               stop("expected non-empty result")
    sensors
})

# ── 4. Full study download (the wow demo) ──────────────────────────────────
check(sprintf("movebank_download_study(%d) returns move2 object", study_id), function() {
    track <- movebank_download_study(
        study_id = study_id, handle = handle,
        attributes = "all", remove_movebank_outliers = FALSE)
    if (!inherits(track, "move2"))      stop("expected move2 object")
    if (nrow(track) == 0)               stop("expected non-empty result")
    track
})

# ── Summary ────────────────────────────────────────────────────────────────
n_pass  <- sum(vapply(results, function(r) isTRUE(r$ok), logical(1)))
n_total <- length(results)
cat(sprintf("\n=== %d/%d checks passed\n", n_pass, n_total))

if (n_pass < n_total) {
    cat("\nFailing checks:\n")
    for (r in results) if (!isTRUE(r$ok)) {
        cat(sprintf("  - %s: %s\n", r$name,
                    if (!is.null(r$msg) && nchar(r$msg) > 0) r$msg else "(no message)"))
    }
    quit(status = 1)
}

quit(status = 0)
