package com.isoplane.dataape;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

/**
 * Hello world!
 *
 */
public class DataApeServer {

    static final Logger log = LoggerFactory.getLogger(DataApeServer.class);

    private ConfigUtil _config;
    private Javalin _server;
    private MongoDriver _mongo;

    public static void main(String[] args_) {
        DataApeServer server = new DataApeServer();
        server.init(args_[0], true);
        server.serve();
    }

    private void init(String propertiesPath_, boolean isReloading_) {
        _config = new ConfigUtil(propertiesPath_, isReloading_);
        _mongo = new MongoDriver(_config);
    }

    private void serve() {
        Configuration config = _config.config();
        int port = config.getInt("server.port");
        _server = Javalin.create(config_ -> {
            config_.enableCorsForAllOrigins();
            //  config_.jsonMapper(jsonMapper);
            boolean isWebDebugLog = config.getBoolean("server.debug.logging", false);
            if (isWebDebugLog) {
                config_.enableDevLogging();
            }
            config_.addStaticFiles(files_ -> {
                files_.hostedPath = config.getString("server.static.path");
                files_.directory = config.getString("server.static.files");
                files_.location = Location.EXTERNAL;
            });
            config_.addStaticFiles(files_ -> {
                files_.hostedPath = config.getString("server.config.path");
                files_.directory = config.getString("server.config.files");
                files_.location = Location.EXTERNAL;
            });
        });
        _server.before(ctx_ -> {
            if (log.isDebugEnabled()) {
                var method = ctx_.method();
                var ctxPath = ctx_.path();
                var headers = ctx_.headerMap();
                var ipStr = ctx_.ip();
                try {
                    ipStr = ctx_.req.getRemoteAddr();
                } catch (Exception ex) {
                    log.error("Error getting remote address", ex);
                }
                log.debug(String.format("Request method : %s", method));
                log.debug(String.format("Request path   : %s", ctxPath));
                log.debug(String.format("Request IP     : %s", ipStr));
                log.debug(String.format("Request headers: %s", headers));
            }
        });
        _server.after(ctx_ -> {
            ctx_.header("Cache-Control", "no-cache, no-store, must-revalidate");
            ctx_.header("Pragma", "no-cache");
            ctx_.header("Expires", "0");
        });
        _server.get("/database", ctx_ -> {
            String dbName = this._mongo.getDatabase();
            Map<String, Object> dbMap = Collections.singletonMap("dbName", dbName);
            ctx_.json(dbMap);
        });
        _server.get("/tables/{database}", ctx_ -> {
            String database = ctx_.pathParam("database");
            Set<String> tables = this._mongo.getTables(database);
            Map<String, Object> tableMap = Collections.singletonMap("tables", tables);
            ctx_.json(tableMap);
        });
        _server.get("/data/{database}/{table}", ctx_ -> {
            String database = ctx_.pathParam("database");
            String table = ctx_.pathParam("table");
            Map<String, List<String>> paramss = ctx_.queryParamMap();
            Map<String, String> params = paramss.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().get(0)));
            //     .map(e -> new SimpleEntry<String, String>(e.getKey(), e.getValue().get(0))).collect(Collectors.toMap());
            Map<String, Object> tableDescription = this._mongo.getData(database, table, params);
            Map<String, Object> dataMap = Collections.singletonMap("data", tableDescription);
            ctx_.json(dataMap);
        });
        _server.put("/save/{database}/{table}", ctx_ -> {
            String database = ctx_.pathParam("database");
            String table = ctx_.pathParam("table");
            String json = ctx_.body();
            boolean result = this._mongo.update(database, table, json);
            ctx_.json(Collections.singletonMap("result", result));
        });
        _server.delete("/delete/{database}/{table}/{id}", ctx_ -> {
            String database = ctx_.pathParam("database");
            String table = ctx_.pathParam("table");
            String id = ctx_.pathParam("id");
            boolean result = this._mongo.delete(database, table, id);
            ctx_.json(Collections.singletonMap("result", result));
        });
        _server.start(port);
    }

    public static class ConfigUtil {

        private Configuration _config;
        private String[] _propertiesPath;
        private Map<String, Object> _runtimeConfig;
        private ScheduledExecutorService _scheduler;

        public ConfigUtil(String propertiesPath_, boolean isReloading_) {
            this.init(propertiesPath_, isReloading_);
        }

        private Configuration init(String propertiesPath_, boolean isReloading_) {
            _runtimeConfig = new HashMap<>();
            if (StringUtils.isBlank(propertiesPath_)) {
                _config = new BaseConfiguration();
                _propertiesPath = new String[0];
                log.warn(String.format("Empty config path!"));
            } else {
                _propertiesPath = propertiesPath_.split(",");
                log.debug(String.format("Reading properties: %s", Arrays.asList(_propertiesPath)));
                _config = loadConfiguration();
                if (isReloading_) {
                    _scheduler = Executors.newScheduledThreadPool(1);
                    _scheduler.scheduleAtFixedRate(new Runnable() {
                        @Override
                        public void run() {
                            loadConfiguration();
                        }
                    }, 30000, 30000, TimeUnit.MILLISECONDS);
                }
            }
            return _config;
        }

        private Configuration loadConfiguration() {
            try {
                CompositeConfiguration cconfig = new CompositeConfiguration();
                for (String path : _propertiesPath) {
                    if (_config == null) {
                        log.info(String.format("Reading properties [%s]", path));
                    } else {
                        log.trace(String.format("Reading properties [%s]", path));
                    }
                    PropertiesConfiguration config;
                    config = new Configurations().properties(new File(path));
                    cconfig.addConfigurationFirst(config);
                }
                for (Entry<String, Object> entry : _runtimeConfig.entrySet()) {
                    cconfig.setProperty(entry.getKey(), entry.getValue());
                }
                // cconfig.setListDelimiterHandler(new DefaultListDelimiterHandler(','));
                _config = cconfig;
                return _config;
            } catch (ConfigurationException ex) {
                log.error(String.format("Error loading configuration"), ex);
                return null;
            }
        }

        public Configuration config() {
            return _config;
        }

    }

    // public static class JsonNullMapper implements JsonMapper {
    //     @Override
    //     public String toJsonString(@NotNull Object obj) {
    //         StdSerializerProvider sp = new StdSerializerProvider();
    //         return gson.toJson(obj);
    //     }
    //     @Override
    //     public <T> T fromJsonString(@NotNull String json, @NotNull Class<T> targetClass) {
    //         return gson.fromJson(json, targetClass);
    //     }
    // }

}
