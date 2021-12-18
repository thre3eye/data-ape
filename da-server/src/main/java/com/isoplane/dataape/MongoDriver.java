package com.isoplane.dataape;

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

import com.isoplane.dataape.DataApeServer.ConfigUtil;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;

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
                connectionStr = String.format(connectionStr, user, pass);
            }
            MongoClientURI uri = new MongoClientURI(connectionStr);
            this._mongo = new MongoClient(uri);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
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
        String sort = MapUtils.getString(params_, "sort");
        int sortDir = MapUtils.getIntValue(params_, "sort_dir", -1);

        Map<String, List<Object>> dataTable = new HashMap<>();
        MongoDatabase db = this._mongo.getDatabase(database_);
        MongoCollection<Document> collection = db.getCollection(table_);
        Bson query = new BsonDocument();
        long queryCount = isCount ? collection.countDocuments(query) : -1;
        //    List<Bson> aggregationQuery = new ArrayList<>();
        //  if (sort != null) {
        //       aggregationQuery.add(Aggregates.sort(new Document(sort, sortDir)));
        //   }
        // aggregationQuery.add(Aggregates.skip(pageSize * (page - 1)));
        // aggregationQuery.add(Aggregates.limit(pageSize));
        // AggregateIterable<Document> result = collection.aggregate(aggregationQuery);//.allowDiskUse(true);

        FindIterable<Document> documents = collection.find(query);
        if (sort != null) {
            documents = sortDir < 0 ? documents.sort(descending(sort)) : documents.sort(ascending(sort));
        }
        documents = documents.skip(pageSize * (page - 1)).limit(pageSize);
        //db.getCollection("collectionName").aggregate(anyList());
        Map<String, Object> tableDescription = new HashMap<>();
        List<String> headers = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<List<Object>> data = new ArrayList<>();
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
