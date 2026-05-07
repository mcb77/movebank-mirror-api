package de.firetail.compat.movebank.mirror.api;

import com.opencsv.CSVReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the Spring application against a tmp mirror directory, then exercises
 * {@code /movebank/service/direct-read} via HTTP. Uses {@code entity_type=tag_type}
 * because it has hardcoded data and needs no on-disk fixture — proves the
 * Spring wiring and controller routing without coupling to file format details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectReadIntegrationTest {

    @TempDir static Path tempMirror;

    @DynamicPropertySource
    static void mirrorDir(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(tempMirror);
        registry.add("movebank.mirror.base-dir", tempMirror::toString);
    }

    @Autowired TestRestTemplate rest;

    @Test
    void tagTypeReturnsHardcodedRows() throws Exception {
        String csv = rest.getForObject("/movebank/service/direct-read?entity_type=tag_type", String.class);
        List<String[]> rows = parseCsv(csv);

        // Header matches the live Movebank /tag_type schema exactly — five
        // columns in alphabetical order (description, external_id, id,
        // is_location_sensor, name). Order verified by curl against live.
        assertEquals("description", rows.get(0)[0]);
        assertEquals("external_id", rows.get(0)[1]);
        assertEquals("id", rows.get(0)[2]);
        assertEquals("is_location_sensor", rows.get(0)[3]);
        assertEquals("name", rows.get(0)[4]);
        assertEquals(10, rows.size(), "1 header + 9 hardcoded sensor types");
        // GPS row: id=653, external_id=gps, is_location_sensor=true, name=GPS.
        assertTrue(rows.stream().anyMatch(r ->
                "gps".equals(r[1]) && "653".equals(r[2]) && "true".equals(r[3]) && "GPS".equals(r[4])));
    }

    @Test
    void unknownEntityTypeReturns400() {
        var resp = rest.getForEntity(
                "/movebank/service/direct-read?entity_type=does-not-exist", String.class);
        assertEquals(400, resp.getStatusCode().value());
    }

    private static List<String[]> parseCsv(String csv) throws Exception {
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            List<String[]> rows = new ArrayList<>();
            String[] row;
            while ((row = reader.readNext()) != null) rows.add(row);
            return rows;
        }
    }
}
