# Galapagos Albatrosses (Movebank study 2911040): download the tracks and map them.
library(move2); library(ggplot2); library(sf); library(rnaturalearth)

albatrosses <- movebank_download_study(2911040)

ggplot() +
  geom_sf(data = ne_countries(scale = "medium", returnclass = "sf"), fill = "grey90") +
  geom_sf(data = albatrosses, aes(color = factor(mt_track_id(albatrosses))), size = 0.1) +
  coord_sf(xlim = c(-95, -75), ylim = c(-15, 5)) +
  guides(color = "none") + theme_minimal()
