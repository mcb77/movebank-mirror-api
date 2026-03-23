package de.firetail.mulletserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class MirrorService {

    private final String mirrorBaseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public MirrorService(@Value("${mullet.mirror.base-dir}") String mirrorBaseDir) {
        this.mirrorBaseDir = mirrorBaseDir;
    }

    // ── Tag types (static) ─────────────────────────────────────────────────

    private static final String[][] TAG_TYPES = {
        { "397",     "argos-doppler-shift" },
        { "2299894", "bird-ring"           },
        { "653",     "gps"                 },
        { "673",     "radio-transmitter"   },
        { "2365683", "acceleration"        },
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
     * Exactly one of {@code individualId} or {@code deploymentId} should be non-null,
     * but both are optional — passing neither returns all deployments in the study.
     * <p>
     * The time window is taken from {@code deploy_on_timestamp} /
     * {@code deploy_off_timestamp}, falling back to {@code timestamp_start} /
     * {@code timestamp_end} if those are blank (as observed in some mirror data).
     */
    public List<DeploymentWindow> getDeploymentWindows(
            String studyId, String individualId, String deploymentId) throws IOException {
        StudyJson study = loadStudy(studyId);
        if (study == null || study.deployments == null) return Collections.emptyList();

        List<DeploymentWindow> windows = new ArrayList<>();
        for (Map<String, String> d : study.deployments) {
            if (individualId != null && !individualId.equals(d.get("individual_id"))) continue;
            if (deploymentId  != null && !deploymentId.equals(d.get("id")))           continue;
            String tagId = d.get("tag_id");
            if (tagId == null || tagId.isBlank()) continue;
            String from = firstNonBlank(d.get("deploy_on_timestamp"),  d.get("timestamp_start"));
            String to   = firstNonBlank(d.get("deploy_off_timestamp"), d.get("timestamp_end"));
            windows.add(new DeploymentWindow(tagId, from, to));
        }
        return windows;
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
        csv.writeNext(new String[]{ "study_id", "sensor_type_id", "short_name", "data_type" });
        for (String attr : attrs)
            csv.writeNext(new String[]{ params.get("study_id"), sensorTypeId, attr, "" });
    }

    public void writeTagTypes(CSVWriter csv) {
        csv.writeNext(new String[]{ "id", "external_id" });
        for (String[] row : TAG_TYPES)
            csv.writeNext(row);
    }

    // ── CSV helper ─────────────────────────────────────────────────────────

    private void writeMaps(List<Map<String, String>> rows, CSVWriter csv) {
        if (rows == null || rows.isEmpty()) return;
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> row : rows) keys.addAll(row.keySet());
        String[] header = keys.toArray(new String[0]);
        csv.writeNext(header);
        for (Map<String, String> row : rows) {
            String[] values = new String[header.length];
            for (int i = 0; i < header.length; i++)
                values[i] = row.getOrDefault(header[i], "");
            csv.writeNext(values);
        }
    }
}
