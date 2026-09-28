# move2 demo — ordinary move2 code, reading from the local mirror, unchanged.
#
# There is nothing mirror-specific in this script: it's the same code you'd
# write against the live Movebank API. The redirect to the local replay server
# comes entirely from the container's baked .Rprofile, which sets
# options(move2_movebank_api_url = ...). That's the whole point — your existing
# analysis runs offline with no edits.
#
# Companion to move-r's / move2-r's verify.R: those prove compatibility for a
# release gate; this is the human-facing, visual version.
library(move2); library(ggplot2); library(sf); library(rnaturalearth)

albatrosses <- movebank_download_study(2911040)   # reads local mirror, not the API

ggplot() +
  geom_sf(data = ne_countries(scale = "medium", returnclass = "sf"), fill = "grey90") +
  geom_sf(data = albatrosses, aes(color = factor(mt_track_id(albatrosses))), size = 0.1) +
  coord_sf(xlim = c(-95, -75), ylim = c(-15, 5)) +
  guides(color = "none") + theme_minimal()
