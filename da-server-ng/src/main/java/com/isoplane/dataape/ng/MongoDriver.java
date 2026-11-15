package com.isoplane.dataape.ng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.configuration2.Configuration;
import org.bson.BsonDocument;
import org.bson.BSONException;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonParseException;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;

import com.isoplane.dataape.ng.JsonHelper.ReadQuery;
import com.mongodb.ConnectionString;
import com.mongodb.MongoException;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.DeleteOptions;

/**
 * Legacy-style MongoDB adapter with one independently owned client per HTTP session.
 * Per-session locking prevents a reconnect or expiry from closing a client while
 * another request is using it, without serializing unrelated sessions.
 */
public class MongoDriver implements AutoCloseable {
    private final Supplier<Configuration> config;
    private final Function<ConnectionString, MongoClient> clientFactory;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates the adapter and starts session cleanup.
     *
     * @param config application configuration, including a nonempty target allowlist
     */
    public MongoDriver(Configuration config) {
        this(() -> config, MongoClients::create);
    }

    MongoDriver(Configuration config, Function<ConnectionString, MongoClient> clientFactory) {
        this(() -> config, clientFactory);
    }

    MongoDriver(Supplier<Configuration> config, Function<ConnectionString, MongoClient> clientFactory) {
        this.config = config;
        this.clientFactory = clientFactory;
        int period = config.get().getInt("session.cleanup.interval", 900);
        if (period <= 0) {
            scheduler.shutdown();
            throw new IllegalArgumentException("session.cleanup.interval must be positive");
        }
        scheduler.scheduleAtFixedRate(this::cleanSessions, period, period, TimeUnit.SECONDS);
    }

    /**
     * Opens a candidate client and discovers collections before replacing the old client.
     * A failed reconnect therefore cannot invalidate a working session.
     *
     * @param sessionId session that owns this connection
     * @param cstr MongoDB URI; raw at-signs in the password are also accepted
     * @return initial database description
     */
    public DbDescription connect(String sessionId, String cstr) {
        ConnectionString uri = connectionString(cstr);
        Session session = session(sessionId);
        MongoClient candidate = clientFactory.apply(uri);
        boolean installed = false;
        try {
            DbDescription description = describe(candidate, uri.getDatabase(), null);
            synchronized (session) {
                if (session.closed || sessions.get(sessionId) != session) {
                    throw new DADatabaseConnectionException("Session expired");
                }
                MongoClient old = session.client;
                session.client = candidate;
                session.tables.clear();
                session.tables.put(uri.getDatabase(), tableMap(description));
                installed = true;
                if (old != null) {
                    old.close();
                }
            }
            return description;
        } finally {
            if (!installed) {
                candidate.close();
            }
        }
    }

    private ConnectionString connectionString(String cstr) {
        if (cstr == null || !cstr.startsWith("mongodb://")) {
            throw new IllegalArgumentException("A standard MongoDB URI is required");
        }
        final ConnectionString uri;
        try {
            uri = new ConnectionString(encodePasswordAtSigns(cstr));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid MongoDB URI");
        }
        if (uri.getDatabase() == null || uri.getDatabase().isBlank()) {
            throw new IllegalArgumentException("A database name is required");
        }
        Set<String> allowed = Arrays.stream(config.get().getStringArray("mongo.allowed.hosts"))
                .map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (allowed.isEmpty() || uri.getHosts().stream().map(host -> host.toLowerCase(Locale.ROOT))
                .anyMatch(host -> !allowed.contains(host))) {
            throw new IllegalArgumentException("MongoDB target is not allowed");
        }
        return uri;
    }

    private static String encodePasswordAtSigns(String cstr) {
        int authorityStart = "mongodb://".length();
        int authorityEnd = cstr.length();
        for (char delimiter : new char[] { '/', '?', '#' }) {
            int index = cstr.indexOf(delimiter, authorityStart);
            if (index >= 0 && index < authorityEnd) {
                authorityEnd = index;
            }
        }
        // The last authority at-sign separates credentials from hosts; earlier ones belong to the password.
        int separator = cstr.lastIndexOf('@', authorityEnd - 1);
        int passwordStart = cstr.indexOf(':', authorityStart);
        if (passwordStart < 0 || passwordStart >= separator) {
            return cstr;
        }
        String password = cstr.substring(passwordStart + 1, separator);
        return cstr.substring(0, passwordStart + 1) + password.replace("@", "%40") + cstr.substring(separator);
    }

    /**
     * Lists collections and their sampled field types, scoped to this connection.
     *
     * @param sessionId session owning the client
     * @param database database to inspect
     * @return database description
     */
    public DbDescription getTables(String sessionId, String database) {
        Session session = session(sessionId);
        synchronized (session) {
            MongoClient client = client(session);
            DbDescription result = describe(client, database, null);
            session.tables.put(database, tableMap(result));
            return result;
        }
    }

    private DbDescription describe(MongoClient client, String database, String onlyTable) {
        var db = client.getDatabase(database);
        Set<String> names = new TreeSet<>();
        if (onlyTable == null) {
            db.listCollectionNames().into(names);
        } else {
            names.add(onlyTable);
        }
        List<Table> tables = new ArrayList<>();
        for (String name : names) {
            Table table = new Table();
            table.name = name;
            table.fieldMap = getFieldInfo(db.getCollection(name));
            tables.add(table);
        }
        DbDescription result = new DbDescription();
        result.name = database;
        result.tables = tables;
        return result;
    }

    private Map<String, String> getFieldInfo(MongoCollection<Document> collection) {
        int limit = config.get().getInt("mongo.field.scan.limit", 2500);
        if (limit <= 0) {
            throw new IllegalArgumentException("mongo.field.scan.limit must be positive");
        }
        var pipeline = List.of(
                new Document("$limit", limit),
                new Document("$project", new Document("arrayofkeyvalue", new Document("$objectToArray", "$$ROOT"))),
                new Document("$unwind", "$arrayofkeyvalue"),
                new Document("$group", new Document("_id", "$arrayofkeyvalue.k")
                        .append("alltypes", new Document("$push", new Document("$type", "$arrayofkeyvalue.v")))));
        List<Document> result = collection.aggregate(pipeline).into(new ArrayList<>());
        Map<String, String> fields = new TreeMap<>();
        for (Document field : result) {
            List<String> types = field.getList("alltypes", String.class);
            if (types == null || types.isEmpty()) {
                throw new IllegalStateException("MongoDB field metadata is missing");
            }
            String type = types.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            fields.put(field.getString("_id"), type);
        }
        return fields;
    }

    /**
     * Reads a filtered page using the legacy column-oriented response structure.
     * The filter is always applied even when no documents match or counting is disabled.
     *
     * @param sessionId session owning the client
     * @param database database name
     * @param table collection name
     * @param params legacy selection, sorting and paging parameters
     * @return table description
     */
    public Map<String, Object> getData(String sessionId, String database, String table, Map<String, String> params) {
        ReadQuery query = JsonHelper.parseQuery(params);
        Session session = session(sessionId);
        synchronized (session) {
            MongoClient client = client(session);
            Map<String, Table> tables = session.tables.computeIfAbsent(database, key -> new HashMap<>());
            Table metadata = tables.get(table);
            if (metadata == null) {
                metadata = describe(client, database, table).tables.iterator().next();
                tables.put(table, metadata);
            }
            var collection = client.getDatabase(database).getCollection(table);
            Page page = readPage(collection, metadata.fieldMap, query);
            Map<String, Object> result = new HashMap<>();
            result.put("db", database);
            result.put("table", table);
            result.put("fieldMap", metadata.fieldMap);
            result.put("data", page.data());
            result.put("querySize", page.querySize());
            result.put("dataSize", page.dataSize());
            result.put("page", query.page());
            result.put("pageSize", query.pageSize());
            var select = JsonHelper.selections(params.get("select"));
            if (!select.isEmpty()) {
                result.put("select", select);
            }
            if (!query.sort().isEmpty()) {
                result.put("sort", query.sort().entrySet().stream()
                        .map(entry -> Map.of("key", entry.getKey(), "dir", entry.getValue())).toList());
            }
            BsonDocument filter = query.filter().toBsonDocument(BsonDocument.class,
                    MongoClientSettings.getDefaultCodecRegistry());
            String filterText = filter.isEmpty() ? "" : ", 'filter':" + filter.toJson().replace('"', '\'');
            String sortText = query.sort().isEmpty() ? "" : ", 'sort':" + query.sort().toJson().replace('"', '\'');
            result.put("query", "{" + table + " " + filterText + " " + sortText + "}");
            return result;
        }
    }

    static Page readPage(MongoCollection<Document> collection, Map<String, String> fields, ReadQuery query) {
        long count = query.countTotal() ? collection.countDocuments(query.filter()) : -1;
        var find = collection.find(query.filter());
        if (!query.sort().isEmpty()) {
            find = find.sort(query.sort());
        }
        List<Document> rows = find.skip(query.skip()).limit(query.pageSize()).into(new ArrayList<>());
        List<List<Object>> columns = new ArrayList<>();
        for (String field : fields.keySet()) {
            List<Object> column = new ArrayList<>(rows.size());
            for (Document row : rows) {
                Object value = row.get(field);
                column.add("_id".equals(field) && value instanceof ObjectId oid
                        ? Map.of("$oid", oid.toHexString()) : value);
            }
            columns.add(column);
        }
        return new Page(count, rows.size(), columns);
    }

    record Page(long querySize, int dataSize, List<List<Object>> data) {
    }

    /**
     * Reads the complete document as canonical Extended JSON, preserving BSON types
     * and fields omitted from sampled table metadata when the user edits it.
     *
     * @param sessionId session owning the client
     * @param database database name
     * @param table collection name
     * @param id nonempty string or BSON ObjectId document identifier
     * @return canonical Extended JSON, or null when the document is missing
     */
    public String getDocument(String sessionId, String database, String table, Object id) {
        requireDocumentId(id);
        Session session = session(sessionId);
        synchronized (session) {
            Document document = client(session).getDatabase(database).getCollection(table)
                    .find(Filters.eq("_id", id)).first();
            if (document == null) {
                return null;
            }
            String json = document.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build());
            var codecs = MongoClientSettings.getDefaultCodecRegistry();
            if (!document.toBsonDocument(Document.class, codecs).equals(
                    parseDocument(json).toBsonDocument(Document.class, codecs))) {
                throw new IllegalArgumentException("This document cannot be represented safely for editing");
            }
            return json;
        }
    }

    /**
     * Replaces an existing document only if it still matches the fetched original.
     * This prevents a stale editor from silently overwriting concurrent changes.
     *
     * @param sessionId session owning the client
     * @param database database name
     * @param table collection name
     * @param originalJson original canonical Extended JSON
     * @param replacementJson edited Extended JSON
     * @return whether the original document was matched and replaced
     */
    public boolean replaceDocument(String sessionId, String database, String table,
            String originalJson, String replacementJson) {
        Document original = parseDocument(originalJson);
        Document replacement = parseDocument(replacementJson);
        Object id = original.get("_id");
        requireDocumentId(id);
        if (!id.equals(replacement.get("_id"))) {
            throw new IllegalArgumentException("The _id must not be changed");
        }
        var unchanged = unchangedDocument(original);
        Session session = session(sessionId);
        synchronized (session) {
            return client(session).getDatabase(database).getCollection(table)
                    .replaceOne(Filters.and(Filters.eq("_id", id), unchanged), replacement)
                    .getMatchedCount() == 1;
        }
    }

    /**
     * Archives and deletes a document transactionally by default. An explicit
     * archive-first mode supports standalone servers but cannot make both writes
     * atomic; ambiguous outcomes retain the archive for manual verification.
     *
     * @param sessionId session owning the client
     * @param database database name
     * @param table source collection name
     * @param id nonempty string or BSON ObjectId document identifier
     * @return whether a document was deleted and archived
     */
    public boolean deleteDocument(String sessionId, String database, String table, Object id) {
        requireDocumentId(id);
        Configuration settings = config.get();
        String prefix = settings.getString("mongo.deleted.prefix", "");
        String postfix = settings.getString("mongo.deleted.postfix", "");
        if ((prefix + postfix).isBlank()) {
            throw new IllegalArgumentException("An archive collection prefix or postfix is required for deletion");
        }
        String archive = prefix + table + postfix;
        if (archive.equals(table)) {
            throw new IllegalArgumentException("Archive collection must differ from the source");
        }
        Session session = session(sessionId);
        synchronized (session) {
            MongoClient client = client(session);
            var db = client.getDatabase(database);
            String mode = System.getProperty("dataape.delete.mode",
                    settings.getString("dataape.delete.mode", "transactional"));
            if ("archive-first".equals(mode)) {
                var concern = WriteConcern.MAJORITY.withJournal(true);
                var source = db.getCollection(table).withReadPreference(ReadPreference.primary()).withWriteConcern(concern);
                var destination = db.getCollection(archive).withReadPreference(ReadPreference.primary())
                        .withWriteConcern(concern);
                return archiveThenDelete(source, destination, id);
            }
            if (!"transactional".equals(mode)) {
                throw new IllegalArgumentException("Invalid dataape.delete.mode");
            }
            try (var transaction = client.startSession()) {
                return transaction.withTransaction(() -> {
                    Document document = db.getCollection(table)
                            .findOneAndDelete(transaction, Filters.eq("_id", id));
                    if (document == null) {
                        return false;
                    }
                    db.getCollection(archive).insertOne(transaction, document);
                    return true;
                });
            } catch (MongoException ex) {
                throw new DAArchiveException();
            }
        }
    }

    /**
     * Performs the explicitly opted-in standalone workflow. The matching delete
     * reduces stale-copy races but cannot replace a transaction: independent
     * writers and MongoDB's BSON comparison semantics still permit races. An
     * ambiguous delete retains the archive because the source may already be gone.
     */
    static boolean archiveThenDelete(MongoCollection<Document> source, MongoCollection<Document> archive, Object id) {
        Document original = source.find(Filters.eq("_id", id)).first();
        if (original == null) {
            return false;
        }
        try {
            archive.insertOne(original);
        } catch (MongoException ex) {
            if (ex.getCode() == 11000) {
                throw new DAArchiveException("Archive already contains this _id; source was not deleted.");
            }
            throw new DAArchiveException("Archive write failed or its outcome is unknown; source was not deleted. Verify the archive.");
        }

        var exact = Filters.and(Filters.eq("_id", id), unchangedDocument(original));
        var options = new DeleteOptions().collation(Collation.builder().locale("simple").build());
        final long deleted;
        try {
            deleted = source.deleteOne(exact, options).getDeletedCount();
        } catch (MongoException ex) {
            throw new DAArchiveException("Delete failed or its outcome is unknown; archive retained. Verify both collections.");
        }
        final Document remaining;
        try {
            remaining = source.find(Filters.eq("_id", id)).first();
        } catch (MongoException ex) {
            throw new DAArchiveException("Could not verify deletion; archive retained. Verify both collections.");
        }
        if (deleted == 1) {
            if (remaining != null) {
                throw new DAArchiveException("Document exists after deletion; archive retained. Verify both collections.");
            }
            return true;
        }
        if (remaining == null) {
            throw new DAArchiveException("Document disappeared without a confirmed deletion; archive retained. Verify both collections.");
        }
        try {
            if (archive.deleteOne(exact, options).getDeletedCount() != 1) {
                throw new DAArchiveException("Source was not deleted and the archive changed or disappeared. Verify both collections.");
            }
        } catch (MongoException ex) {
            throw new DAArchiveException("Source was not deleted; archive cleanup failed or its outcome is unknown. Verify both collections.");
        }
        throw new DAArchiveException("Source changed and was not deleted; temporary archive removed. Refresh and retry.");
    }

    private static Document unchangedDocument(Document original) {
        return new Document("$expr", new Document("$eq", List.of("$$ROOT", new Document("$literal", original))));
    }

    private static Document parseDocument(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Document JSON is required");
        }
        try {
            return Document.parse(json);
        } catch (BSONException | JsonParseException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid document Extended JSON");
        }
    }

    private static void requireDocumentId(Object id) {
        if (!(id instanceof ObjectId) && (!(id instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("A nonempty string or ObjectId _id is required");
        }
    }

    private static Map<String, Table> tableMap(DbDescription description) {
        Map<String, Table> tables = new TreeMap<>();
        for (Table table : description.tables) {
            tables.put(table.name, table);
        }
        return tables;
    }

    /**
     * Refreshes the expiry timestamp independently of the logging level.
     *
     * @param sessionId HTTP session ID
     * @param deadline epoch milliseconds at which the session expires
     */
    public void updateSession(String sessionId, long deadline) {
        while (true) {
            Session session;
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    throw new IllegalStateException("MongoDB adapter is closed");
                }
                session = sessions.computeIfAbsent(sessionId, key -> new Session());
            }
            synchronized (session) {
                if (closed.get()) {
                    throw new IllegalStateException("MongoDB adapter is closed");
                }
                if (!session.closed) {
                    session.deadline = deadline;
                    return;
                }
            }
            sessions.remove(sessionId, session);
        }
    }

    private Session session(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new DADatabaseConnectionException("DB not connected");
        }
        return session;
    }

    private static MongoClient client(Session session) {
        if (session.closed || session.client == null) {
            throw new DADatabaseConnectionException("DB not connected");
        }
        return session.client;
    }

    void cleanSessions() {
        long now = System.currentTimeMillis();
        sessions.forEach((id, session) -> {
            synchronized (session) {
                if (!session.closed && session.deadline <= now && sessions.remove(id, session)) {
                    session.closed = true;
                    if (session.client != null) {
                        session.client.close();
                    }
                }
            }
        });
    }

    /** Stops cleanup and closes every session-owned client after in-flight reads finish. */
    @Override
    public void close() {
        List<Session> snapshot;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            snapshot = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        scheduler.shutdownNow();
        for (Session session : snapshot) {
            synchronized (session) {
                if (!session.closed) {
                    session.closed = true;
                    if (session.client != null) {
                        session.client.close();
                    }
                }
            }
        }
    }

    private static class Session {
        long deadline;
        boolean closed;
        MongoClient client;
        final Map<String, Map<String, Table>> tables = new HashMap<>();
    }

    /** Collection name and sampled field types, matching the legacy response. */
    public static class Table {
        public String name;
        public Map<String, String> fieldMap;
    }

    /** Database name and discovered collections, matching the legacy response. */
    public static class DbDescription {
        public String name;
        public Collection<Table> tables = Collections.emptyList();
    }

    /** Signals that a session has no usable MongoDB client. */
    public static class DADatabaseConnectionException extends RuntimeException {
        public DADatabaseConnectionException(String message) {
            super(message);
        }
    }

    /** Reports an archive transaction failure without exposing database details. */
    public static class DAArchiveException extends RuntimeException {
        public DAArchiveException() {
            super("Archive transaction failed or its outcome is unknown; refresh and verify");
        }

        public DAArchiveException(String message) {
            super(message);
        }
    }
}
