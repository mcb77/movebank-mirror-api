package de.firetail.compat.movebank.mirror.api;

/**
 * A time window during which a specific tag was deployed on a specific individual.
 * <p>{@code from} and {@code to} are in Movebank timestamp format
 * ({@code yyyy-MM-dd HH:mm:ss.SSS}) and may be null or blank to indicate
 * an unbounded start or end respectively.
 * <p>{@code individualId} and {@code deploymentId} carry the identity of the
 * deployment so events can be enriched with these fields when streaming.
 */
record DeploymentWindow(
        String tagId,
        String individualId,
        String deploymentId,
        String from,
        String to) {

    /**
     * Returns true if the given timestamp falls within this deployment window.
     * Comparison is lexicographic, which is correct for the fixed-width
     * {@code yyyy-MM-dd HH:mm:ss.SSS} format.
     */
    boolean contains(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return false;
        if (from != null && !from.isBlank() && timestamp.compareTo(from) < 0) return false;
        if (to   != null && !to.isBlank()   && timestamp.compareTo(to)   > 0) return false;
        return true;
    }
}
