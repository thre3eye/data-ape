package com.isoplane.dataape;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.regex;
//import static com.mongodb.client.model.Filters.gt;
//import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;

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
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.configuration2.Configuration;
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

    public Map<String, Object> getData(String database_, String table_, Map<String, String> params_) {
        boolean isCount = MapUtils.getBooleanValue(params_, "count_total", true);
        int page = MapUtils.getIntValue(params_, "page", 1);
        int pageSize = MapUtils.getIntValue(params_, "page_size", 50);
        String sortKey = MapUtils.getString(params_, "sort_key");
        int sortDir = MapUtils.getIntValue(params_, "sort_dir", -1);
        String selectKey = MapUtils.getString(params_, "select_key");
        String selectOp = MapUtils.getString(params_, "select_op");
        Object selectVal = MapUtils.getObject(params_, "select_val");

        Map<String, List<Object>> dataTable = new HashMap<>();
        MongoDatabase db = this._mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);
        Bson query = new BsonDocument();
        if (selectKey != null) {
            query = switch (selectOp) {
                case "gt" -> gt(selectKey, selectVal);
                case "gte" -> gte(selectKey, selectVal);
                case "eq" -> eq(selectKey, selectVal);
                case "lte" -> lte(selectKey, selectVal);
                case "lt" -> lt(selectKey, selectVal);
                case "stw" -> {
                    Pattern pattern = Pattern.compile("^" + Pattern.quote(selectVal.toString()),
                            Pattern.CASE_INSENSITIVE);
                    yield regex(selectKey, pattern);
                }
                case "ctn" -> {
                    Pattern pattern = Pattern.compile(".*" + Pattern.quote(selectVal.toString()) + ".*",
                            Pattern.CASE_INSENSITIVE);
                    yield regex(selectKey, pattern);
                }
                case "edw" -> {
                    Pattern pattern = Pattern.compile(Pattern.quote(selectVal.toString()) + "$",
                            Pattern.CASE_INSENSITIVE);
                    yield regex(selectKey, pattern);
                }
                default -> query;
            };
        }

        long queryCount = isCount ? collection.countDocuments(query) : -1;
        //    List<Bson> aggregationQuery = new ArrayList<>();
        //  if (sort != null) {
        //       aggregationQuery.add(Aggregates.sort(new Document(sort, sortDir)));
        //   }
        // aggregationQuery.add(Aggregates.skip(pageSize * (page - 1)));
        // aggregationQuery.add(Aggregates.limit(pageSize));
        // AggregateIterable<Document> result = collection.aggregate(aggregationQuery);//.allowDiskUse(true);

        FindIterable<Document> documents = collection.find(query);
        if (sortKey != null) {
            documents = sortDir < 0 ? documents.sort(descending(sortKey)) : documents.sort(ascending(sortKey));
        }
        documents = documents.skip(pageSize * (page - 1)).limit(pageSize);
        //db.getCollection("collectionName").aggregate(anyList());
        Map<String, Object> tableDescription = new HashMap<>();
        List<String> headers = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<List<Object>> data = new ArrayList<>();
        tableDescription.put("db", database_);
        tableDescription.put("table", table_);
        tableDescription.put("headers", headers);
        tableDescription.put("types", types);
        tableDescription.put("data", data);
        tableDescription.put("querySize", queryCount);
        tableDescription.put("pageSize", pageSize);
        tableDescription.put("page", page);

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
