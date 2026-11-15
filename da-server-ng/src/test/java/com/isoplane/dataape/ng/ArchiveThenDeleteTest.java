package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.result.DeleteResult;

class ArchiveThenDeleteTest {
    private MongoCollection<Document> source;
    private MongoCollection<Document> archive;
    private FindIterable<Document> results;
    private final Document original = new Document("_id", "one").append("value", "original");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        source = mock(MongoCollection.class);
        archive = mock(MongoCollection.class);
        results = mock(FindIterable.class);
        when(source.find(any(Bson.class))).thenReturn(results);
    }

    @Test
    void cleansUpWhenDeleteDefinitelyDidNotMatchAndSourceRemains() {
        when(results.first()).thenReturn(original, new Document("_id", "one").append("value", "changed"));
        when(source.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenReturn(DeleteResult.acknowledged(0));
        when(archive.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenReturn(DeleteResult.acknowledged(1));
        var error = assertThrows(MongoDriver.DAArchiveException.class,
                () -> MongoDriver.archiveThenDelete(source, archive, "one"));
        assertTrue(error.getMessage().contains("temporary archive removed"));
        verify(archive).insertOne(original);
        verify(archive).deleteOne(any(Bson.class), any(DeleteOptions.class));
    }

    @Test
    void retainsArchiveWhenDeleteOutcomeIsUnknown() {
        when(results.first()).thenReturn(original);
        when(source.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenThrow(new MongoException("timeout"));
        var error = assertThrows(MongoDriver.DAArchiveException.class,
                () -> MongoDriver.archiveThenDelete(source, archive, "one"));
        assertTrue(error.getMessage().contains("archive retained"));
        verify(archive, never()).deleteOne(any(Bson.class), any(DeleteOptions.class));
    }

    @Test
    void retainsArchiveWhenSourceDisappearsWithoutConfirmedDelete() {
        when(results.first()).thenReturn(original, (Document) null);
        when(source.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenReturn(DeleteResult.acknowledged(0));
        var error = assertThrows(MongoDriver.DAArchiveException.class,
                () -> MongoDriver.archiveThenDelete(source, archive, "one"));
        assertTrue(error.getMessage().contains("archive retained"));
        verify(archive, never()).deleteOne(any(Bson.class), any(DeleteOptions.class));
    }

    @Test
    void reportsCleanupFailureWithoutPretendingItWasRemoved() {
        when(results.first()).thenReturn(original, original);
        when(source.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenReturn(DeleteResult.acknowledged(0));
        when(archive.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenThrow(new MongoException("timeout"));
        var error = assertThrows(MongoDriver.DAArchiveException.class,
                () -> MongoDriver.archiveThenDelete(source, archive, "one"));
        assertTrue(error.getMessage().contains("cleanup failed"));
    }

    @Test
    void retainsArchiveIfSourceIsPresentAfterAcknowledgedDeletion() {
        when(results.first()).thenReturn(original, original);
        when(source.deleteOne(any(Bson.class), any(DeleteOptions.class))).thenReturn(DeleteResult.acknowledged(1));
        var error = assertThrows(MongoDriver.DAArchiveException.class,
                () -> MongoDriver.archiveThenDelete(source, archive, "one"));
        assertTrue(error.getMessage().contains("archive retained"));
        verify(archive, never()).deleteOne(any(Bson.class), any(DeleteOptions.class));
    }
}
