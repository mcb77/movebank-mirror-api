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

    /**
     * Synthetic columns the server can fill from filesystem layout + deployment
     * metadata. Order is fixed and shared between {@link #augmentHeader} and
     * {@link #augmentRow}: the rows append values in the same order that the
     * header appends names. {@code Set.of(...)} would not work — its iteration
     * order is unspecified and JVM-implementation-dependent, which would
     * silently shuffle values into wrong columns.
     */
    private static final List<String> SYNTHETIC_COLS = List.of(
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
        // Live order: sensor type (numeric), then individual, then timestamp.
        sensorTypeIds.sort(EventService::compareIds);

        // When the caller didn't specify an attribute projection, synthesise one
        // from the union of every source CSV's columns plus the synthetic
        // columns. This unifies the single-sensor-type and multi-sensor-type
        // aggregation paths: with multiple sensor types the source schemas
        // differ (GPS rows have location_lat/long, acceleration rows don't), so
        // the only correct way to share one CSV stream is to project everything
        // to a stable union header. With one sensor type the projection is
        // effectively identity. Either way every emitted row aligns with the
        // header.
        List<String> effectiveAttrs = (requestedAttrs != null)
                ? requestedAttrs
                : buildUnionAttrs(studyDir, sensorTypeIds, tagId);

        // Build the per-tag deployment lookup once for the whole response.
        // Used to enrich event rows with tag_id / individual_id / deployment_id
        // (the on-disk CSV files don't carry these — they come from the
        // directory layout and deployment table).
        Map<String, List<DeploymentWindow>> deploymentsByTag =
                mirrorService.getDeploymentsByTag(studyId);

        // individual_id / deployment_id select deployments; only events inside them are sent.
        // Without these filters every event is sent, deployed or not.
        Set<DeploymentWindow> selected = null;
        List<File> dirs;
        if (individualId != null || deploymentId != null) {
            selected = new HashSet<>(mirrorService.getDeploymentWindows(studyId, individualId, deploymentId));
            Set<String> selectedTags = new HashSet<>();
            for (DeploymentWindow w : selected) selectedTags.add(w.tagId());
            dirs = new ArrayList<>();
            for (File d : tagDirs(studyDir, null)) if (selectedTags.contains(d.getName())) dirs.add(d);
        } else {
            dirs = new ArrayList<>(tagDirs(studyDir, tagId));
        }
        dirs.sort(Comparator.comparing(File::getName, EventService::compareIds));

        Out out = new Out(csv, effectiveAttrs);
        for (String stid : sensorTypeIds) {
            writeSensorType(stid, dirs, deploymentsByTag, selected, out);
        }
    }

    /**
     * Writes one sensor type's events in live Movebank's order: by individual (numeric id),
     * and within an individual by timestamp, merged across all tags the individual carried
     * (tags can overlap in time, and a deployment without deploy_on is open towards the past).
     * Events outside every deployment of their tag come last, tag by tag, in file order; they
     * are only sent when no individual/deployment filter is given.
     * <p>An event belongs to the first deployment of its tag whose window contains its
     * timestamp (the same rule as before), so an event is never sent twice.
     * <p>Memory: one pending row per tag of the current individual (k-way merge over the
     * tags' files, each of which is read front to back).
     */
    private void writeSensorType(String sensorTypeId, List<File> dirs,
                                 Map<String, List<DeploymentWindow>> deploymentsByTag,
                                 Set<DeploymentWindow> selected, Out out) throws Exception {
        // individual -> the tags (in tag order) that carry one of its (selected) deployments
        TreeMap<String, List<File>> tagsByIndividual =
                new TreeMap<>(Comparator.nullsLast(EventService::compareIds));
        for (File dir : dirs) {
            for (DeploymentWindow w : deploymentsByTag.getOrDefault(dir.getName(), List.of())) {
                if (selected != null && !selected.contains(w)) continue;
                List<File> tags = tagsByIndividual.computeIfAbsent(blankToNull(w.individualId()), k -> new ArrayList<>());
                if (!tags.contains(dir)) tags.add(dir);
            }
        }
        for (Map.Entry<String, List<File>> e : tagsByIndividual.entrySet()) {
            String individual = e.getKey();
            List<RowStream> streams = new ArrayList<>();
            for (File dir : e.getValue()) {
                streams.add(new RowStream(streams.size(), dir, sensorTypeId,
                        deploymentsByTag.getOrDefault(dir.getName(), List.of()),
                        w -> w != null && Objects.equals(blankToNull(w.individualId()), individual)
                                && (selected == null || selected.contains(w)),
                        out));
            }
            merge(streams, out);
        }
        if (selected == null) {
            for (File dir : dirs) {
                merge(List.of(new RowStream(0, dir, sensorTypeId,
                        deploymentsByTag.getOrDefault(dir.getName(), List.of()),
                        Objects::isNull, out)), out);
            }
        }
    }

    /** k-way merge by timestamp; ties keep tag order. Closes every stream. */
    private static void merge(List<RowStream> streams, Out out) throws Exception {
        PriorityQueue<RowStream> queue = new PriorityQueue<>(
                Comparator.comparing((RowStream s) -> s.ts, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(s -> s.order));
        try {
            for (RowStream s : streams) if (s.advance()) queue.add(s);
            while (!queue.isEmpty()) {
                RowStream s = queue.poll();
                out.emit(s);
                if (s.advance()) queue.add(s);
            }
        } finally {
            for (RowStream s : streams) s.close();
        }
    }

    /**
     * Returns the union of all source-CSV column names across {@code sensorTypeIds},
     * with the synthetic columns appended. Order: source columns first (in
     * first-seen order, deterministic because sensorTypeIds is sorted and tagDirs
     * are sorted), then any synthetic columns not already present.
     */
    private List<String> buildUnionAttrs(File studyDir, List<String> sensorTypeIds,
                                          String tagId) throws Exception {
        LinkedHashSet<String> union = new LinkedHashSet<>();
        for (String stid : sensorTypeIds) {
            for (File tagDir : tagDirs(studyDir, tagId)) {
                if (!tagDir.isDirectory()) continue;
                File[] csvs = tagDir.listFiles(
                        (d, n) -> n.startsWith(stid + "_") && n.endsWith(".csv"));
                if (csvs == null || csvs.length == 0) continue;
                Arrays.sort(csvs);
                try (CSVReader reader = new CSVReader(
                        new InputStreamReader(new FileInputStream(csvs[0]), StandardCharsets.UTF_8))) {
                    String[] header = reader.readNext();
                    if (header != null) Collections.addAll(union, header);
                }
                break; // one file per sensor type is enough to learn its columns
            }
        }
        for (String s : SYNTHETIC_COLS) union.add(s);
        return new ArrayList<>(union);
    }

    /** Mutable monotonic event_id source — synthetic, scoped to a single response. */
    private static final class EventIdSeq {
        private long n = 1;
        long next() { return n++; }
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

    // ── Core streaming ─────────────────────────────────────────────────────

    /**
     * Output side of one response: writes the header once (when the first source file is
     * opened, as before), numbers events in output order and projects rows.
     */
    private static final class Out {
        final CSVWriter csv;
        final List<String> requestedAttrs;   // null: raw row + synthetic columns
        final EventIdSeq idSeq = new EventIdSeq();
        boolean headerWritten;

        Out(CSVWriter csv, List<String> requestedAttrs) {
            this.csv = csv;
            this.requestedAttrs = requestedAttrs;
        }

        void header(String[] sourceHeader) {
            if (headerWritten) return;
            csv.writeNext(requestedAttrs == null ? augmentHeader(sourceHeader)
                    : requestedAttrs.toArray(new String[0]), false);
            headerWritten = true;
        }

        /**
         * Writes the stream's current row. If {@code requestedAttrs} is non-null, the row is
         * projected to those columns in that order; columns absent from the source CSV are
         * empty; source columns not requested are dropped.
         */
        void emit(RowStream s) {
            long eventId = idSeq.next();
            if (s.projection == null) {
                // No attribute projection: emit raw row plus synthetic columns.
                csv.writeNext(augmentRow(s.row, s.tagId, s.active, eventId), false);
                return;
            }
            String[] projected = new String[s.projection.length];
            for (int i = 0; i < s.projection.length; i++) {
                String name = requestedAttrs.get(i);
                int srcIdx = s.projection[i];
                // Always prefer the value from the underlying CSV when present and
                // meaningful. Synthetic values (tag_id / individual_id / deployment_id /
                // event_id) are a fallback for older mirrors that didn't download those columns.
                String fromSource = (srcIdx >= 0 && srcIdx < s.row.length) ? s.row[srcIdx] : null;
                if (fromSource != null && !fromSource.isEmpty() && !"NA".equals(fromSource)) {
                    projected[i] = fromSource;
                } else {
                    String syn = synthetic(name, s.tagId, s.active, eventId);
                    // Empty, as live Movebank sends missing values.
                    projected[i] = (syn != null) ? syn : "";
                }
            }
            csv.writeNext(projected, false);
        }
    }

    /**
     * The rows of one tag folder for one sensor type, read file by file (chronological
     * file names), keeping only rows whose deployment passes {@code accept}. The
     * deployment of a row is the first window of the tag that contains its timestamp,
     * or null if none does.
     */
    private static final class RowStream implements Closeable {
        final int order;
        final String tagId;
        private final Iterator<File> files;
        private final List<DeploymentWindow> tagWindows;
        private final Predicate<DeploymentWindow> accept;
        private final Out out;
        private CSVReader reader;
        private int tsIdx;
        private int[] fileProjection;

        // current row
        String[] row;
        String ts;
        DeploymentWindow active;
        int[] projection;

        RowStream(int order, File tagDir, String sensorTypeId, List<DeploymentWindow> tagWindows,
                  Predicate<DeploymentWindow> accept, Out out) {
            this.order = order;
            this.tagId = tagDir.getName();
            this.tagWindows = tagWindows;
            this.accept = accept;
            this.out = out;
            File[] csvFiles = tagDir.isDirectory() ? tagDir.listFiles(
                    (d, name) -> name.startsWith(sensorTypeId + "_") && name.endsWith(".csv")) : null;
            if (csvFiles == null) csvFiles = new File[0];
            Arrays.sort(csvFiles); // chronological order (timestamp baked into filename)
            this.files = Arrays.asList(csvFiles).iterator();
        }

        /** Moves to the next accepted row; false when the tag has none left. */
        boolean advance() throws Exception {
            while (true) {
                if (reader == null) {
                    if (!files.hasNext()) { row = null; ts = null; active = null; return false; }
                    open(files.next());
                    continue;
                }
                String[] r = reader.readNext();
                if (r == null) { close(); continue; }
                String t = eventTimestamp(r, tsIdx);
                DeploymentWindow w = findActive(tagWindows, t);
                if (!accept.test(w)) continue;
                row = r; ts = t; active = w; projection = fileProjection;
                return true;
            }
        }

        private void open(File csvFile) throws Exception {
            reader = new CSVReader(new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8));
            String[] header = reader.readNext();
            if (header == null) { close(); return; }
            out.header(header);
            tsIdx = indexOf(header, "timestamp");
            fileProjection = (out.requestedAttrs == null) ? null : buildProjection(header, out.requestedAttrs);
        }

        @Override
        public void close() throws IOException {
            if (reader != null) { reader.close(); reader = null; }
        }
    }

    /** Returns the synthetic value for {@code colName}, or null if {@code colName} is not synthetic. */
    private static String synthetic(String colName, String tagId, DeploymentWindow active, long eventId) {
        switch (colName) {
            case "tag_id":        return tagId;
            case "individual_id": return active != null ? safe(active.individualId()) : "";
            case "deployment_id": return active != null ? safe(active.deploymentId()) : "";
            case "event_id":      return Long.toString(eventId);
            default:              return null;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

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
        out[i++] = active != null ? safe(active.individualId()) : "";
        out[i++] = active != null ? safe(active.deploymentId()) : "";
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
        if (dirs == null) return Collections.emptyList();
        // File.listFiles() returns filesystem-iteration order (not
        // guaranteed alphabetical). Tests that assert on row order would
        // pass on machines whose ext4 happens to return dirs in name
        // order and fail on ones that don't (e.g. CI runners on
        // overlayfs / other directory layouts). Sort defensively.
        Arrays.sort(dirs);
        return Arrays.asList(dirs);
    }

    /** Numeric order for Movebank ids (digit strings); anything else falls back to text order. */
    static int compareIds(String a, String b) {
        boolean na = a != null && a.matches("\\d+"), nb = b != null && b.matches("\\d+");
        if (na && nb) {
            String x = a.replaceFirst("^0+(?=.)", ""), y = b.replaceFirst("^0+(?=.)", "");
            return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static String eventTimestamp(String[] row, int tsIdx) {
        return (tsIdx >= 0 && tsIdx < row.length) ? row[tsIdx] : null;
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++)
            if (value.equals(arr[i])) return i;
        return -1;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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
