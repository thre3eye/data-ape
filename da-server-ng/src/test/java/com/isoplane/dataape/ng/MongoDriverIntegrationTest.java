package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs only when an explicitly provisioned disposable fixture is supplied.
 * The fixture must contain the synthetic rows described in the module README.
 */
class MongoDriverIntegrationTest {
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
