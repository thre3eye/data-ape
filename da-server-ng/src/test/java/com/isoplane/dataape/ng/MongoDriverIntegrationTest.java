package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration2.BaseConfiguration;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClients;

/**
 * Runs only when an explicitly provisioned disposable fixture is supplied.
 * The fixture must contain the synthetic rows described in the module README.
 */
class MongoDriverIntegrationTest {
    @Test
    void archivesFirstOnAnIsolatedStandaloneAndRejectsArchiveConflicts() {
        String host = System.getenv("DA_NG_TEST_MONGO_STANDALONE_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "No disposable standalone fixture configured");
        String uri = "mongodb://" + host + "/da_ng_standalone_fixture?directConnection=true";
        BaseConfiguration config = new BaseConfiguration();
        config.setProperty("mongo.allowed.hosts", List.of(host));
        config.setProperty("mongo.deleted.postfix", "_deleted");
        config.setProperty("dataape.delete.mode", "archive-first");
        try (var fixture = MongoClients.create(uri); var driver = new MongoDriver(config)) {
            var db = fixture.getDatabase("da_ng_standalone_fixture");
            try {
                var source = db.getCollection("sample");
                var archive = db.getCollection("sample_deleted");
                var id = new ObjectId("5ccd00001234567890abcdef");
                source.insertMany(List.of(new Document("_id", id).append("value", 12L),
                        new Document("_id", "conflict").append("value", "source")));
                archive.insertOne(new Document("_id", "conflict").append("value", "old archive"));
                driver.updateSession("standalone", Long.MAX_VALUE);
                driver.connect("standalone", uri);
                assertTrue(driver.deleteDocument("standalone", "da_ng_standalone_fixture", "sample", id));
                assertNull(source.find(new Document("_id", id)).first());
                assertEquals(12L, archive.find(new Document("_id", id)).first().getLong("value"));
                var error = assertThrows(MongoDriver.DAArchiveException.class,
                        () -> driver.deleteDocument("standalone", "da_ng_standalone_fixture", "sample", "conflict"));
                assertTrue(error.getMessage().contains("already contains"));
                assertEquals("source", source.find(new Document("_id", "conflict")).first().getString("value"));
                assertEquals("old archive", archive.find(new Document("_id", "conflict")).first().getString("value"));
                assertFalse(driver.deleteDocument("standalone", "da_ng_standalone_fixture", "sample", "missing"));
            } finally {
                db.drop();
            }
        }
    }

    @Test
    void replacesOnlyUnchangedDocumentsAndArchivesDeletesAtomically() {
        String host = System.getenv("DA_NG_TEST_MONGO_WRITE_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "No disposable write fixture configured");
        String uri = "mongodb://" + host + "/da_ng_write_fixture?directConnection=true";
        BaseConfiguration config = new BaseConfiguration();
        config.setProperty("mongo.allowed.hosts", List.of(host));
        config.setProperty("mongo.deleted.postfix", "_deleted");
        try (var fixture = MongoClients.create(uri); var driver = new MongoDriver(config)) {
            var db = fixture.getDatabase("da_ng_write_fixture");
            try {
                var source = db.getCollection("sample");
                var archive = db.getCollection("sample_deleted");
                var objectId = new ObjectId("5ccd00001234567890abcdef");
                source.insertMany(List.of(new Document("_id", "one").append("long", 4294967298L).append("smallLong", 12L)
                        .append("nested", new Document("value", "original")),
                        new Document("_id", "two").append("value", "keep"),
                        new Document("_id", objectId).append("value", "object-id"),
                        new Document("_id", objectId.toHexString()).append("value", "string-id"),
                        new Document("_id", "ambiguous").append("literal",
                                new Document("$date", "2020-01-01T00:00:00Z"))));
                driver.updateSession("integration", Long.MAX_VALUE);
                driver.connect("integration", uri);
                var page = driver.getData("integration", "da_ng_write_fixture", "sample", Map.of());
                @SuppressWarnings("unchecked")
                var columns = (List<List<Object>>) page.get("data");
                assertTrue(columns.stream().anyMatch(column -> column.contains(Map.of("$oid", objectId.toHexString()))));
                String objectOriginal = driver.getDocument("integration", "da_ng_write_fixture", "sample", objectId);
                assertEquals(objectId, Document.parse(objectOriginal).getObjectId("_id"));
                String objectEdited = Document.parse(objectOriginal).append("value", "edited").toJson();
                assertTrue(driver.replaceDocument("integration", "da_ng_write_fixture", "sample",
                        objectOriginal, objectEdited));
                assertFalse(driver.replaceDocument("integration", "da_ng_write_fixture", "sample",
                        objectOriginal, objectEdited));
                assertThrows(IllegalArgumentException.class,
                        () -> driver.replaceDocument("integration", "da_ng_write_fixture", "sample",
                                objectEdited, new Document("_id", objectId.toHexString()).toJson()));
                assertEquals("string-id", source.find(new Document("_id", objectId.toHexString())).first()
                        .getString("value"));
                assertTrue(driver.deleteDocument("integration", "da_ng_write_fixture", "sample", objectId));
                assertNull(source.find(new Document("_id", objectId)).first());
                assertEquals("edited", archive.find(new Document("_id", objectId)).first().getString("value"));
                assertNotNull(source.find(new Document("_id", objectId.toHexString())).first());
                assertThrows(IllegalArgumentException.class,
                        () -> driver.getDocument("integration", "da_ng_write_fixture", "sample", "ambiguous"));
                String original = driver.getDocument("integration", "da_ng_write_fixture", "sample", "one");
                Document replacement = Document.parse(original).append("name", "edited");
                String edited = replacement.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build());
                assertTrue(driver.replaceDocument("integration", "da_ng_write_fixture", "sample",
                        original, edited));
                assertEquals(4294967298L, source.find(new Document("_id", "one")).first().getLong("long"));
                assertEquals(12L, source.find(new Document("_id", "one")).first().getLong("smallLong"));
                assertFalse(driver.replaceDocument("integration", "da_ng_write_fixture", "sample",
                        original, edited));
                assertThrows(IllegalArgumentException.class,
                        () -> driver.replaceDocument("integration", "da_ng_write_fixture", "sample",
                                original, new Document("_id", "other").toJson()));
                assertTrue(driver.deleteDocument("integration", "da_ng_write_fixture", "sample", "one"));
                assertNull(source.find(new Document("_id", "one")).first());
                assertEquals(4294967298L, archive.find(new Document("_id", "one")).first().getLong("long"));
                archive.insertOne(new Document("_id", "two"));
                assertThrows(MongoDriver.DAArchiveException.class,
                        () -> driver.deleteDocument("integration", "da_ng_write_fixture", "sample", "two"));
                assertNotNull(source.find(new Document("_id", "two")).first());
                assertFalse(driver.deleteDocument("integration", "da_ng_write_fixture", "sample", "missing"));
            } finally {
                db.drop();
            }
        }
    }

    @Test
    void readsFilteredPagesWithoutReturningUnrelatedDocuments() {
        String host = System.getenv("DA_NG_TEST_MONGO_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "No disposable Mongo fixture configured");
        BaseConfiguration config = new BaseConfiguration();
        config.setProperty("mongo.allowed.hosts", List.of(host));
        config.setProperty("mongo.field.scan.limit", 100);
        try (MongoDriver driver = new MongoDriver(config)) {
            driver.updateSession("integration", Long.MAX_VALUE);
            var db = driver.connect("integration", "mongodb://" + host + "/da_ng_fixture?directConnection=true");
            assertEquals("da_ng_fixture", db.name);
            var select = "[{\"key\":\"score\",\"op\":\"gte\",\"val\":15}]";
            Map<String, Object> page = driver.getData("integration", "da_ng_fixture", "sample",
                    Map.of("select", select, "count_total", "false"));
            assertEquals(-1L, page.get("querySize"));
            assertEquals(1, page.get("dataSize"));
            assertEquals(List.of(List.of("two"), List.of("Beta"), List.of(20)), page.get("data"));

            var noMatches = "[{\"key\":\"score\",\"op\":\"gt\",\"val\":100}]";
            Map<String, Object> empty = driver.getData("integration", "da_ng_fixture", "sample",
                    Map.of("select", noMatches));
            assertEquals(0L, empty.get("querySize"));
            assertEquals(0, empty.get("dataSize"));
            assertEquals(List.of(List.of(), List.of(), List.of()), empty.get("data"));
        }
    }

    @Test
    void legacyHttpRoutesReadTheSyntheticFixture() throws Exception {
        String host = System.getenv("DA_NG_TEST_MONGO_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "No disposable Mongo fixture configured");
        BaseConfiguration config = new BaseConfiguration();
        config.setProperty("mongo.allowed.hosts", List.of(host));
        config.setProperty("mongo.field.scan.limit", 100);
        ObjectMapper json = new ObjectMapper();
        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config))) {
            int port = server.start(0);
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            String connection = json.writeValueAsString(Map.of("cstr",
                    "mongodb://" + host + "/da_ng_fixture?directConnection=true"));
            HttpRequest connect = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/connect"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(connection)).build();
            var response = client.send(connect, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("da_ng_fixture", json.readTree(response.body()).get("dbName").asText());

            String filter = URLEncoder.encode("[{\"key\":\"score\",\"op\":\"gte\",\"val\":15}]",
                    StandardCharsets.UTF_8);
            HttpRequest get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/data/da_ng_fixture/sample?count_total=false&select=" + filter)).GET().build();
            response = client.send(get, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var data = json.readTree(response.body()).get("data");
            assertEquals(-1, data.get("querySize").asInt());
            assertEquals(1, data.get("dataSize").asInt());
            assertEquals(20, data.get("data").get(2).get(0).asInt());
        }
    }
}
