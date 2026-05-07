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

    // Mirror of the live Movebank /tag_type table (verified 2026-05-07 by
    // curl against the live API). Columns and order match what live returns:
    // description, external_id, id, is_location_sensor, name (alphabetical).
    // IDs match the ones the live API uses; getting this right matters because
    // some clients look up sensor types by their numeric id, and the move R
    // package crosses /tag_type with /event's sensor_type_id column.
    //
    // 9 sensor types currently catalogued by Movebank. If a new one appears
    // upstream that you need served locally, add it here.
    private static final String[][] TAG_TYPES = {
        // description, external_id,            id,         is_location_sensor, name
        { "",          "bird-ring",             "397",      "true",  "Bird Ring"             },
        { "",          "gps",                   "653",      "true",  "GPS"                   },
        { "",          "radio-transmitter",     "673",      "true",  "Radio Transmitter"     },
        { "",          "argos-doppler-shift",   "82798",    "true",  "Argos Doppler Shift"   },
        { "",          "natural-mark",          "2365682",  "true",  "Natural Mark"          },
        { "",          "acceleration",          "2365683",  "false", "Acceleration"          },
        { "",          "solar-geolocator",      "3886361",  "true",  "Solar Geolocator"      },
        { "",          "accessory-measurements","7842954",  "false", "Accessory Measurements"},
        { "",          "solar-geolocator-raw",  "9301403",  "false", "Solar Geolocator Raw"  },
    };
    private static final String[] TAG_TYPE_HEADER = {
        "description", "external_id", "id", "is_location_sensor", "name"
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
        writeMaps(rows, csv, parseAttributes(params.get("attributes")));
    }

    public void writeTags(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        writeMaps(study.tags, csv, parseAttributes(params.get("attributes")));
    }

    public void writeIndividuals(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        writeMaps(study.individuals, csv, parseAttributes(params.get("attributes")));
    }

    /**
     * Live Movebank's {@code /deployment} endpoint omits the three
     * relational-linkage columns ({@code id}, {@code tag_id},
     * {@code individual_id}) when the request does not pass an explicit
     * {@code attributes=…}. Our mirror merges them in (see
     * {@code MovebankMirror.getAllDeploymentRefDataForStudy}, which does two
     * API roundtrips precisely to capture them), so the on-disk JSON has
     * them — but emitting them by default breaks {@code move2}'s
     * {@code movebank_download_deployment}, whose first {@code left_join}
     * would collide on {@code individual_id} between the unfiltered
     * deployment fetch and the {@code attributes=id,tag_id,individual_id}
     * fetch. Drop them when no projection is requested to match the live
     * API's shape.
     */
    private static final List<String> DEPLOYMENT_LINKAGE_COLUMNS =
            List.of("tag_id", "individual_id");

    public void writeDeployments(Map<String, String> params, CSVWriter csv) throws IOException {
        StudyJson study = loadStudy(params.get("study_id"));
        if (study == null) return;
        String tagId        = params.get("tag_id");
        String individualId = params.get("individual_id");
        List<String> requestedAttrs = parseAttributes(params.get("attributes"));
        List<Map<String, String>> rows = study.deployments;
        if (tagId != null)        rows = rows.stream().filter(r -> tagId.equals(r.get("tag_id"))).toList();
        if (individualId != null) rows = rows.stream().filter(r -> individualId.equals(r.get("individual_id"))).toList();

        // Live-Movebank quirk: when no attributes= is requested, the linkage
        // columns are not in the response. See DEPLOYMENT_LINKAGE_COLUMNS above
        // and BUG_COMPATIBILITY.md.
        if (requestedAttrs == null) {
            rows = rows.stream().map(r -> {
                Map<String, String> copy = new LinkedHashMap<>(r);
                DEPLOYMENT_LINKAGE_COLUMNS.forEach(copy::remove);
                return copy;
            }).toList();
        }

        writeMaps(rows, csv, requestedAttrs);
    }

    public void writeSensors(Map<String, String> params, CSVWriter csv) throws IOException {
        // RequestBuilderSensor uses tag_study_id, not study_id
        String studyId = params.getOrDefault("study_id", params.get("tag_study_id"));
        StudyJson study = loadStudy(studyId);
        if (study == null) return;
        writeMaps(study.sensors, csv, parseAttributes(params.get("attributes")));
    }

    /**
     * Parses a comma-separated attribute list. Returns null when absent/empty
     * or when the value is the special token {@code "all"} — Movebank's live
     * API treats {@code attributes=all} as "every column from the source",
     * which matches our default no-projection behaviour.
     */
    static List<String> parseAttributes(String csv) {
        if (csv == null || csv.isEmpty()) return null;
        if ("all".equalsIgnoreCase(csv.trim())) return null;
        List<String> out = new ArrayList<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? null : out;
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
        writeMaps(rows, csv, null);
    }

    /**
     * Writes the rows as CSV. When {@code requestedAttrs} is non-null, the
     * output is projected to exactly those columns in that order; columns not
     * in the source are emitted as empty strings. When null, the union of all
     * source keys (in first-seen order) is used.
     */
    private void writeMaps(List<Map<String, String>> rows, CSVWriter csv,
                           List<String> requestedAttrs) {
        if (rows == null || rows.isEmpty()) return;
        String[] header;
        if (requestedAttrs != null) {
            header = requestedAttrs.toArray(new String[0]);
        } else {
            Set<String> keys = new LinkedHashSet<>();
            for (Map<String, String> row : rows) keys.addAll(row.keySet());
            header = keys.toArray(new String[0]);
        }
        csv.writeNext(header, false);
        for (Map<String, String> row : rows) {
            String[] values = new String[header.length];
            for (int i = 0; i < header.length; i++)
                values[i] = row.getOrDefault(header[i], "");
            csv.writeNext(values, false);
        }
    }
}
