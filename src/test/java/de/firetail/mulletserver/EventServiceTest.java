package de.firetail.mulletserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EventServiceTest {

    @TempDir Path mirrorDir;

    private MirrorService mirrorService;
    private EventService  eventService;

    private static final String STUDY_ID     = "1";
    private static final String SENSOR_TYPE  = "653";
    private static final String TAG_A        = "101";
    private static final String TAG_B        = "102";
    private static final String INDIVIDUAL_X = "200";
    private static final String INDIVIDUAL_Y = "201";
    private static final String DEPLOY_1     = "301";  // X on tag A, Jan–Jun
    private static final String DEPLOY_2     = "302";  // X on tag B, Jul–open
    private static final String DEPLOY_3     = "303";  // Y on tag A, separate window

    // Timestamps used in tests
    private static final String JAN = "2020-01-15 00:00:00.000";
    private static final String MAR = "2020-03-10 00:00:00.000";
    private static final String AUG = "2020-08-20 00:00:00.000";
    private static final String NOV = "2020-11-05 00:00:00.000";

    @BeforeEach
    void setUp() throws Exception {
        mirrorService = new MirrorService(mirrorDir.toString());
        eventService  = new EventService(mirrorDir.toString(), mirrorService);

        // Study setup:
        //   Deploy 1: individual X, tag A, Jan 1 – Jun 30
        //   Deploy 2: individual X, tag B, Jul 1 – open (no end)
        //   Deploy 3: individual Y, tag A, Oct 1 – Dec 31
        createStudyJson(STUDY_ID, List.of(
            deployment(DEPLOY_1, TAG_A, INDIVIDUAL_X, "2020-01-01 00:00:00.000", "2020-06-30 00:00:00.000"),
            deployment(DEPLOY_2, TAG_B, INDIVIDUAL_X, "2020-07-01 00:00:00.000", ""),
            deployment(DEPLOY_3, TAG_A, INDIVIDUAL_Y, "2020-10-01 00:00:00.000", "2020-12-31 00:00:00.000")
        ));

        // Tag A events: JAN and MAR are in deploy-1 window; AUG and NOV are in deploy-3 window
        createEventCsv(STUDY_ID, TAG_A, SENSOR_TYPE, new String[][]{
            { "timestamp", "location_lat", "sensor_type_id" },
            { JAN, "1.0", SENSOR_TYPE },
            { MAR, "2.0", SENSOR_TYPE },
            { AUG, "3.0", SENSOR_TYPE },
            { NOV, "4.0", SENSOR_TYPE },
        });

        // Tag B events: AUG and NOV are in deploy-2 window (Jul 1 – open)
        createEventCsv(STUDY_ID, TAG_B, SENSOR_TYPE, new String[][]{
            { "timestamp", "location_lat", "sensor_type_id" },
            { AUG, "5.0", SENSOR_TYPE },
            { NOV, "6.0", SENSOR_TYPE },
        });
    }

    // ── individual_id filter ───────────────────────────────────────────────

    @Test
    void individualWithClosedDeploymentReturnsOnlyWindowedEvents() throws Exception {
        // Deploy 1: X on tag A, Jan–Jun → expect JAN and MAR only
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE, "individual_id", INDIVIDUAL_X);

        List<String> timestamps = timestamps(rows);
        // From deploy 1 (tag A): JAN, MAR
        // From deploy 2 (tag B): AUG, NOV
        assertEquals(List.of(JAN, MAR, AUG, NOV), timestamps);
    }

    @Test
    void individualWithOnlyClosedDeploymentExcludesEventsOutsideWindow() throws Exception {
        // Individual Y: only deploy 3 on tag A, Oct 1 – Dec 31 → only NOV qualifies (AUG is before Oct)
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE, "individual_id", INDIVIDUAL_Y);

        List<String> timestamps = timestamps(rows);
        assertEquals(List.of(NOV), timestamps);
        assertFalse(timestamps.contains(JAN));
        assertFalse(timestamps.contains(AUG));
    }

    @Test
    void individualWithOpenEndedDeploymentIncludesAllEventsAfterStart() throws Exception {
        // Deploy 2: X on tag B from Jul 1, no end → AUG and NOV qualify
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE, "individual_id", INDIVIDUAL_X);

        List<String> timestamps = timestamps(rows);
        assertTrue(timestamps.contains(AUG));
        assertTrue(timestamps.contains(NOV));
    }

    // ── deployment_id filter ───────────────────────────────────────────────

    @Test
    void deploymentIdFilterReturnsOnlyThatDeploymentsWindow() throws Exception {
        // Deploy 1: tag A, Jan–Jun → only JAN and MAR
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE, "deployment_id", DEPLOY_1);

        assertEquals(List.of(JAN, MAR), timestamps(rows));
    }

    @Test
    void deploymentIdFilterForOpenEndedDeployment() throws Exception {
        // Deploy 2: tag B, Jul–open → AUG and NOV
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE, "deployment_id", DEPLOY_2);

        assertEquals(List.of(AUG, NOV), timestamps(rows));
    }

    // ── tag_id filter (no time filtering) ─────────────────────────────────

    @Test
    void tagIdFilterReturnsAllEventsForThatTagRegardlessOfDeployment() throws Exception {
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE, "tag_id", TAG_A);

        assertEquals(List.of(JAN, MAR, AUG, NOV), timestamps(rows));
    }

    // ── no filter ─────────────────────────────────────────────────────────

    @Test
    void noFilterReturnsAllEventsAcrossAllTags() throws Exception {
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE);

        assertEquals(6, rows.size());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private List<Map<String, String>> queryEvents(String... pairs) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) params.put(pairs[i], pairs[i + 1]);
        StringWriter sw = new StringWriter();
        try (CSVWriter csv = new CSVWriter(sw)) {
            eventService.writeEvents(params, csv);
        }
        return parseCsv(sw.toString());
    }

    private List<String> timestamps(List<Map<String, String>> rows) {
        return rows.stream().map(r -> r.get("timestamp")).toList();
    }

    private List<Map<String, String>> parseCsv(String csv) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return rows;
            String[] row;
            while ((row = reader.readNext()) != null) {
                Map<String, String> map = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++)
                    map.put(header[i], i < row.length ? row[i] : "");
                rows.add(map);
            }
        }
        return rows;
    }

    private void createStudyJson(String studyId, List<Map<String, String>> deployments)
            throws IOException {
        StudyJson s = new StudyJson();
        s.study = Map.of("id", studyId, "name", "Test Study");
        s.deployments = deployments;
        s.tags = Collections.emptyList();
        s.individuals = Collections.emptyList();
        s.sensors = Collections.emptyList();
        s.sensorTypeIds = Set.of(SENSOR_TYPE);
        s.attributesBySensorTypeIDs = Map.of(SENSOR_TYPE, List.of("location_lat"));
        Path file = mirrorDir.resolve(String.format("%012d", Long.parseLong(studyId)) + ".json");
        new ObjectMapper().writeValue(file.toFile(), s);
    }

    private void createEventCsv(String studyId, String tagId, String sensorTypeId, String[][] rows)
            throws IOException {
        Path tagDir = mirrorDir
            .resolve(String.format("%012d", Long.parseLong(studyId)))
            .resolve(tagId);
        Files.createDirectories(tagDir);
        Path csvFile = tagDir.resolve(sensorTypeId + "_20260101000000.csv");
        try (CSVWriter writer = new CSVWriter(new FileWriter(csvFile.toFile()))) {
            for (String[] row : rows) writer.writeNext(row);
        }
    }

    private Map<String, String> deployment(
            String id, String tagId, String individualId, String from, String to) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("tag_id", tagId);
        d.put("individual_id", individualId);
        d.put("deploy_on_timestamp", from);
        d.put("deploy_off_timestamp", to);
        return d;
    }
}
