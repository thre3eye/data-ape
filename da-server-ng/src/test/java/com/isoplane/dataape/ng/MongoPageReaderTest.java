package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.isoplane.dataape.ng.JsonHelper.ReadQuery;

class MongoPageReaderTest {
    @Test
    void uncountedFilteredQueryStillFetchesTheMatchingPageOnce() {
        MongoCollection<Document> collection = collection();
        FindIterable<Document> find = find(collection, List.of(new Document("score", 20).append("name", "Beta")));
        ReadQuery query = JsonHelper.parseQuery(Map.of(
                "select", "[{\"key\":\"score\",\"op\":\"gte\",\"val\":15}]",
                "count_total", "false", "page_size", "2"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "string");
        fields.put("score", "int");

        MongoDriver.Page page = MongoDriver.readPage(collection, fields, query);

        assertEquals(-1, page.querySize());
        assertEquals(1, page.dataSize());
        assertEquals(List.of(List.of("Beta"), List.of(20)), page.data());
        verify(collection, never()).countDocuments(any(Bson.class));
        verify(collection).find(query.filter());
        verify(find).skip(0);
        verify(find).limit(2);
    }

    @Test
    void zeroCountNeverTriggersAnUnfilteredFallback() {
        MongoCollection<Document> collection = collection();
        FindIterable<Document> find = find(collection, List.of());
        ReadQuery query = JsonHelper.parseQuery(Map.of("select", "[{\"key\":\"score\",\"op\":\"gt\",\"val\":100}]"));
        when(collection.countDocuments(query.filter())).thenReturn(0L);

        MongoDriver.Page page = MongoDriver.readPage(collection, Map.of("score", "int"), query);

        assertEquals(0, page.querySize());
        assertEquals(0, page.dataSize());
        assertEquals(List.of(List.of()), page.data());
        verify(collection).find(query.filter());
        verify(collection, never()).find();
        verify(find).into(any());
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> collection() {
        return mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private static FindIterable<Document> find(MongoCollection<Document> collection, List<Document> rows) {
        FindIterable<Document> find = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(find);
        when(find.skip(org.mockito.ArgumentMatchers.anyInt())).thenReturn(find);
        when(find.limit(org.mockito.ArgumentMatchers.anyInt())).thenReturn(find);
        when(find.into(any())).thenAnswer(invocation -> {
            List<Document> target = invocation.getArgument(0);
            target.addAll(rows);
            return target;
        });
        return find;
    }
}
