package com.isoplane.dataape.ng;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isoplane.dataape.ng.MongoDriver.DAArchiveException;
import com.isoplane.dataape.ng.MongoDriver.DADatabaseConnectionException;
import com.mongodb.MongoException;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;

/**
 * Legacy-shaped Data-Ape HTTP server with writes disabled by default.
 * The server binds to loopback by default because application authentication,
 * TLS and production target enforcement are not yet implemented.
 */
public class DataApeServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DataApeServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConfigUtil config;
    private final MongoDriver mongo;
    private Javalin server;

    /**
     * Creates the server and its session-scoped MongoDB adapter.
     *
     * @param config validated configuration source
     */
    public DataApeServer(ConfigUtil config) {
        this(config, new MongoDriver(config::config, com.mongodb.client.MongoClients::create));
    }

    DataApeServer(ConfigUtil config, MongoDriver mongo) {
        this.config = config;
        this.mongo = mongo;
    }

    /**
     * Loads one or more comma-separated property files and runs the local server.
     *
     * @param args configuration path list
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a comma-separated properties path");
        }
        ConfigUtil config = new ConfigUtil(args[0], true);
        DataApeServer server = new DataApeServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "data-ape-shutdown"));
        server.start(config.config().getInt("server.port", 4100));
    }

    /**
     * Starts the HTTP server; port zero is available for isolated tests.
     * Loopback is the default; container deployments must explicitly opt in to
     * a wider bind address and enforce their own network boundary.
     *
     * @param port requested loopback port
     * @return bound port
     */
    public synchronized int start(int port) {
        if (server != null) {
            return server.port();
        }
        String host = config.config().getString("server.bind.host", "127.0.0.1");
        if (host.isBlank()) {
            throw new IllegalArgumentException("server.bind.host must not be blank");
        }
        server = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(JSON, false));
            cfg.http.maxRequestSize = 1024 * 1024;
            addStaticFiles(cfg.staticFiles, "server.static.files", "server.static.path");
            addStaticFiles(cfg.staticFiles, "server.config.files", "server.config.path");
            cfg.routes.before(this::updateSession);
            cfg.routes.after(ctx -> {
                ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                ctx.header("Pragma", "no-cache");
                ctx.header("Expires", "0");
            });
            cfg.routes.post("/connect", this::connect);
            cfg.routes.get("/tables/{database}", ctx -> {
                var db = mongo.getTables(sessionId(ctx), ctx.pathParam("database"));
                ctx.json(databaseDescription(db));
            });
            cfg.routes.get("/data/{database}/{table}", ctx -> {
                Map<String, String> params = new HashMap<>();
                ctx.queryParamMap().forEach((key, values) -> {
                    if (values.size() != 1) {
                        throw new IllegalArgumentException("Duplicate query parameter");
                    }
                    params.put(key, values.get(0));
                });
                ctx.json(Map.of("data", mongo.getData(sessionId(ctx), ctx.pathParam("database"),
                        ctx.pathParam("table"), params)));
            });
            cfg.routes.get("/document/{database}/{table}/{id}", ctx -> {
                String document = mongo.getDocument(sessionId(ctx), ctx.pathParam("database"),
                        ctx.pathParam("table"), documentId(ctx));
                if (document == null) {
                    ctx.status(404).json(Map.of("msg", "Document not found", "id", 0));
                } else {
                    ctx.json(Map.of("document", document));
                }
            });
            cfg.routes.put("/save/{database}/{table}", this::save);
            cfg.routes.delete("/delete/{database}/{table}/{id}", this::delete);
            cfg.routes.exception(IllegalArgumentException.class, (ex, ctx) ->
                    ctx.status(400).json(Map.of("msg", ex.getMessage(), "id", 0)));
            cfg.routes.exception(DADatabaseConnectionException.class, (ex, ctx) ->
                    ctx.status(401).json(Map.of("msg", "DB not connected", "id", -1)));
            cfg.routes.exception(DAArchiveException.class, (ex, ctx) -> {
                LOG.warn("Archive/delete failed: {}", ex.getMessage());
                ctx.status(503).json(Map.of("msg", ex.getMessage(), "id", 0));
            });
            cfg.routes.exception(MongoException.class, (ex, ctx) -> {
                LOG.warn("MongoDB request failed");
                ctx.status(503).json(Map.of("msg", "Database request failed", "id", -1));
            });
            cfg.routes.exception(Exception.class, (ex, ctx) -> {
                LOG.error("Unexpected request failure: {}", ex.getClass().getSimpleName());
                ctx.status(500).json(Map.of("msg", "Internal server error", "id", 0));
            });
        });
        server.start(host, port);
        return server.port();
    }

    private void addStaticFiles(io.javalin.config.StaticFilesConfig files, String directoryKey, String pathKey) {
        String directory = config.config().getString(directoryKey);
        if (directory != null && !directory.isBlank()) {
            files.add(file -> {
                file.directory = directory;
                file.hostedPath = config.config().getString(pathKey, "/");
                file.location = Location.EXTERNAL;
            });
        }
    }

    private void updateSession(Context ctx) {
        var session = ctx.req().getSession();
        int timeout = config.config().getInt("session.timeout.interval", 900);
        if (timeout <= 0) {
            throw new IllegalArgumentException("session.timeout.interval must be positive");
        }
        session.setMaxInactiveInterval(timeout);
        mongo.updateSession(session.getId(), System.currentTimeMillis() + timeout * 1000L);
    }

    private void connect(Context ctx) {
        if (ctx.queryParam("cstr") != null) {
            throw new IllegalArgumentException("Connection URI must not be in the URL");
        }
        if (ctx.contentType() == null || !ctx.contentType().toLowerCase(java.util.Locale.ROOT)
                .startsWith("application/json")) {
            throw new IllegalArgumentException("JSON content type required");
        }
        final JsonNode body;
        try {
            body = JSON.readTree(ctx.body());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
        JsonNode cstr = body == null ? null : body.get("cstr");
        if (cstr == null || !cstr.isTextual()) {
            throw new IllegalArgumentException("Connection URI is required");
        }
        var db = mongo.connect(sessionId(ctx), cstr.textValue());
        var response = databaseDescription(db);
        if (writesEnabled()) {
            String token = UUID.randomUUID().toString();
            ctx.req().getSession().setAttribute("dataape.csrf", token);
            response.put("writeToken", token);
        } else {
            ctx.req().getSession().removeAttribute("dataape.csrf");
        }
        ctx.json(response);
    }

    private Map<String, Object> databaseDescription(MongoDriver.DbDescription db) {
        Map<String, Object> response = new HashMap<>();
        response.put("dbName", db.name);
        response.put("tables", db.tables);
        response.put("decimaldigits", config.config().getInt("ui.decimaldigits", 2));
        return response;
    }

    private static String sessionId(Context ctx) {
        return ctx.req().getSession().getId();
    }

    private void save(Context ctx) {
        if (!authorizeWrite(ctx)) {
            return;
        }
        final JsonNode body;
        try {
            body = JSON.readTree(ctx.body());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
        if (body == null || !body.path("original").isTextual() || !body.path("replacement").isTextual()) {
            throw new IllegalArgumentException("Original and replacement document JSON are required");
        }
        boolean result = mongo.replaceDocument(sessionId(ctx), ctx.pathParam("database"),
                ctx.pathParam("table"), body.path("original").asText(), body.path("replacement").asText());
        ctx.json(Map.of("result", result));
    }

    private void delete(Context ctx) {
        if (!authorizeWrite(ctx)) {
            return;
        }
        boolean result = mongo.deleteDocument(sessionId(ctx), ctx.pathParam("database"),
                ctx.pathParam("table"), documentId(ctx));
        ctx.json(Map.of("result", result));
    }

    private static Object documentId(Context ctx) {
        var types = ctx.queryParams("idType");
        if (types.isEmpty()) {
            return ctx.pathParam("id");
        }
        if (types.size() != 1 || !"objectId".equals(types.get(0)) || !ObjectId.isValid(ctx.pathParam("id"))) {
            throw new IllegalArgumentException("Invalid document identifier type or ObjectId");
        }
        return new ObjectId(ctx.pathParam("id"));
    }

    private boolean authorizeWrite(Context ctx) {
        if (!writesEnabled()) {
            ctx.status(403).json(Map.of("msg", "Server is read-only", "id", 0));
            return false;
        }
        String origin = System.getProperty("dataape.write.origin",
                config.config().getString("dataape.write.origin", ""));
        Object expected = ctx.req().getSession().getAttribute("dataape.csrf");
        String supplied = ctx.header("X-Data-Ape-CSRF");
        if (origin.isBlank() || !origin.equals(ctx.header("Origin"))
                || !(expected instanceof String token) || supplied == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            ctx.status(403).json(Map.of("msg", "Write authorization failed", "id", 0));
            return false;
        }
        return true;
    }

    private boolean writesEnabled() {
        String property = System.getProperty("dataape.write.enabled");
        if (property == null) {
            return config.config().getBoolean("dataape.write.enabled", false);
        }
        if (!"true".equalsIgnoreCase(property) && !"false".equalsIgnoreCase(property)) {
            throw new IllegalArgumentException("dataape.write.enabled must be true or false");
        }
        return Boolean.parseBoolean(property);
    }

    /** Stops HTTP, closes session clients and stops configuration reload. */
    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop();
            server = null;
        }
        mongo.close();
        config.close();
    }

    /**
     * Loads the same ordered property-file list as the legacy server.
     * Reload publishes a complete configuration snapshot so requests never see a
     * partially loaded set of files.
     */
    public static class ConfigUtil implements AutoCloseable {
        private final String[] paths;
        private final ScheduledExecutorService scheduler;
        private volatile Configuration config;

        /**
         * Loads configuration and optionally starts periodic reload.
         *
         * @param propertiesPath comma-separated property files, later files take precedence
         * @param reloading whether to reload every 30 seconds
         */
        public ConfigUtil(String propertiesPath, boolean reloading) {
            if (propertiesPath == null || propertiesPath.isBlank()) {
                throw new IllegalArgumentException("Configuration path is required");
            }
            paths = propertiesPath.split(",");
            config = loadConfiguration();
            if (reloading) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        config = loadConfiguration();
                    } catch (IllegalStateException ex) {
                        LOG.error("Configuration reload failed; retaining previous configuration");
                    }
                }, 30, 30, TimeUnit.SECONDS);
            } else {
                scheduler = null;
            }
        }

        ConfigUtil(Configuration config) {
            this.paths = new String[0];
            this.scheduler = null;
            this.config = config;
        }

        private Configuration loadConfiguration() {
            CompositeConfiguration result = new CompositeConfiguration();
            try {
                for (String path : paths) {
                    result.addConfigurationFirst(new Configurations().properties(new File(path.trim())));
                }
                return result;
            } catch (ConfigurationException ex) {
                throw new IllegalStateException("Unable to load configuration", ex);
            }
        }

        /** Returns the current complete configuration snapshot. */
        public Configuration config() {
            return config;
        }

        /** Stops the optional configuration reload task. */
        @Override
        public void close() {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }
}
