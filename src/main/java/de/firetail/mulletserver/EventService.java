package de.firetail.mulletserver;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
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
            @Value("${mullet.mirror.base-dir}") String mirrorBaseDir,
            MirrorService mirrorService) {
        this.mirrorBaseDir = mirrorBaseDir;
        this.mirrorService = mirrorService;
    }

    public void writeEvents(Map<String, String> params, CSVWriter csv) throws Exception {
        String studyId      = params.get("study_id");
        String sensorTypeId = params.get("sensor_sensor_type_id");
        String tagId        = params.get("tag_id");
        String individualId = params.get("individual_id");
        String deploymentId = params.get("deployment_id");

        File studyDir = new File(mirrorBaseDir, String.format("%012d", Long.parseLong(studyId)));
        if (!studyDir.isDirectory()) return;

        if (individualId != null || deploymentId != null) {
            writeEventsForDeployments(studyId, sensorTypeId, individualId, deploymentId, studyDir, csv);
        } else {
            streamCsvFiles(tagDirs(studyDir, tagId), sensorTypeId, null, csv, false);
        }
    }

    private void writeEventsForDeployments(
            String studyId, String sensorTypeId,
            String individualId, String deploymentId,
            File studyDir, CSVWriter csv) throws Exception {

        List<DeploymentWindow> windows =
                mirrorService.getDeploymentWindows(studyId, individualId, deploymentId);

        // Group windows by tag to avoid reading the same CSV files multiple times
        Map<String, List<DeploymentWindow>> windowsByTag = new LinkedHashMap<>();
        for (DeploymentWindow w : windows)
            windowsByTag.computeIfAbsent(w.tagId(), k -> new ArrayList<>()).add(w);

        boolean headerWritten = false;
        for (Map.Entry<String, List<DeploymentWindow>> entry : windowsByTag.entrySet()) {
            File tagDir = new File(studyDir, entry.getKey());
            List<DeploymentWindow> tagWindows = entry.getValue();
            Predicate<String> filter = ts -> tagWindows.stream().anyMatch(w -> w.contains(ts));
            headerWritten = streamCsvFiles(List.of(tagDir), sensorTypeId, filter, csv, headerWritten);
        }
    }

    // ── Core streaming ─────────────────────────────────────────────────────

    /**
     * Streams all matching CSV files from {@code tagDirs} into {@code csv}.
     * If {@code timestampFilter} is non-null, only rows whose {@code timestamp}
     * column value passes the filter are written.
     *
     * @return true if the CSV header was written (either before or during this call)
     */
    private boolean streamCsvFiles(
            List<File> tagDirs, String sensorTypeId,
            Predicate<String> timestampFilter,
            CSVWriter csv, boolean headerWritten) throws Exception {

        for (File tagDir : tagDirs) {
            if (!tagDir.isDirectory()) continue;
            File[] csvFiles = tagDir.listFiles(
                (d, name) -> name.startsWith(sensorTypeId + "_") && name.endsWith(".csv"));
            if (csvFiles == null || csvFiles.length == 0) continue;
            Arrays.sort(csvFiles); // chronological order (timestamp baked into filename)

            for (File csvFile : csvFiles) {
                try (CSVReader reader = new CSVReader(
                        new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
                    String[] header = reader.readNext();
                    if (header == null) continue;
                    if (!headerWritten) {
                        csv.writeNext(header);
                        headerWritten = true;
                    }
                    int tsIdx = indexOf(header, "timestamp");
                    String[] row;
                    while ((row = reader.readNext()) != null) {
                        if (timestampFilter == null || timestampFilter.test(eventTimestamp(row, tsIdx)))
                            csv.writeNext(row);
                    }
                }
            }
        }
        return headerWritten;
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
}
