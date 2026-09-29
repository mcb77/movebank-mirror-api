# Galapagos Albatrosses (Movebank study 2911040) with the older move package.
# move v1 has the live Movebank URL built in: the shim points it at the mirror.
source("/work/move-r/url_shim.R")
library(move)

api <- Sys.getenv("MOVEBANK_MIRROR_API_URL", unset = "http://host.docker.internal:8080/movebank")
login <- movebankLogin(username = "ignored", password = "ignored")
override_url(login, api)

study <- getMovebankData(study = 2911040, login = login)
plot(study, col = rainbow(n.indiv(study)), pch = ".")
