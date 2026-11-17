package de.firetail.compat.movebank.mirror.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.mirror.StudyJson;
import de.firetail.compat.movebank.mirror.reader.MirrorReader;
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
        MirrorReader reader = new MirrorReader(mirrorDir.toFile());
        mirrorService = new MirrorService(reader);
        eventService  = new EventService(reader);

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

        // All four events of tag A, in live order: X's (JAN, MAR), Y's (NOV), then the
        // event outside every deployment (AUG, between X's end and Y's start) last.
        assertEquals(List.of(JAN, MAR, NOV, AUG), timestamps(rows));
    }

    // ── no filter ─────────────────────────────────────────────────────────

    @Test
    void noFilterReturnsAllEventsAcrossAllTags() throws Exception {
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE);

        assertEquals(6, rows.size());
    }

    // ── no sensor_type_id (move::getMovebankData compatibility) ───────────

    @Test
    void missingSensorTypeIdAggregatesAcrossStudysSensorTypes() throws Exception {
        // move::getMovebankData(study) issues entity_type=event with no
        // sensor_sensor_type_id; the server must enumerate sensor types from
        // the study's metadata and stream every matching CSV.
        List<Map<String, String>> rows = queryEvents("study_id", STUDY_ID);

        // Same payload as the explicit-sensor-type case — the fixture only
        // has one sensor type, so the union equals the single-type result.
        assertEquals(6, rows.size());
        // Live order: X (tag A Jan-Jun, then tag B from Jul), Y (tag A Oct-Dec),
        // then tag A's undeployed AUG.
        assertEquals(List.of(JAN, MAR, AUG, NOV, NOV, AUG), timestamps(rows));
    }

    @Test
    void missingSensorTypeIdAndUnknownStudyReturnsEmpty() throws Exception {
        List<Map<String, String>> rows = queryEvents("study_id", "999999999");
        assertEquals(0, rows.size());
    }

    @Test
    void individualIdAcceptsCommaSeparatedList() throws Exception {
        // move::getMovebankData sends individual_id=<csv>. Each id in the list
        // should select that individual's events; the union is returned.
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID,
            "sensor_sensor_type_id", SENSOR_TYPE,
            "individual_id", INDIVIDUAL_X + "," + INDIVIDUAL_Y);

        // Live order: individual, then timestamp across that individual's tags.
        //   X: tag A Jan–Jun (JAN, MAR), tag B Jul–open (AUG, NOV)
        //   Y: tag A Oct–Dec (NOV)
        // Tag A's AUG falls in no selected deployment and is not sent.
        List<String> ts = timestamps(rows);
        assertEquals(List.of(JAN, MAR, AUG, NOV, NOV), ts);
    }

    @Test
    void emptySensorTypeIdIsTreatedAsAbsent() throws Exception {
        // move sends sensor_type_id= (empty); the server must enumerate all sensor
        // types in the study and aggregate, same as if the param were missing.
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_type_id", "");
        assertEquals(6, rows.size());
    }

    @Test
    void sensorTypeIdAliasIsAccepted() throws Exception {
        // The /event endpoint historically uses sensor_sensor_type_id, but move
        // and some other clients use the simpler sensor_type_id. Both should work.
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_type_id", SENSOR_TYPE);
        assertEquals(6, rows.size());
    }

    @Test
    void attributesParameterProjectsAndOrdersColumns() throws Exception {
        // Request a subset, in a different order from the source. The response
        // header and rows should match the requested order exactly.
        StringWriter sw = new StringWriter();
        try (CSVWriter csv = new CSVWriter(sw)) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("study_id", STUDY_ID);
            p.put("sensor_sensor_type_id", SENSOR_TYPE);
            p.put("attributes", "location_lat,timestamp");
            eventService.writeEvents(p, csv);
        }
        try (CSVReader r = new CSVReader(new StringReader(sw.toString()))) {
            assertArrayEquals(new String[]{"location_lat", "timestamp"}, r.readNext());
            assertArrayEquals(new String[]{"1.0", JAN}, r.readNext());
            assertArrayEquals(new String[]{"2.0", MAR}, r.readNext());
        }
    }

    @Test
    void attributesEqualsAllSkipsProjection() throws Exception {
        // move2::movebank_download_study sends `attributes=all`. The Movebank
        // live API treats that as "every column from the source"; we must do
        // the same, not project to a one-column "all" filter.
        StringWriter sw = new StringWriter();
        try (CSVWriter csv = new CSVWriter(sw)) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("study_id", STUDY_ID);
            p.put("sensor_sensor_type_id", SENSOR_TYPE);
            p.put("attributes", "all");
            eventService.writeEvents(p, csv);
        }
        try (CSVReader r = new CSVReader(new StringReader(sw.toString()))) {
            String[] header = r.readNext();
            // Source CSV columns + the four synthetic ones.
            assertTrue(java.util.Arrays.asList(header).contains("timestamp"));
            assertTrue(java.util.Arrays.asList(header).contains("location_lat"));
            assertTrue(java.util.Arrays.asList(header).contains("event_id"));
            assertTrue(java.util.Arrays.asList(header).contains("tag_id"));
            int rows = 0;
            while (r.readNext() != null) rows++;
            assertEquals(6, rows);
        }
    }

    @Test
    void attributesProjectionEmitsEmptyForUnknownColumns() throws Exception {
        // A column the source CSV doesn't have is empty, as live Movebank
        // sends missing values (measured 2026-09-29).
        StringWriter sw = new StringWriter();
        try (CSVWriter csv = new CSVWriter(sw)) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("study_id", STUDY_ID);
            p.put("sensor_sensor_type_id", SENSOR_TYPE);
            p.put("attributes", "timestamp,does_not_exist");
            eventService.writeEvents(p, csv);
        }
        try (CSVReader r = new CSVReader(new StringReader(sw.toString()))) {
            assertArrayEquals(new String[]{"timestamp", "does_not_exist"}, r.readNext());
            assertArrayEquals(new String[]{JAN, ""}, r.readNext());
        }
    }

    // ── live row order: individual, then timestamp ───────────────────────

    @Test
    void noFilterOrdersByIndividualThenTimestampWithUndeployedLast() throws Exception {
        List<Map<String, String>> rows = queryEvents(
            "study_id", STUDY_ID, "sensor_sensor_type_id", SENSOR_TYPE);

        List<String> got = rows.stream()
            .map(r -> r.get("individual_id") + "|" + r.get("tag_id") + "|" + r.get("timestamp")).toList();
        assertEquals(List.of(
            INDIVIDUAL_X + "|" + TAG_A + "|" + JAN,
            INDIVIDUAL_X + "|" + TAG_A + "|" + MAR,
            INDIVIDUAL_X + "|" + TAG_B + "|" + AUG,
            INDIVIDUAL_X + "|" + TAG_B + "|" + NOV,
            INDIVIDUAL_Y + "|" + TAG_A + "|" + NOV,
            "|" + TAG_A + "|" + AUG), got);
        // event_id numbers the rows in output order
        assertEquals(List.of("1", "2", "3", "4", "5", "6"),
            rows.stream().map(r -> r.get("event_id")).toList());
    }

    @Test
    void overlappingTagsOnOneIndividualAreMergedByTimestamp() throws Exception {
        // Individual 900 carries two tags at once. The tag with the LOWER id (701) was
        // deployed later, and the earlier tag (702) has no deploy_on (open towards the
        // past). Sorting tag folders by id would send 701's rows first; live merges by time.
        String study = "2";
        createStudyJson(study, List.of(
            deployment("801", "701", "900", "2020-03-01 00:00:00.000", ""),
            deployment("802", "702", "900", "", "")));
        createEventCsv(study, "701", SENSOR_TYPE, new String[][]{
            { "timestamp", "location_lat" },
            { "2020-03-05 00:00:00.000", "1" },
            { "2020-03-07 00:00:00.000", "3" },
        });
        createEventCsv(study, "702", SENSOR_TYPE, new String[][]{
            { "timestamp", "location_lat" },
            { "2019-12-31 00:00:00.000", "0" },
            { "2020-03-06 00:00:00.000", "2" },
            { "2020-03-08 00:00:00.000", "4" },
        });

        List<Map<String, String>> rows = queryEvents(
            "study_id", study, "sensor_sensor_type_id", SENSOR_TYPE);

        assertEquals(List.of("0", "1", "2", "3", "4"),
            rows.stream().map(r -> r.get("location_lat")).toList());
        assertTrue(rows.stream().allMatch(r -> "900".equals(r.get("individual_id"))),
            "blank deploy_on is an open start: the 2019 event belongs to 900 too");
    }

    @Test
    void tagMovedBetweenIndividualsIsSplitByIndividual() throws Exception {
        // Tag 711 is on individual 20 first, then on individual 3. Individual 3 sorts
        // first (numeric ids), so its later events come before 20's earlier ones.
        String study = "3";
        createStudyJson(study, List.of(
            deployment("811", "711", "20", "2020-01-01 00:00:00.000", "2020-01-31 00:00:00.000"),
            deployment("812", "711", "3",  "2020-02-01 00:00:00.000", "")));
        createEventCsv(study, "711", SENSOR_TYPE, new String[][]{
            { "timestamp", "location_lat" },
            { "2020-01-10 00:00:00.000", "a" },
            { "2020-02-10 00:00:00.000", "b" },
        });

        List<Map<String, String>> rows = queryEvents(
            "study_id", study, "sensor_sensor_type_id", SENSOR_TYPE);

        assertEquals(List.of("3|b", "20|a"),
            rows.stream().map(r -> r.get("individual_id") + "|" + r.get("location_lat")).toList());
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
