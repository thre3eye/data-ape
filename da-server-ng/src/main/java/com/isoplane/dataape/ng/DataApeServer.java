package com.isoplane.dataape.ng;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isoplane.dataape.ng.MongoDriver.DADatabaseConnectionException;
import com.mongodb.MongoException;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;

/**
 * Read-only port of the legacy Data-Ape HTTP server.
 * The server binds to loopback by default because authentication, TLS and
 * production target enforcement are not yet implemented.
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
            cfg.routes.put("/save/{database}/{table}", DataApeServer::rejectWrite);
            cfg.routes.delete("/delete/{database}/{table}/{id}", DataApeServer::rejectWrite);
            cfg.routes.exception(IllegalArgumentException.class, (ex, ctx) ->
                    ctx.status(400).json(Map.of("msg", ex.getMessage(), "id", 0)));
            cfg.routes.exception(DADatabaseConnectionException.class, (ex, ctx) ->
                    ctx.status(401).json(Map.of("msg", "DB not connected", "id", -1)));
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
        ctx.json(databaseDescription(mongo.connect(sessionId(ctx), cstr.textValue())));
    }

    private Map<String, Object> databaseDescription(MongoDriver.DbDescription db) {
        return Map.of("dbName", db.name, "tables", db.tables,
                "decimaldigits", config.config().getInt("ui.decimaldigits", 2));
    }

    private static String sessionId(Context ctx) {
        return ctx.req().getSession().getId();
    }

    private static void rejectWrite(Context ctx) {
        ctx.status(403).json(Map.of("msg", "Server is read-only", "id", 0));
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
