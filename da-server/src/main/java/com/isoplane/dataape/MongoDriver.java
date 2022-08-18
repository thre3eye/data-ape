package com.isoplane.dataape;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.isoplane.dataape.DataApeServer.ConfigUtil;
import com.isoplane.dataape.JsonHelper.SelectParam;
import com.isoplane.dataape.JsonHelper.SortParam;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.QueryBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongoDriver {

    static final Logger log = LoggerFactory.getLogger(MongoDriver.class);

    private ConfigUtil _config;
    private Map<String, Long> _sessionTimeoutMap = new HashMap<>();
    private Map<String, MongoClient> _sessionClientMap = new HashMap<>();

    private Map<String, Map<String, Table>> _tableMap = new HashMap<>();

    public MongoDriver(ConfigUtil config_) {
        this._config = config_;
        this.init();
    }

    private void init() {
        try {

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // Expects format:
    // mongodb://<user>:<password>@<address>/<database>
    // ?retryWrites=false (or other options)
    public DbDescription connect(String sessionId_, String cstr_) {
        var startIdx = cstr_.indexOf("://");
        var endIdx = cstr_.lastIndexOf("@");
        var tokenStr = cstr_.substring(startIdx + "://".length(), endIdx);
        var tokens = tokenStr.split(":", 2);
        var uname = URLEncoder.encode(tokens[0], Charset.defaultCharset());
        var pass = URLEncoder.encode(tokens[1], Charset.defaultCharset());
        var cstr = cstr_.replace(tokens[0], uname).replace(tokens[1], pass);

        startIdx = cstr_.lastIndexOf("/");
        endIdx = cstr_.lastIndexOf("?");
        var dbName = endIdx < 0 ? cstr_.substring(startIdx + 1) : cstr_.substring(startIdx + 1, endIdx);

        var uri = new MongoClientURI(cstr);
        var mongo = new MongoClient(uri);
        this._sessionClientMap.put(sessionId_, mongo);

        var dbn = mongo.listDatabaseNames();
        var dbNames = dbn.into(new ArrayList<>());
        log.info(String.format("Connected [%s]: %s", dbName, dbNames));

        var db = this.getTables(sessionId_, dbName);

        // this._mongo = mongo;
        return db;
    }

    public boolean update(String sessionId_, String database_, String table_, String json_) {
        Document doc = StringUtils.isBlank(json_) ? null : Document.parse(json_);
        boolean result = updateDocument(sessionId_, database_, table_, doc);
        return result;
    }

    private boolean updateDocument(String sessionId_, String database_, String table_, Document doc_) {
        var mongo = this._sessionClientMap.get(sessionId_);
        if (mongo == null) {
            throw new DADatabaseException("DB not initialized");
        }
        String id = doc_ != null ? (String) doc_.remove("_id") : null;
        if (StringUtils.isBlank(id)) {
            return false;
        }
        MongoDatabase db = mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);
        Bson query = Filters.eq("_id", id);
        Document oldDoc = collection.find(query).first();
        if (oldDoc != null) {
            var i = doc_.entrySet().iterator();
            while (i.hasNext()) {
                var entry = i.next();
                Object oldValue = oldDoc.get(entry.getKey());
                Object newValue = entry.getValue();
                if (oldValue == null && newValue == null) {
                    i.remove();
                } else if (oldValue instanceof Number && newValue instanceof Number) {
                    Number numValue = (Number) newValue;
                    if (oldValue instanceof Byte) {
                        numValue = numValue.byteValue();
                    } else if (oldValue instanceof Double) {
                        numValue = numValue.doubleValue();
                    } else if (oldValue instanceof Float) {
                        numValue = numValue.floatValue();
                    } else if (oldValue instanceof Integer) {
                        numValue = numValue.intValue();
                    } else if (oldValue instanceof Long) {
                        numValue = numValue.longValue();
                    } else if (oldValue instanceof Short) {
                        numValue = numValue.shortValue();
                    }
                    entry.setValue(numValue);
                }
            }
        }
        ReplaceOptions options = new ReplaceOptions().upsert(true);
        UpdateResult update = collection.replaceOne(query, doc_, options);
        boolean result = update.getMatchedCount() == 1;
        return result;
    }

    public boolean delete(String sessionId_, String database_, String table_, String id_) {
        var mongo = this._sessionClientMap.get(sessionId_);
        if (mongo == null) {
            throw new DADatabaseException("DB not initialized");
        }
        if (StringUtils.isBlank(id_)) {
            return false;
        }
        Bson query = Filters.eq("_id", id_);
        MongoDatabase db = mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);

        Document doc = collection.findOneAndDelete(query);
        if (doc != null) {
            Configuration config = _config.config();
            String preFix = config.getString("mongo.deleted.prefix", "");
            String postFix = config.getString("mongo.deleted.postfix", "");
            if (!StringUtils.isBlank(String.format("%s%s", preFix, postFix).trim())) {
                String deleteTable = String.format("%s%s%s", preFix, table_, postFix);
                updateDocument(sessionId_, database_, deleteTable, doc);
            }
            return true;
        }
        return false;
    }

    public DbDescription getTables(String sessionId_, String database_, String... tables_) {
        var mongo = this._sessionClientMap.get(sessionId_);
        if (mongo == null) {
            throw new DADatabaseException("DB not initialized");
        }
        var start = LocalDateTime.now();
        Set<String> tableNames = new TreeSet<>();
        Map<String, Table> tableMap = new TreeMap<>();
        if (tables_ != null && tables_.length > 0) {
            tableNames.addAll(Arrays.asList(tables_));
            var oldMap = this._tableMap.get(database_);
            if (oldMap == null) {
                this._tableMap.put(database_, tableMap);
            } else {
                oldMap.putAll(tableMap);
            }
        } else {
            MongoDatabase db = mongo.getDatabase(database_);
            db.listCollectionNames().into(tableNames);
            this._tableMap.put(database_, tableMap);
        }
        for (String tableName : tableNames) {
            Table table = new Table();
            tableMap.put(tableName, table);
            table.name = tableName;
            var fieldMap = getFieldInfo(sessionId_, database_, tableName);
            table.fieldMap = fieldMap;
        }
        var end = LocalDateTime.now();
        var duration = Duration.between(start, end);
        log.debug(String.format("getTables duration: %s", duration));

        var db = new DbDescription();
        db.name = database_;
        db.tables = tableMap.values();
        return db;
    }

    private Map<String, String> getFieldInfo(String sessionId_, String database_, String tableName_) {
        var mongo = this._sessionClientMap.get(sessionId_);
        if (mongo == null) {
            throw new DADatabaseException("DB not initialized");
        }
        MongoDatabase db = mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(tableName_);

        var scanLmit = _config.config().getInt("mongo.field.scan.limit");
        var typeQuery = collection.aggregate(Arrays.asList(
                new Document("$limit", scanLmit),
                new Document("$project",
                        new Document("arrayofkeyvalue", new Document("$objectToArray", "$$ROOT"))),
                new Document("$unwind", "$arrayofkeyvalue"),
                new Document("$group",
                        new Document("_id", "$arrayofkeyvalue.k").append("alltypes",
                                new Document("$push",
                                        new Document(new Document("$type", "$arrayofkeyvalue.v")))))));
        var queryArray = new ArrayList<Document>();
        Map<String, String> fieldMap = new TreeMap<>();
        typeQuery.into(queryArray);
        for (Document doc : queryArray) {
            var field = (String) doc.get("_id");
            String type;
            @SuppressWarnings("unchecked")
            var types = (List<String>) doc.get("alltypes");
            if (types == null || types.isEmpty()) {
                log.error(String.format("getFieldTypes missing value [%s]", field));
                type = null;
            } else if (types.size() == 1) {
                type = types.get(0);
            } else {
                Map<String, Long> typeMap = types.stream()
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
                type = typeMap.entrySet().stream().max(Map.Entry.comparingByValue()).map(Entry::getKey).get();
            }
            fieldMap.put(field, type);
        }
        return fieldMap;
    }

    public Map<String, Object> getData(String sessionId_, String database_, String table_, Map<String, String> params_)
            throws IOException {
        var mongo = this._sessionClientMap.get(sessionId_);
        if (mongo == null) {
            throw new DADatabaseException("DB not initialized");
        }
        boolean isCount = MapUtils.getBooleanValue(params_, "count_total", true);
        int page = MapUtils.getIntValue(params_, "page", 1);
        int pageSize = MapUtils.getIntValue(params_, "page_size", 50);
        String selectJson = MapUtils.getString(params_, "select");
        List<SelectParam> select = JsonHelper.toSelectParam(selectJson);
        String sortJson = MapUtils.getString(params_, "sort");
        List<SortParam> sort = JsonHelper.toSortParam(sortJson);

        MongoDatabase db = mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);

        // NOTE: Primarily for dev when server restarts but not UI
        if (this._tableMap.isEmpty() || !this._tableMap.containsKey(database_)) {
            this.getTables(sessionId_, database_);
        } else if (!this._tableMap.get(database_).containsKey(table_)) {
            this.getTables(database_, table_);
        }
        var tables = _tableMap.get(database_);
        var table = tables != null ? tables.get(table_) : null;
        var fieldMap = table != null ? table.fieldMap : null;

        String selectStr = null;
        Bson query = new BsonDocument();
        if (select != null && !select.isEmpty()) {
            List<DBObject> qos = new ArrayList<>();
            for (SelectParam selectParam : select) {
                var paramQb = QueryBuilder.start(selectParam.key);
                paramQb = switch (selectParam.op) {
                    case "exst" -> paramQb.exists(true);
                    case "nxst" -> paramQb.exists(false);
                    case "gt" -> paramQb.greaterThan(selectParam.val);
                    case "gte" -> paramQb.greaterThanEquals(selectParam.val);
                    case "eq" -> paramQb.is(selectParam.val);
                    case "ne" -> paramQb.notEquals(selectParam.val);
                    case "lte" -> paramQb.lessThanEquals(selectParam.val);
                    case "lt" -> paramQb.lessThan(selectParam.val);
                    case "stw" -> {
                        Pattern pattern = Pattern.compile("^" + Pattern.quote(selectParam.val.toString()),
                                Pattern.CASE_INSENSITIVE);
                        yield paramQb.regex(pattern);
                    }
                    case "ctn" -> {
                        Pattern pattern = Pattern.compile(".*" + Pattern.quote(selectParam.val.toString()) + ".*",
                                Pattern.CASE_INSENSITIVE);
                        yield paramQb.regex(pattern);
                    }
                    case "excl" -> {
                        Pattern pattern = Pattern.compile("^(?!.*" + Pattern.quote(selectParam.val.toString()) + ").*",
                                Pattern.CASE_INSENSITIVE);
                        yield paramQb.regex(pattern);
                    }
                    case "endw" -> {
                        Pattern pattern = Pattern.compile(Pattern.quote(selectParam.val.toString()) + "$",
                                Pattern.CASE_INSENSITIVE);
                        yield paramQb.regex(pattern);
                    }
                    default -> null;
                };
                if (paramQb != null) {
                    qos.add(paramQb.get());
                }
            }
            if (!qos.isEmpty()) {
                var qb = QueryBuilder.start().and(qos.toArray(new DBObject[0]));
                selectStr = qb.get().toString();
                query = (Bson) qb.get();
            }
        }
        long queryCount = isCount ? collection.countDocuments(query) : -1;
        FindIterable<Document> documents;
        String sortStr = null;
        if (queryCount > 0) {
            documents = collection.find(query);
            if (sort != null && !sort.isEmpty()) {
                // NOTE: Using Document to get mql query string
                Document sortD = new Document();
                for (SortParam sortParam : sort) {
                    sortD.append(sortParam.key, sortParam.dir);
                }
                sortStr = sortD.toJson();
                documents = documents.sort(sortD);
            }
            documents = documents.skip(pageSize * (page - 1)).limit(pageSize);
        } else {
            documents = collection.find().limit(1);
        }

        int count = 0;
        List<List<Object>> data = new ArrayList<>();
        if (fieldMap != null && !fieldMap.isEmpty() && documents != null) {
            for (String key : fieldMap.keySet()) {
                count = 0;
                var keyColumn = new ArrayList<>();
                data.add(keyColumn);
                for (Document document : documents) {
                    var value = document.get(key);
                    keyColumn.add(value);
                    count++;
                }
            }
        }

        Map<String, Object> tableDescription = new HashMap<>();
        tableDescription.put("db", database_);
        tableDescription.put("table", table_);
        tableDescription.put("fieldMap", fieldMap);
        tableDescription.put("data", data);
        tableDescription.put("querySize", queryCount);
        tableDescription.put("pageSize", pageSize);
        tableDescription.put("page", page);
        if (select != null && !select.isEmpty()) {
            tableDescription.put("select", select);
        }
        if (sort != null && !sort.isEmpty()) {
            tableDescription.put("sort", sort);
        }
        selectStr = StringUtils.isBlank(selectStr) ? "" : String.format(", 'filter':%s", selectStr).replace('"', '\'');
        sortStr = StringUtils.isBlank(sortStr) ? "" : String.format(", 'sort':%s", sortStr).replace('"', '\'');
        var queryStr = String.format("{%s %s}", table_, selectStr, sortStr); // NOTE: We only want to allow find queries
                                                                             // # find':'%s'
        tableDescription.put("query", queryStr);
        tableDescription.put("dataSize", queryCount > 0 ? count : 0);
        return tableDescription;
    }

    public static class Table {
        public String name;
        public Map<String, String> fieldMap;
    }

    public static class DbDescription {
        public String name;
        public Collection<Table> tables;
    }

    public static class DADatabaseException extends RuntimeException {

        public DADatabaseException() {
            super();
        }

        public DADatabaseException(String msg_) {
            super(msg_);
        }
    }

    public void updateSession(String sessionId_, long timeout_) {
        this._sessionTimeoutMap.put(sessionId_, timeout_);
    }

}
