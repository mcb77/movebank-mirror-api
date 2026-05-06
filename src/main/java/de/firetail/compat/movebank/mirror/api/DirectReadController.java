package de.firetail.compat.movebank.mirror.api;

import com.opencsv.CSVWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@RestController
public class DirectReadController {

    private static final Set<String> KNOWN_TYPES = Set.of(
            "study", "tag", "individual", "deployment", "sensor",
            "study_attribute", "tag_type", "event");

    @Autowired private MirrorService mirrorService;
    @Autowired private EventService  eventService;

    @GetMapping("/movebank/service/direct-read")
    public void directRead(
            @RequestParam Map<String, String> params,
            HttpServletResponse response) throws Exception {

        String entityType = params.get("entity_type");

        // Validate before setting Content-Type — sendError() must be free to
        // negotiate the framework's default error body without colliding with
        // a pre-pinned text/csv response.
        if (entityType == null || !KNOWN_TYPES.contains(entityType)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Unknown entity_type: " + entityType);
            return;
        }

        response.setContentType("text/csv;charset=UTF-8");
        try (CSVWriter csv = new CSVWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8))) {
            switch (entityType) {
                case "study"           -> mirrorService.writeStudies(params, csv);
                case "tag"             -> mirrorService.writeTags(params, csv);
                case "individual"      -> mirrorService.writeIndividuals(params, csv);
                case "deployment"      -> mirrorService.writeDeployments(params, csv);
                case "sensor"          -> mirrorService.writeSensors(params, csv);
                case "study_attribute" -> mirrorService.writeStudyAttributes(params, csv);
                case "tag_type"        -> mirrorService.writeTagTypes(csv);
                case "event"           -> eventService.writeEvents(params, csv);
                default -> throw new IllegalStateException("unreachable: " + entityType);
            }
        }
    }
}
