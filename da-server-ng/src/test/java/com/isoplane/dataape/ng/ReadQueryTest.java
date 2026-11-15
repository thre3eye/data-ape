package com.isoplane.dataape.ng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mongodb.MongoClientSettings;
import com.isoplane.dataape.ng.JsonHelper.ReadQuery;

class ReadQueryTest {
    @Test
    void parsesLegacyOperatorsAndPreservesSortOrder() {
        ReadQuery query = JsonHelper.parseQuery(Map.of(
                "select", "[{\"key\":\"score\",\"op\":\"gte\",\"val\":15},"
                        + "{\"key\":\"name\",\"op\":\"stw\",\"val\":\"A.*\"},"
                        + "{\"key\":\"active\",\"op\":\"exst\"}]",
                "sort", "[{\"key\":\"score\",\"dir\":\"-1\"},{\"key\":\"name\",\"dir\":1}]",
                "page", "2", "page_size", "10", "count_total", "false"));

        BsonDocument bson = query.filter().toBsonDocument(BsonDocument.class,
                MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(3, bson.getArray("$and").size());
        assertEquals(15, bson.getArray("$and").get(0).asDocument().getDocument("score").getInt32("$gte").getValue());
        assertTrue(bson.getArray("$and").get(1).asDocument()
                .getRegularExpression("name").getPattern().contains("\\QA.*\\E"));
        assertEquals("{\"score\": -1, \"name\": 1}", query.sort().toJson());
        assertEquals(10, query.skip());
        assertEquals(false, query.countTotal());
    }

    @Test
    void rejectsMalformedOrBroadeningInputs() {
        for (String selection : new String[] {
                "[", "{}", "[] []", "[{\"key\":\"x\",\"op\":\"unknown\",\"val\":1}]",
                "[{\"key\":\"x\",\"op\":\"eq\"}]", "[{\"key\":\"$where\",\"op\":\"eq\",\"val\":1}]",
                "[{\"key\":\"x\",\"op\":\"stw\",\"val\":42}]" }) {
            assertThrows(IllegalArgumentException.class, () -> JsonHelper.parseQuery(Map.of("select", selection)));
        }
        assertThrows(IllegalArgumentException.class, () -> JsonHelper.parseQuery(Map.of("sort", "[{\"key\":\"x\",\"dir\":0}]")));
        assertThrows(IllegalArgumentException.class, () -> JsonHelper.parseQuery(Map.of("page_size", "201")));
        assertThrows(IllegalArgumentException.class, () -> JsonHelper.parseQuery(Map.of("page", "2147483647")));
        assertThrows(IllegalArgumentException.class, () -> JsonHelper.parseQuery(Map.of("count_total", "yes")));
    }

    @ParameterizedTest
    @CsvSource({"gt,$gt", "gte,$gte", "ne,$ne", "lte,$lte", "lt,$lt"})
    void translatesComparisonOperators(String op, String bsonOp) {
        BsonDocument document = filter("[{\"key\":\"score\",\"op\":\"" + op + "\",\"val\":15}]");
        assertEquals(15, document.getArray("$and").get(0).asDocument()
                .getDocument("score").getInt32(bsonOp).getValue());
    }

    @Test
    void translatesEqualityAndExistence() {
        assertEquals(15, filter("[{\"key\":\"score\",\"op\":\"eq\",\"val\":15}]")
                .getArray("$and").get(0).asDocument().getInt32("score").getValue());
        assertTrue(filter("[{\"key\":\"score\",\"op\":\"exst\"}]")
                .getArray("$and").get(0).asDocument().getDocument("score").getBoolean("$exists").getValue());
        assertEquals(false, filter("[{\"key\":\"score\",\"op\":\"nxst\"}]")
                .getArray("$and").get(0).asDocument().getDocument("score").getBoolean("$exists").getValue());
    }

    @ParameterizedTest
    @CsvSource({"stw,^\\QA.*\\E", "ctn,.*\\QA.*\\E.*", "endw,\\QA.*\\E$", "excl,^(?!.*\\QA.*\\E).*"})
    void quotesLiteralTextInRegexOperators(String op, String expectedPattern) {
        var regex = filter("[{\"key\":\"name\",\"op\":\"" + op + "\",\"val\":\"A.*\"}]")
                .getArray("$and").get(0).asDocument().getRegularExpression("name");
        assertEquals(expectedPattern, regex.getPattern());
        assertTrue(regex.getOptions().contains("i"));
    }

    private static BsonDocument filter(String selection) {
        return JsonHelper.parseQuery(Map.of("select", selection)).filter()
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }
}
