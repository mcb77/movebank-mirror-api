# mullet-server

A local Spring Boot server that re-implements the [Movebank REST API (v1)](https://github.com/movebank/movebank-api-doc/blob/master/movebank-api.md), serving data from a local mirror created by the BasicImport/basicmirror tooling.

Designed to be a drop-in replacement for the live Movebank API — point any [mullet-rest-client](https://github.com/mcb77/mullet-rest-client) instance at `http://localhost:8080/movebank` and it works without modification.

---

## Prerequisites

- Java 21
- A local mirror populated by the BasicImport `EventDataSyncLoop` / `EventDataDownloader`

---

## Configuration

`src/main/resources/application.properties`:

```properties
server.port=8080
mullet.mirror.base-dir=/tmp/mullet-mirror
```

Set `mullet.mirror.base-dir` to your mirror root directory.

---

## Running

```bash
./gradlew bootRun
```

Or build a jar and run it:

```bash
./gradlew build
java -jar build/libs/mullet-server-0.0.1-SNAPSHOT.jar --mullet.mirror.base-dir=/path/to/mirror
```

---

## Mirror Directory Layout

The server expects the directory structure written by `EventDataDownloader`:

```
{mirrorBaseDir}/
  000000082207.json               ← StudyJson (12-digit zero-padded study ID)
  000000082207/
    {tagId}/
      state_{sensorTypeId}.json   ← download state (not read by server)
      {sensorTypeId}_{timestamp}.csv        ← catch-up data
      {sensorTypeId}_update_{timestamp}.csv ← incremental updates
```

---

## Supported Entity Types

| `entity_type`     | Parameters                                      | Source                                |
|-------------------|-------------------------------------------------|---------------------------------------|
| `study`           | `id` (optional), `name` (optional)              | All `*.json` files in mirror root     |
| `tag`             | `study_id`                                      | `tags` in study JSON                  |
| `individual`      | `study_id`                                      | `individuals` in study JSON           |
| `deployment`      | `study_id`, `tag_id` (optional), `individual_id` (optional) | `deployments` in study JSON |
| `sensor`          | `tag_study_id`                                  | `sensors` in study JSON               |
| `study_attribute` | `study_id`, `sensor_type_id`                    | `attributesBySensorTypeIDs` in study JSON |
| `event`           | `study_id`, `sensor_sensor_type_id`, `tag_id` (optional) | CSV files in tag subdirectory |
| `tag_type`        | —                                               | Hardcoded (5 known sensor types)      |

---

## Connecting with mullet-rest-client

```java
MulletRestClient client = new MulletRestClient(
    "http://localhost:8080/movebank",
    "any-user",    // auth is not enforced
    "any-password",
    (LicenseChecker) html -> true
);
```

---

## License

GNU Lesser General Public License v2.1 — see [LICENSE](../mullet-rest-client/LICENSE).
