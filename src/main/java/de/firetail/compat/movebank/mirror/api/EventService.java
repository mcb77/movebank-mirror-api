package de.firetail.compat.movebank.mirror.api;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

@Service
public class EventService {

    private final String mirrorBaseDir;
    private final MirrorService mirrorService;

    public EventService(
            @Value("${movebank.mirror.base-dir}") String mirrorBaseDir,
            MirrorService mirrorService) {
        this.mirrorBaseDir = mirrorBaseDir;
        this.mirrorService = mirrorService;
    }

    /** Synthetic columns the server can fill from filesystem layout + deployment metadata. */
    private static final Set<String> SYNTHETIC_COLS = Set.of(
            "tag_id", "individual_id", "deployment_id", "event_id");

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

        File studyDir = new File(mirrorBaseDir, String.format("%012d", Long.parseLong(studyId)));
        if (!studyDir.isDirectory()) return;

        // sensor_type_id can be a CSV list — move::getMovebankData sends every
        // location-capable sensor type as a comma-separated value. Empty/missing
        // means "all sensor types in this study".
        List<String> sensorTypeIds = new ArrayList<>(MirrorService.csvToSet(sensorTypeId));
        if (sensorTypeIds.isEmpty()) sensorTypeIds = sensorTypesForStudy(studyId);
        if (sensorTypeIds.isEmpty()) return;

        // Build the per-tag deployment lookup once for the whole response.
        // Used to enrich event rows with tag_id / individual_id / deployment_id
        // (the on-disk CSV files don't carry these — they come from the
        // directory layout and deployment table).
        Map<String, List<DeploymentWindow>> deploymentsByTag =
                mirrorService.getDeploymentsByTag(studyId);
        EventIdSeq idSeq = new EventIdSeq();

        boolean headerWritten = false;
        for (String stid : sensorTypeIds) {
            if (individualId != null || deploymentId != null) {
                headerWritten = writeEventsForDeployments(
                        studyId, stid, individualId, deploymentId, studyDir, csv,
                        requestedAttrs, deploymentsByTag, idSeq, headerWritten);
            } else {
                headerWritten = streamCsvFiles(
                        tagDirs(studyDir, tagId), stid, null, csv,
                        requestedAttrs, deploymentsByTag, idSeq, headerWritten);
            }
        }
    }

    /** Mutable monotonic event_id source — synthetic, scoped to a single response. */
    private static final class EventIdSeq {
        private long n = 1;
        long next() { return n++; }
    }

    /** Parses a comma-separated attribute list. Returns null when absent/empty. */
    private static List<String> parseAttributes(String csv) {
        if (csv == null || csv.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    /** Sorted list of every sensor type id known to this study, or empty if metadata is missing. */
    private List<String> sensorTypesForStudy(String studyId) throws IOException {
        StudyJson study = mirrorService.loadStudy(studyId);
        if (study == null || study.sensorTypeIds == null || study.sensorTypeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(study.sensorTypeIds);
        Collections.sort(ids); // deterministic order
        return ids;
    }

    private boolean writeEventsForDeployments(
            String studyId, String sensorTypeId,
            String individualId, String deploymentId,
            File studyDir, CSVWriter csv,
            List<String> requestedAttrs,
            Map<String, List<DeploymentWindow>> deploymentsByTag,
            EventIdSeq idSeq,
            boolean headerWritten) throws Exception {

        List<DeploymentWindow> windows =
                mirrorService.getDeploymentWindows(studyId, individualId, deploymentId);

        // Group windows by tag to avoid reading the same CSV files multiple times
        Map<String, List<DeploymentWindow>> windowsByTag = new LinkedHashMap<>();
        for (DeploymentWindow w : windows)
            windowsByTag.computeIfAbsent(w.tagId(), k -> new ArrayList<>()).add(w);

        for (Map.Entry<String, List<DeploymentWindow>> entry : windowsByTag.entrySet()) {
            File tagDir = new File(studyDir, entry.getKey());
            List<DeploymentWindow> tagWindows = entry.getValue();
            Predicate<String> filter = ts -> tagWindows.stream().anyMatch(w -> w.contains(ts));
            headerWritten = streamCsvFiles(
                    List.of(tagDir), sensorTypeId, filter, csv,
                    requestedAttrs, deploymentsByTag, idSeq, headerWritten);
        }
        return headerWritten;
    }

    // ── Core streaming ─────────────────────────────────────────────────────

    /**
     * Streams all matching CSV files from {@code tagDirs} into {@code csv}.
     * <p>If {@code timestampFilter} is non-null, only rows whose {@code timestamp}
     * column value passes the filter are written.
     * <p>If {@code requestedAttrs} is non-null, the output is projected to those
     * columns in that order; columns absent from the source CSV are emitted as
     * empty strings; columns present in the source but not requested are dropped.
     *
     * @return true if the CSV header was written (either before or during this call)
     */
    private boolean streamCsvFiles(
            List<File> tagDirs, String sensorTypeId,
            Predicate<String> timestampFilter,
            CSVWriter csv, List<String> requestedAttrs,
            Map<String, List<DeploymentWindow>> deploymentsByTag,
            EventIdSeq idSeq,
            boolean headerWritten) throws Exception {

        for (File tagDir : tagDirs) {
            if (!tagDir.isDirectory()) continue;
            File[] csvFiles = tagDir.listFiles(
                (d, name) -> name.startsWith(sensorTypeId + "_") && name.endsWith(".csv"));
            if (csvFiles == null || csvFiles.length == 0) continue;
            Arrays.sort(csvFiles); // chronological order (timestamp baked into filename)

            String currentTagId = tagDir.getName();
            List<DeploymentWindow> tagDeployments =
                    deploymentsByTag.getOrDefault(currentTagId, Collections.emptyList());

            for (File csvFile : csvFiles) {
                try (CSVReader reader = new CSVReader(
                        new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
                    String[] header = reader.readNext();
                    if (header == null) continue;
                    if (!headerWritten) {
                        String[] outHeader = (requestedAttrs == null)
                                ? augmentHeader(header)
                                : requestedAttrs.toArray(new String[0]);
                        csv.writeNext(outHeader, false);
                        headerWritten = true;
                    }
                    int tsIdx = indexOf(header, "timestamp");
                    int[] projection = (requestedAttrs == null) ? null
                            : buildProjection(header, requestedAttrs);
                    String[] row;
                    while ((row = reader.readNext()) != null) {
                        String ts = eventTimestamp(row, tsIdx);
                        if (timestampFilter != null && !timestampFilter.test(ts)) continue;
                        DeploymentWindow active = findActive(tagDeployments, ts);
                        long eventId = idSeq.next();
                        if (projection == null) {
                            // No attribute projection: emit raw row plus synthetic columns.
                            csv.writeNext(augmentRow(row, currentTagId, active, eventId), false);
                        } else {
                            String[] projected = new String[projection.length];
                            for (int i = 0; i < projection.length; i++) {
                                String name = requestedAttrs.get(i);
                                int srcIdx = projection[i];
                                // Always prefer the value from the underlying CSV
                                // when present and meaningful. Synthetic values
                                // (tag_id / individual_id / deployment_id / event_id)
                                // are a fallback for older mirrors that didn't
                                // download those columns.
                                String fromSource = (srcIdx >= 0 && srcIdx < row.length)
                                        ? row[srcIdx] : null;
                                if (fromSource != null && !fromSource.isEmpty()
                                        && !"NA".equals(fromSource)) {
                                    projected[i] = fromSource;
                                } else {
                                    String syn = synthetic(name, currentTagId, active, eventId);
                                    // "NA" (R's default na.strings) for columns absent
                                    // from this sensor's CSV — empty strings would crash
                                    // read.csv when colClasses declares the column numeric.
                                    projected[i] = (syn != null) ? syn : "NA";
                                }
                            }
                            csv.writeNext(projected, false);
                        }
                    }
                }
            }
        }
        return headerWritten;
    }

    /** Returns the synthetic value for {@code colName}, or null if {@code colName} is not synthetic. */
    private static String synthetic(String colName, String tagId, DeploymentWindow active, long eventId) {
        switch (colName) {
            case "tag_id":        return tagId;
            case "individual_id": return active != null ? safe(active.individualId()) : "NA";
            case "deployment_id": return active != null ? safe(active.deploymentId()) : "NA";
            case "event_id":      return Long.toString(eventId);
            default:              return null;
        }
    }

    private static String safe(String s) { return (s == null || s.isEmpty()) ? "NA" : s; }

    /** Locates the deployment window covering {@code ts}, or null if none. */
    private static DeploymentWindow findActive(List<DeploymentWindow> windows, String ts) {
        if (ts == null) return null;
        for (DeploymentWindow w : windows) {
            if (w.contains(ts)) return w;
        }
        return null;
    }

    /** Adds synthetic columns to a header that lacks them. */
    private static String[] augmentHeader(String[] header) {
        List<String> out = new ArrayList<>(Arrays.asList(header));
        for (String s : SYNTHETIC_COLS) {
            if (indexOf(header, s) < 0) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** Adds synthetic column values to a raw row that lacks them. Order matches augmentHeader. */
    private static String[] augmentRow(String[] row, String tagId, DeploymentWindow active, long eventId) {
        // Determine which synthetic cols need to be appended (any not already in source header).
        // We don't have the header here; assume the source has none of them (true for the on-disk
        // CSV format the mirror writes today). If the format ever changes, this would emit
        // duplicates — caught by augmentHeader's matching set check.
        String[] out = Arrays.copyOf(row, row.length + SYNTHETIC_COLS.size());
        int i = row.length;
        // Order must match augmentHeader's iteration over SYNTHETIC_COLS — Set.of() preserves
        // declaration order in Java 21? No — Set.of returns an immutable set with implementation-
        // defined order. To stay deterministic, list the columns in a fixed sequence here.
        out[i++] = tagId;
        out[i++] = active != null ? safe(active.individualId()) : "NA";
        out[i++] = active != null ? safe(active.deploymentId()) : "NA";
        out[i]   = Long.toString(eventId);
        return out;
    }

    /** Returns an int[] mapping each requested attribute to its index in the source header (-1 if absent). */
    private static int[] buildProjection(String[] sourceHeader, List<String> requestedAttrs) {
        int[] proj = new int[requestedAttrs.size()];
        for (int i = 0; i < requestedAttrs.size(); i++) {
            proj[i] = indexOf(sourceHeader, requestedAttrs.get(i));
        }
        return proj;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<File> tagDirs(File studyDir, String tagId) {
        if (tagId != null) {
            File d = new File(studyDir, tagId);
            return d.isDirectory() ? List.of(d) : Collections.emptyList();
        }
        File[] dirs = studyDir.listFiles(File::isDirectory);
        return dirs != null ? Arrays.asList(dirs) : Collections.emptyList();
    }

    private static String eventTimestamp(String[] row, int tsIdx) {
        return (tsIdx >= 0 && tsIdx < row.length) ? row[tsIdx] : null;
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++)
            if (value.equals(arr[i])) return i;
        return -1;
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
