package de.firetail.mulletserver;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class StudyJson {
    public Map<String, String> study;
    public List<Map<String, String>> sensors;
    public List<Map<String, String>> tags;
    public List<Map<String, String>> deployments;
    public List<Map<String, String>> individuals;
    public Set<String> sensorTypeIds;
    public Map<String, List<String>> attributesBySensorTypeIDs;
}
