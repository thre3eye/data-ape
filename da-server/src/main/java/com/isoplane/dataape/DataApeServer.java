package com.isoplane.dataape;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isoplane.dataape.MongoDriver.DADatabaseConnectionException;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

/**
 * Hello world!
 */
public class DataApeServer {

    static final Logger log = LoggerFactory.getLogger(DataApeServer.class);

    private ConfigUtil _config;
    private Javalin _server;
    private MongoDriver _mongo;
    private ObjectMapper _jsonMapper;

    public static void main(String[] args_) {
        DataApeServer server = new DataApeServer();
        server.init(args_[0], true);
        server.serve();
    }

    private void init(String propertiesPath_, boolean isReloading_) {
        _jsonMapper = new ObjectMapper();
        _config = new ConfigUtil(propertiesPath_, isReloading_);
        _mongo = new MongoDriver(_config);
    }

    private void serve() {
        Configuration config = _config.config();
        int port = config.getInt("server.port");
        _server = Javalin.create(config_ -> {
            config_.enableCorsForAllOrigins();
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

                var session = ctx_.req.getSession();
                var sessionId = session.getId();
                if (session.isNew()) {
                    log.info(String.format("New session"));
                    var timeout = config.getInt("session.timeout.interval", 900);
                    session.setMaxInactiveInterval(timeout);
                }
                var ival = session.getMaxInactiveInterval();
                var last = session.getLastAccessedTime();
                var timeout = last + (1000 * ival);
                this._mongo.updateSession(sessionId, timeout);
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Request method : %s", method));
                    log.debug(String.format("Request path   : %s", ctxPath));
                    log.debug(String.format("Request IP     : %s", ipStr));
                    log.debug(String.format("Request headers: %s", headers));
                    log.debug(String.format("Request session: %s", sessionId));
                    log.debug(String.format("Request time   : %s", new Date(last)));
                }
            }
        });
        _server.after(ctx_ -> {
            ctx_.header("Cache-Control", "no-cache, no-store, must-revalidate");
            ctx_.header("Pragma", "no-cache");
            ctx_.header("Expires", "0");
        });
        _server.post("/connect", ctx_ -> {
            var sessionId = ctx_.req.getSession().getId();
            var cstr1 = ctx_.queryParam("cstr");
            log.debug(String.format("cstr: %s", cstr1));
            var json = ctx_.body();
            var cstr = StringUtils.isBlank(json) ? null
                    : (String) _jsonMapper.readValue(json, HashMap.class).get("cstr");
            var db = this._mongo.connect(sessionId, cstr);
            var decimalDigits = _config.config().getInteger("ui.decimaldigits", 2);
            Map<String, Object> tableMap = Map.of("dbName", db.name, "tables", db.tables, "decimaldigits",
                    decimalDigits);
            ctx_.json(tableMap);
        });
        _server.get("/tables/{database}", ctx_ -> {
            var sessionId = ctx_.req.getSession().getId();
            String database = ctx_.pathParam("database");
            var db = this._mongo.getTables(sessionId, database);
            var decimalDigits = _config.config().getInteger("ui.decimaldigits", 2);
            var tableMap = Map.of("dbName", db.name, "tables", db.tables, "decimaldigits", decimalDigits);
            ctx_.json(tableMap);
        });
        _server.get("/data/{database}/{table}", ctx_ -> {
            var sessionId = ctx_.req.getSession().getId();
            String database = ctx_.pathParam("database");
            String table = ctx_.pathParam("table");
            Map<String, List<String>> paramss = ctx_.queryParamMap();
            Map<String, String> params = paramss.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().get(0)));
            Map<String, Object> tableDescription = this._mongo.getData(sessionId, database, table, params);
            Map<String, Object> dataMap = Collections.singletonMap("data", tableDescription);
            ctx_.json(dataMap);
        });
        _server.put("/save/{database}/{table}", ctx_ -> {
            var sessionId = ctx_.req.getSession().getId();
            String database = ctx_.pathParam("database");
            String table = ctx_.pathParam("table");
            String json = ctx_.body();
            boolean result = this._mongo.update(sessionId, database, table, json);
            ctx_.json(Collections.singletonMap("result", result));
        });
        _server.delete("/delete/{database}/{table}/{id}", ctx_ -> {
            var sessionId = ctx_.req.getSession().getId();
            String database = ctx_.pathParam("database");
            String table = ctx_.pathParam("table");
            String id = ctx_.pathParam("id");
            boolean result = this._mongo.delete(sessionId, database, table, id);
            ctx_.json(Collections.singletonMap("result", result));
        });
        _server.exception(DADatabaseConnectionException.class, (err_, ctx_) -> {
            var msg = err_.getMessage();
            var msgMap = Map.of("msg", StringUtils.isBlank(msg) ? "DB Error" : msg, "id", -1);
            ctx_.status(500);
            ctx_.json(msgMap);
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

}
