package de.firetail.mulletserver;

import com.opencsv.CSVWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class DirectReadController {

    @Autowired private MirrorService mirrorService;
    @Autowired private EventService  eventService;

    @GetMapping("/movebank/service/direct-read")
    public void directRead(
            @RequestParam Map<String, String> params,
            HttpServletResponse response) throws Exception {

        String entityType = params.get("entity_type");
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
                default                -> response.sendError(400, "Unknown entity_type: " + entityType);
            }
        }
    }
}
