package de.firetail.compat.movebank.mirror.api;

import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.mirror.reader.EventQuery;
import de.firetail.compat.movebank.mirror.reader.MirrorLayout;
import de.firetail.compat.movebank.mirror.reader.MirrorReader;
import de.firetail.compat.movebank.mirror.reader.MovebankEventCsv;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code entity_type=event}: translates the request parameters into an
 * {@link EventQuery} and writes the events as Movebank CSV. Reading, ordering
 * and deployment assignment live in movebank-mirror's {@link MirrorReader}.
 */
@Service
public class EventService {

    private final MirrorReader reader;

    public EventService(MirrorReader reader) {
        this.reader = reader;
    }

    public void writeEvents(Map<String, String> params, CSVWriter csv) throws Exception {
        String studyId      = params.get("study_id");
        // move::getMovebankData sends sensor_type_id (often empty), other clients
        // use sensor_sensor_type_id. Accept both; treat blank/empty as absent.
        String sensorTypeId = firstNonEmpty(
                params.get("sensor_sensor_type_id"),
                params.get("sensor_type_id"));
        String tagId        = nullIfEmpty(params.get("tag_id"));
        String individualId = nullIfEmpty(params.get("individual_id"));
        String deploymentId = nullIfEmpty(params.get("deployment_id"));
        // When the caller specifies attributes, the response is projected to
        // exactly those columns in that order. This lets move::getMovebankData
        // aggregate across sensor types whose underlying CSVs have differing
        // column sets — missing columns become empty strings, foreign columns
        // are dropped.
        List<String> requestedAttrs = parseAttributes(params.get("attributes"));

        if (!MirrorLayout.studyDir(reader.baseDir(), studyId).isDirectory()) return;

        // sensor_type_id can be a CSV list — move::getMovebankData sends every
        // location-capable sensor type as a comma-separated value. Empty/missing
        // means "all sensor types in this study".
        List<String> sensorTypeIds = new ArrayList<>(MirrorService.csvToSet(sensorTypeId));
        if (sensorTypeIds.isEmpty()) sensorTypeIds = reader.sensorTypeIds(studyId);
        if (sensorTypeIds.isEmpty()) return;
        sensorTypeIds.sort(MirrorReader::compareIds);

        // Without an attribute projection, project to the union of every source
        // CSV's columns plus the synthetic columns: with several sensor types the
        // source schemas differ, so one stable header is the only way to share a
        // CSV stream. With one sensor type this is effectively the identity.
        List<String> columns = (requestedAttrs != null)
                ? requestedAttrs
                : MovebankEventCsv.defaultColumns(reader, studyId, sensorTypeIds, tagId);

        // individual_id / deployment_id select deployments; only events inside them are sent.
        // Without these filters every event is sent, deployed or not.
        EventQuery query = EventQuery.study(studyId)
                .sensorTypes(sensorTypeIds)
                .tag(tagId)
                .individuals(MirrorService.csvToSet(individualId))
                .deployments(MirrorService.csvToSet(deploymentId));
        reader.readEvents(query, new MovebankEventCsv(csv, columns));
    }

    /**
     * Parses a comma-separated attribute list. Returns null when absent/empty
     * <em>or</em> when the value is the special token {@code "all"}: Movebank's
     * live API treats {@code attributes=all} as "every column from the source",
     * with no projection. {@code move2::movebank_download_study} sends this by
     * default. Returning null routes the caller down the no-projection path,
     * matching that behaviour.
     */
    private static List<String> parseAttributes(String csv) {
        if (csv == null || csv.isEmpty()) return null;
        if ("all".equalsIgnoreCase(csv.trim())) return null;
        List<String> out = new ArrayList<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values)
            if (v != null && !v.isEmpty()) return v;
        return null;
    }
}
