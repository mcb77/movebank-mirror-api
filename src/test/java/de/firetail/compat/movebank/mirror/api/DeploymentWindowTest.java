package de.firetail.compat.movebank.mirror.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentWindowTest {

    private static final String JAN = "2020-01-01 00:00:00.000";
    private static final String JUN = "2020-06-30 00:00:00.000";
    private static final String MAR = "2020-03-15 12:00:00.000";
    private static final String DEC = "2020-12-31 23:59:59.999";

    @Test
    void timestampInMiddleOfWindowMatches() {
        assertTrue(window(JAN, JUN).contains(MAR));
    }

    @Test
    void timestampAtWindowStartMatches() {
        assertTrue(window(JAN, JUN).contains(JAN));
    }

    @Test
    void timestampAtWindowEndMatches() {
        assertTrue(window(JAN, JUN).contains(JUN));
    }

    @Test
    void timestampBeforeWindowStartDoesNotMatch() {
        assertFalse(window(MAR, JUN).contains(JAN));
    }

    @Test
    void timestampAfterWindowEndDoesNotMatch() {
        assertFalse(window(JAN, JUN).contains(DEC));
    }

    @Test
    void openEndedWindowMatchesTimestampsAfterStart() {
        assertTrue(window(JUN, null).contains(DEC));
    }

    @Test
    void openEndedWindowRejectsTimestampsBeforeStart() {
        assertFalse(window(JUN, null).contains(JAN));
    }

    @Test
    void openStartWindowMatchesTimestampsBeforeEnd() {
        assertTrue(window(null, JUN).contains(JAN));
    }

    @Test
    void fullyUnboundedWindowMatchesAnything() {
        assertTrue(window(null, null).contains(MAR));
    }

    @Test
    void nullTimestampDoesNotMatch() {
        assertFalse(window(JAN, JUN).contains(null));
    }

    @Test
    void blankTimestampDoesNotMatch() {
        assertFalse(window(JAN, JUN).contains(""));
        assertFalse(window(JAN, JUN).contains("   "));
    }

    private DeploymentWindow window(String from, String to) {
        return new DeploymentWindow("tag1", "ind1", "deploy1", from, to);
    }
}
