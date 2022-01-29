package com.isoplane.dataape;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Sorts.orderBy;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.isoplane.dataape.DataApeServer.ConfigUtil;
import com.isoplane.dataape.JsonHelper.SelectParam;
import com.isoplane.dataape.JsonHelper.SortParam;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
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
import org.bson.types.ObjectId;

public class MongoDriver {

    private ConfigUtil _config;
    private MongoClient _mongo;

    public MongoDriver(ConfigUtil config_) {
        this._config = config_;
        this.init();
    }

    private void init() {
        try {
            Configuration config = _config.config();
            String dbName = config.getString("mongo.db");
            String user = config.getString("mongo.user");
            String pass = config.getString("mongo.pass");
            String connectionStr = config.getString("mongo.cstr");

            user = URLEncoder.encode(user, "UTF-8");
            pass = URLEncoder.encode(pass, "UTF-8");
            if (connectionStr.contains("%s")) {
                connectionStr = String.format(connectionStr, user, pass, dbName);
            }
            MongoClientURI uri = new MongoClientURI(connectionStr);
            this._mongo = new MongoClient(uri);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public boolean update(String database_, String table_, String json_) {
        Document doc = StringUtils.isBlank(json_) ? null : Document.parse(json_);
        boolean result = updateDocument(database_, table_, doc);
        return result;
    }

    private boolean updateDocument(String database_, String table_, Document doc_) {
        String id = doc_ != null ? (String) doc_.remove("_id") : null;
        if (StringUtils.isBlank(id)) {
            return false;
        }
        Bson query = Filters.eq("_id", id);
        ReplaceOptions options = new ReplaceOptions().upsert(true);
        MongoDatabase db = this._mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);
        UpdateResult update = collection.replaceOne(query, doc_, options);
        boolean result = update.getMatchedCount() == 1;
        return result;
    }

    public boolean delete(String database_, String table_, String id_) {
        if (StringUtils.isBlank(id_)) {
            return false;
        }
        Bson query = Filters.eq("_id", id_);
        MongoDatabase db = this._mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);

        Document doc = collection.findOneAndDelete(query);
        if (doc != null) {
            Configuration config = _config.config();
            String preFix = config.getString("mongo.deleted.prefix", "");
            String postFix = config.getString("mongo.deleted.postfix", "");
            if (!StringUtils.isBlank(String.format("%s%s", preFix, postFix).trim())) {
                String deleteTable = String.format("%s%s%s", preFix, table_, postFix);
                updateDocument(database_, deleteTable, doc);
            }
            return true;
        }
        return false;
        //   DeleteResult delete = collection.deleteOne(query);
        //   boolean result = delete.getDeletedCount() == 1;
        //  return result;
    }

    public String getDatabase() {
        Configuration config = _config.config();
        String dbName = config.getString("mongo.db");
        return dbName;
    }

    public Set<String> getTables(String database_) {
        Set<String> tables = new TreeSet<>();
        MongoDatabase db = this._mongo.getDatabase(database_);
        db.listCollectionNames().into(tables);
        return tables;
    }

    public Map<String, Object> getData(String database_, String table_, Map<String, String> params_)
            throws IOException {
        boolean isCount = MapUtils.getBooleanValue(params_, "count_total", true);
        int page = MapUtils.getIntValue(params_, "page", 1);
        int pageSize = MapUtils.getIntValue(params_, "page_size", 50);
        String selectJson = MapUtils.getString(params_, "select");
        List<SelectParam> select = JsonHelper.toSelectParam(selectJson);
        String sortJson = MapUtils.getString(params_, "sort");
        List<SortParam> sort = JsonHelper.toSortParam(sortJson);

        Map<String, List<Object>> dataTable = new HashMap<>();
        MongoDatabase db = this._mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);
        Bson query = new BsonDocument();
        if (select != null && !select.isEmpty()) {
            List<Bson> selects = new ArrayList<>();
            for (SelectParam selectParam : select) {
                var q = switch (selectParam.op) {
                    case "gt" -> gt(selectParam.key, selectParam.val);
                    case "gte" -> gte(selectParam.key, selectParam.val);
                    case "eq" -> eq(selectParam.key, selectParam.val);
                    case "lte" -> lte(selectParam.key, selectParam.val);
                    case "lt" -> lt(selectParam.key, selectParam.val);
                    case "stw" -> {
                        Pattern pattern = Pattern.compile("^" + Pattern.quote(selectParam.val.toString()),
                                Pattern.CASE_INSENSITIVE);
                        yield regex(selectParam.key, pattern);
                    }
                    case "ctn" -> {
                        Pattern pattern = Pattern.compile(".*" + Pattern.quote(selectParam.val.toString()) + ".*",
                                Pattern.CASE_INSENSITIVE);
                        yield regex(selectParam.key, pattern);
                    }
                    case "edw" -> {
                        Pattern pattern = Pattern.compile(Pattern.quote(selectParam.val.toString()) + "$",
                                Pattern.CASE_INSENSITIVE);
                        yield regex(selectParam.key, pattern);
                    }
                    default -> query;
                };
                selects.add(q);
            }
            query = and(selects);
        }
        long queryCount = isCount ? collection.countDocuments(query) : -1;
        FindIterable<Document> documents;
        if (queryCount > 0) {
            documents = collection.find(query);
            if (sort != null && !sort.isEmpty()) {
                List<Bson> sorts = new ArrayList<>();
                for (SortParam sortParam : sort) {
                    sorts.add(sortParam.dir < 0
                            ? descending(sortParam.key)
                            : ascending(sortParam.key));
                }
                documents = documents.sort(orderBy(sorts));
            }
            documents = documents.skip(pageSize * (page - 1)).limit(pageSize);
        } else {
            documents = collection.find().limit(1);
        }
        Map<String, Object> tableDescription = new HashMap<>();
        List<String> headers = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<List<Object>> data = new ArrayList<>();
        tableDescription.put("db", database_);
        tableDescription.put("table", table_);
        tableDescription.put("headers", headers);
        tableDescription.put("types", types);
        tableDescription.put("data", queryCount > 0 ? data : new ArrayList<>());
        tableDescription.put("querySize", queryCount);
        tableDescription.put("pageSize", pageSize);
        tableDescription.put("page", page);
        if (select != null && !select.isEmpty()) {
            tableDescription.put("select", select);
        }
        if (sort != null && !sort.isEmpty()) {
            tableDescription.put("sort", sort);
        }

        int count = 0;
        for (Document document : documents) {
            List<String> oldKeys = new ArrayList<>(headers);
            Set<String> oldKeys2 = new HashSet<>(dataTable.keySet());
            Set<String> newKeys = document.keySet();
            for (String key : newKeys) {
                Object value = document.get(key);
                if ("_id".equals(key) && ObjectId.class.isInstance(value)) {
                    value = ((ObjectId) value).toString();
                }
                int index = headers.indexOf(key);
                if (index == -1) {
                    if (value == null)
                        continue;
                    headers.add(key);
                    oldKeys.add(key);
                    types.add(value.getClass().getSimpleName());
                    data.add(this.createDataArray(count));
                    index = headers.size() - 1;
                }
                List<Object> column = data.get(index);
                column.add(value);
                oldKeys.remove(key);

                List<Object> row = this.backfill(dataTable, key, count, value);
                row.add(value);
                oldKeys2.remove(key);
            }
            // Backfill with NULL
            for (String key : oldKeys) {
                int index = headers.indexOf(key);
                data.get(index).add(null);
            }

            for (String key : oldKeys2) {
                List<Object> row = dataTable.get(key);
                row.add(null);
            }
            count++;
        }
        tableDescription.put("dataSize", queryCount > 0 ? count : 0);
        return tableDescription;
    }

    private List<Object> createDataArray(int count_) {
        List<Object> list = new ArrayList<>();
        for (var i = 0; i < count_; i++) {
            list.add(null);
        }
        return list;
    }

    private List<Object> backfill(Map<String, List<Object>> map_, String key_, int count_, Object value_) {
        List<Object> row = map_.get(key_);
        if (row != null)
            return row;
        row = new ArrayList<>(count_);
        map_.put(key_, row);
        String type = getType(value_);
        row.add(type);
        // Backfill
        for (var i = 0; i < count_; i++) {
            if (row.size() - 1 == count_)
                break;
            row.add(null);
        }
        return row;
    }

    private String getType(Object value_) {
        if (value_ == null)
            return null;
        String type = value_.getClass().getSimpleName();
        return type;
    }

}
