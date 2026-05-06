package de.firetail.compat.movebank.mirror.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class MirrorService {

    private final String mirrorBaseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public MirrorService(@Value("${movebank.mirror.base-dir}") String mirrorBaseDir) {
        this.mirrorBaseDir = mirrorBaseDir;
    }

    // ── Tag types (static) ─────────────────────────────────────────────────

    // Columns chosen to satisfy move::getMovebankData, which reads `id`, `name`,
    // and `is_location_sensor`. `external_id` is kept for clients that look it
    // up (mullet-rest-client / movebank-api-client use it via Constants).
    private static final String[][] TAG_TYPES = {
        // id,        name,                  external_id,          is_location_sensor
        { "397",     "Argos Doppler Shift", "argos-doppler-shift", "true"  },
        { "2299894", "Bird Ring",           "bird-ring",           "true"  },
        { "653",     "GPS",                 "gps",                 "true"  },
        { "673",     "Radio Transmitter",   "radio-transmitter",   "true"  },
        { "2365683", "Acceleration",        "acceleration",        "false" },
    };
    private static final String[] TAG_TYPE_HEADER = {
        "id", "name", "external_id", "is_location_sensor"
    };

    // ── File helpers ───────────────────────────────────────────────────────

    private File studyFile(String studyId) {
        return new File(mirrorBaseDir, String.format("%012d", Long.parseLong(studyId)) + ".json");
    }

    StudyJson loadStudy(String studyId) throws IOException {
        File f = studyFile(studyId);
        return f.exists() ? mapper.readValue(f, StudyJson.class) : null;
    }

    private List<StudyJson> loadAllStudies() throws IOException {
        File dir = new File(mirrorBaseDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json") && !name.endsWith("-license.json"));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files);
        List<StudyJson> studies = new ArrayList<>();
        for (File f : files) {
            try { studies.add(mapper.readValue(f, StudyJson.class)); }
            catch (Exception e) { /* skip malformed file */ }
        }
        return studies;
    }

    // ── Deployment windows ─────────────────────────────────────────────────

    /**
     * Returns deployment windows matching the given filters.
     * <p>{@code individualId} and {@code deploymentId} accept either a single id or a
     * comma-separated list — the latter is what {@code move::getMovebankData} sends
     * for studies with many individuals. Both are optional; passing neither returns
     * every deployment in the study.
     * <p>
     * The time window is taken from {@code deploy_on_timestamp} /
     * {@code deploy_off_timestamp}, falling back to {@code timestamp_start} /
     * {@code timestamp_end} if those are blank (as observed in some mirror data).
     */
    public List<DeploymentWindow> getDeploymentWindows(
            String studyId, String individualId, String deploymentId) throws IOException {
        StudyJson study = loadStudy(studyId);
        if (study == null || study.deployments == null) return Collections.emptyList();

        Set<String> individualIds = csvToSet(individualId);
        Set<String> deploymentIds = csvToSet(deploymentId);

        List<DeploymentWindow> windows = new ArrayList<>();
        for (Map<String, String> d : study.deployments) {
            if (!individualIds.isEmpty() && !individualIds.contains(d.get("individual_id"))) continue;
            if (!deploymentIds.isEmpty() && !deploymentIds.contains(d.get("id")))           continue;
            String tagId = d.get("tag_id");
            if (tagId == null || tagId.isBlank()) continue;
            String from = firstNonBlank(d.get("deploy_on_timestamp"),  d.get("timestamp_start"));
            String to   = firstNonBlank(d.get("deploy_off_timestamp"), d.get("timestamp_end"));
            windows.add(new DeploymentWindow(
                    tagId, d.get("individual_id"), d.get("id"), from, to));
        }
        return windows;
    }

    /** All deployment windows in the study, indexed by tag id. Used to enrich event rows. */
    public Map<String, List<DeploymentWindow>> getDeploymentsByTag(String studyId) throws IOException {
        List<DeploymentWindow> all = getDeploymentWindows(studyId, null, null);
        Map<String, List<DeploymentWindow>> byTag = new HashMap<>();
        for (DeploymentWindow w : all) {
            byTag.computeIfAbsent(w.tagId(), k -> new ArrayList<>()).add(w);
        }
        return byTag;
    }

    /**
     * Splits a comma-separated id string into a set, treating null/blank as the empty set.
     * Used to interpret query parameters that may carry one or many ids.
     */
    public static Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values)
            if (v != null && !v.isBlank()) return v;
        return null;
    }

    // ── Entity handlers ────────────────────────────────────────────────────

    public void writeStudies(Map<String, String> params, CSVWriter csv) throws IOException {
        String id   = params.get("id");
        String name = params.get("name");

        List<Map<String, String>> rows;
        if (id != null) {
            StudyJson s = loadStudy(id);
            rows = s != null ? List.of(s.study) : Collections.emptyList();
        } else {
            rows = new ArrayList<>();
            for (StudyJson s : loadAllStudies()) {
                if (name == null || name.equals(s.study.get("name")))
                    rows.add(s.study);
            }
        }
        writeMaps(rows, csv);
    }

    public void writeTags(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        writeMaps(study.tags, csv);
    }

    public void writeIndividuals(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        writeMaps(study.individuals, csv);
    }

    public void writeDeployments(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        String tagId        = params.get("tag_id");
        String individualId = params.get("individual_id");
        List<Map<String, String>> rows = study.deployments;
        if (tagId != null)        rows = rows.stream().filter(r -> tagId.equals(r.get("tag_id"))).toList();
        if (individualId != null) rows = rows.stream().filter(r -> individualId.equals(r.get("individual_id"))).toList();
        writeMaps(rows, csv);
    }

    public void writeSensors(Map<String, String> params, CSVWriter csv) throws IOException {
        // RequestBuilderSensor uses tag_study_id, not study_id
        String studyId = params.getOrDefault("study_id", params.get("tag_study_id"));
        StudyJson study = loadStudy(studyId);
        if (study == null) return;
        writeMaps(study.sensors, csv);
    }

    public void writeStudyAttributes(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        String sensorTypeId = params.get("sensor_type_id");
        List<String> attrs = study.attributesBySensorTypeIDs.get(sensorTypeId);
        if (attrs == null) return;
        csv.writeNext(new String[]{ "study_id", "sensor_type_id", "short_name", "data_type" }, false);
        for (String attr : attrs)
            csv.writeNext(new String[]{ params.get("study_id"), sensorTypeId, attr, "" }, false);
    }

    public void writeTagTypes(CSVWriter csv) {
        csv.writeNext(TAG_TYPE_HEADER, false);
        for (String[] row : TAG_TYPES)
            csv.writeNext(row, false);
    }

    // ── CSV helper ─────────────────────────────────────────────────────────

    private void writeMaps(List<Map<String, String>> rows, CSVWriter csv) {
        if (rows == null || rows.isEmpty()) return;
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> row : rows) keys.addAll(row.keySet());
        String[] header = keys.toArray(new String[0]);
        csv.writeNext(header, false);
        for (Map<String, String> row : rows) {
            String[] values = new String[header.length];
            for (int i = 0; i < header.length; i++)
                values[i] = row.getOrDefault(header[i], "");
            csv.writeNext(values, false);
        }
    }
}
