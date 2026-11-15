package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration2.BaseConfiguration;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class DataApeServerTest {
    @Test
    void startsWithPropertiesFiles(@TempDir Path directory) throws Exception {
        Path defaults = directory.resolve("defaults.properties");
        Path overrides = directory.resolve("overrides.properties");
        Files.writeString(defaults, "server.port=4100\nmongo.allowed.hosts=fixture:27017\n");
        Files.writeString(overrides, "server.port=4200\nruntime.version=${sys:java.version}\n");
        try (var config = new DataApeServer.ConfigUtil(defaults + "," + overrides, false);
                var server = new DataApeServer(config)) {
            assertEquals(4200, config.config().getInt("server.port"));
            assertEquals("fixture:27017", config.config().getString("mongo.allowed.hosts"));
            assertEquals(System.getProperty("java.version"), config.config().getString("runtime.version"));
            int port = server.start(0);
            assertEquals(403, request(HttpClient.newHttpClient(), port, "PUT", "/save/example/sample", "{}")
                    .statusCode());
        }
    }

    @Test
    void bindsToTheConfiguredAddress() throws Exception {
        var config = new BaseConfiguration();
        config.setProperty("server.bind.host", "127.0.0.2");
        MongoDriver mongo = mock(MongoDriver.class);
        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config), mongo)) {
            int port = server.start(0);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.2:" + port
                    + "/save/example/sample")).PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
        }
    }

    @Test
    void legacyReadRoutesUseTheSameSessionAndWritesRemainDisabled() throws Exception {
        var config = new BaseConfiguration();
        config.setProperty("session.timeout.interval", 60);
        MongoDriver mongo = mock(MongoDriver.class);
        var db = new MongoDriver.DbDescription();
        db.name = "example";
        db.tables = List.of();
        when(mongo.connect(anyString(), anyString())).thenReturn(db);
        when(mongo.getTables(anyString(), anyString())).thenReturn(db);
        when(mongo.getData(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of("db", "example", "table", "sample", "dataSize", 0));

        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config), mongo)) {
            int port = server.start(0);
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            HttpResponse<String> connected = request(client, port, "POST", "/connect",
                    "{\"cstr\":\"mongodb://host/example\"}");
            assertEquals(200, connected.statusCode());
            assertTrue(connected.body().contains("\"dbName\":\"example\""));
            assertEquals(200, request(client, port, "GET", "/tables/example", "").statusCode());
            HttpResponse<String> data = request(client, port, "GET", "/data/example/sample?page=1", "");
            assertEquals(200, data.statusCode());
            assertTrue(data.body().contains("\"data\":{\""));
            assertEquals(403, request(client, port, "PUT", "/save/example/sample", "{}").statusCode());
            assertEquals(403, request(client, port, "DELETE", "/delete/example/sample/one", "").statusCode());

            var ids = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(mongo).connect(ids.capture(), anyString());
            verify(mongo).getTables(ids.capture(), org.mockito.ArgumentMatchers.eq("example"));
            assertEquals(ids.getAllValues().get(0), ids.getAllValues().get(1));
        }
    }

    @Test
    void rejectsConnectionUriInUrlBeforeCallingMongo() throws Exception {
        var config = new BaseConfiguration();
        MongoDriver mongo = mock(MongoDriver.class);
        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config), mongo)) {
            int port = server.start(0);
            HttpResponse<String> result = request(HttpClient.newHttpClient(), port, "POST",
                    "/connect?cstr=not-a-secret", "{\"cstr\":\"mongodb://host/example\"}");
            assertEquals(400, result.statusCode());
            verify(mongo, never()).connect(anyString(), anyString());
        }
    }

    @Test
    void disconnectRequiresCustomHeaderAndInvalidatesTheSession() throws Exception {
        var config = new BaseConfiguration();
        config.setProperty("dataape.write.enabled", true);
        config.setProperty("dataape.write.origin", "http://ape.test");
        MongoDriver mongo = mock(MongoDriver.class);
        var db = new MongoDriver.DbDescription();
        db.name = "example";
        when(mongo.connect(anyString(), anyString())).thenReturn(db);
        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config), mongo)) {
            int port = server.start(0);
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            var connected = request(client, port, "POST", "/connect", "{\"cstr\":\"mongodb://host/example\"}");
            String token = new ObjectMapper().readTree(connected.body()).get("writeToken").asText();
            assertEquals(403, request(client, port, "POST", "/disconnect", "{}").statusCode());
            verify(mongo, never()).disconnect(anyString());
            var disconnect = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/disconnect"))
                    .header("X-Data-Ape-Disconnect", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(200, client.send(disconnect, HttpResponse.BodyHandlers.ofString()).statusCode());
            verify(mongo).disconnect(anyString());
            assertEquals(403, write(client, port, "{}", "http://ape.test", token).statusCode());
        }
    }

    @Test
    void writesRequireEnabledPolicySameOriginAndSessionToken() throws Exception {
        var config = new BaseConfiguration();
        config.setProperty("dataape.write.enabled", true);
        config.setProperty("dataape.write.origin", "http://ape.test");
        MongoDriver mongo = mock(MongoDriver.class);
        var db = new MongoDriver.DbDescription();
        db.name = "example";
        when(mongo.connect(anyString(), anyString())).thenReturn(db);
        when(mongo.replaceDocument(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config), mongo)) {
            int port = server.start(0);
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            var connected = request(client, port, "POST", "/connect", "{\"cstr\":\"mongodb://host/example\"}");
            String token = new ObjectMapper().readTree(connected.body()).get("writeToken").asText();
            String body = "{\"original\":\"{}\",\"replacement\":\"{}\"}";
            assertEquals(403, write(client, port, body, "http://ape.test", "wrong").statusCode());
            assertEquals(403, write(client, port, body, "http://other.test", token).statusCode());
            assertEquals(403, write(HttpClient.newHttpClient(), port, body, "http://ape.test", token).statusCode());
            verify(mongo, never()).replaceDocument(anyString(), anyString(), anyString(), anyString(), anyString());
            var saved = write(client, port, body, "http://ape.test", token);
            assertEquals(200, saved.statusCode());
            assertTrue(saved.body().contains("\"result\":true"));
            verify(mongo).replaceDocument(anyString(), anyString(), anyString(), anyString(), anyString());

            var reconnected = request(client, port, "POST", "/connect", "{\"cstr\":\"mongodb://host/example\"}");
            String newToken = new ObjectMapper().readTree(reconnected.body()).get("writeToken").asText();
            assertEquals(403, write(client, port, body, "http://ape.test", token).statusCode());
            assertEquals(200, write(client, port, body, "http://ape.test", newToken).statusCode());
        }
    }

    @Test
    void documentRoutesDistinguishObjectIdsFromIdenticalStrings() throws Exception {
        var config = new BaseConfiguration();
        config.setProperty("dataape.write.enabled", true);
        config.setProperty("dataape.write.origin", "http://ape.test");
        MongoDriver mongo = mock(MongoDriver.class);
        var db = new MongoDriver.DbDescription();
        db.name = "example";
        when(mongo.connect(anyString(), anyString())).thenReturn(db);
        var oid = new ObjectId("5ccd00001234567890abcdef");
        when(mongo.getDocument(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("{}");
        when(mongo.deleteDocument(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        try (DataApeServer server = new DataApeServer(new DataApeServer.ConfigUtil(config), mongo)) {
            int port = server.start(0);
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            var connected = request(client, port, "POST", "/connect", "{\"cstr\":\"mongodb://host/example\"}");
            String token = new ObjectMapper().readTree(connected.body()).get("writeToken").asText();
            String path = "/document/example/sample/" + oid.toHexString();
            assertEquals(200, request(client, port, "GET", path, "").statusCode());
            assertEquals(200, request(client, port, "GET", path + "?idType=objectId", "").statusCode());
            assertEquals(400, request(client, port, "GET", path + "?idType=invalid", "").statusCode());
            assertEquals(400, request(client, port, "GET", "/document/example/sample/bad?idType=objectId", "").statusCode());
            assertEquals(400, request(client, port, "GET", path + "?idType=objectId&idType=objectId", "").statusCode());
            verify(mongo).getDocument(anyString(), org.mockito.ArgumentMatchers.eq("example"),
                    org.mockito.ArgumentMatchers.eq("sample"), org.mockito.ArgumentMatchers.eq(oid.toHexString()));
            verify(mongo).getDocument(anyString(), org.mockito.ArgumentMatchers.eq("example"),
                    org.mockito.ArgumentMatchers.eq("sample"), org.mockito.ArgumentMatchers.eq(oid));
            var deletion = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/delete/example/sample/" + oid.toHexString() + "?idType=objectId"))
                    .header("Origin", "http://ape.test").header("X-Data-Ape-CSRF", token).DELETE().build();
            assertEquals(200, client.send(deletion, HttpResponse.BodyHandlers.ofString()).statusCode());
            verify(mongo).deleteDocument(anyString(), org.mockito.ArgumentMatchers.eq("example"),
                    org.mockito.ArgumentMatchers.eq("sample"), org.mockito.ArgumentMatchers.eq(oid));
        }
    }

    private static HttpResponse<String> write(HttpClient client, int port, String body, String origin, String token)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/save/example/sample"))
                .header("Content-Type", "application/json").header("Origin", origin)
                .header("X-Data-Ape-CSRF", token).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> request(HttpClient client, int port, String method, String path, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
