package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.ListCollectionNamesIterable;

class MongoDriverTest {
    @Test
    void reconnectFailurePreservesOldClientAndExpiryClosesIt() {
        MongoClient oldClient = client();
        MongoClient failedClient = mock(MongoClient.class);
        when(failedClient.getDatabase("example")).thenThrow(new IllegalStateException("synthetic failure"));
        AtomicInteger created = new AtomicInteger();
        BaseConfiguration config = config();
        try (MongoDriver driver = new MongoDriver(config,
                uri -> created.getAndIncrement() == 0 ? oldClient : failedClient)) {
            driver.updateSession("a", Long.MAX_VALUE);
            assertEquals("example", driver.connect("a", "mongodb://fixture:27017/example").name);
            assertThrows(IllegalStateException.class,
                    () -> driver.connect("a", "mongodb://fixture:27017/example"));
            verify(failedClient).close();
            verify(oldClient, never()).close();
            assertEquals("example", driver.getTables("a", "example").name);
            driver.updateSession("a", 0);
            driver.cleanSessions();
            verify(oldClient).close();
            assertThrows(MongoDriver.DADatabaseConnectionException.class, () -> driver.getTables("a", "example"));
        }
    }

    @Test
    void separateSessionsOwnSeparateClientsAndTargetsMustBeAllowed() {
        MongoClient first = client();
        MongoClient second = client();
        AtomicInteger created = new AtomicInteger();
        try (MongoDriver driver = new MongoDriver(config(), uri -> created.getAndIncrement() == 0 ? first : second)) {
            driver.updateSession("a", Long.MAX_VALUE);
            driver.updateSession("b", Long.MAX_VALUE);
            driver.connect("a", "mongodb://fixture:27017/example");
            driver.connect("b", "mongodb://fixture:27017/example");
            assertThrows(IllegalArgumentException.class,
                    () -> driver.connect("a", "mongodb://not-allowed:27017/example"));
            driver.updateSession("a", 0);
            driver.cleanSessions();
            verify(first).close();
            verify(second, never()).close();
            assertEquals("example", driver.getTables("b", "example").name);
        }
        verify(second).close();
    }

    private static BaseConfiguration config() {
        var config = new BaseConfiguration();
        config.setProperty("mongo.allowed.hosts", List.of("fixture:27017"));
        return config;
    }

    private static MongoClient client() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase db = mock(MongoDatabase.class);
        ListCollectionNamesIterable names = mock(ListCollectionNamesIterable.class);
        when(client.getDatabase("example")).thenReturn(db);
        when(db.listCollectionNames()).thenReturn(names);
        when(names.into(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return client;
    }
}
