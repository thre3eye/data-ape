package com.isoplane.dataape.ng;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isoplane.at.mongodb.ng.query.ATMongoWhereQuery;
import com.mongodb.client.model.Filters;

/**
 * Parses the legacy selection and sort JSON without silently dropping predicates.
 * Rejecting invalid predicates prevents malformed input from broadening a query.
 */
public final class JsonHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_PREDICATES = 50;

    private JsonHelper() {
    }

    /**
     * A bounded request; the total count is independent from reading the page.
     *
     * @param filter validated MongoDB predicate
     * @param sort ordered sort fields
     * @param page one-based page number
     * @param pageSize maximum page length
     * @param skip checked driver offset
     * @param countTotal whether to calculate a total
     */
    public record ReadQuery(Bson filter, Document sort, int page, int pageSize, int skip, boolean countTotal) {
    }

    /**
     * Parses request parameters without silently widening invalid requests.
     *
     * @param params query parameters
     * @return validated request
     * @throws IllegalArgumentException if a parameter is malformed or out of range
     */
    public static ReadQuery parseQuery(Map<String, String> params) {
        int page = positiveInt(params, "page", 1);
        int pageSize = positiveInt(params, "page_size", 50);
        if (pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page_size exceeds the maximum");
        }
        long offset = (long) pageSize * (page - 1);
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page offset is too large");
        }
        String countValue = params.getOrDefault("count_total", "true");
        if (!"true".equalsIgnoreCase(countValue) && !"false".equalsIgnoreCase(countValue)) {
            throw new IllegalArgumentException("count_total must be true or false");
        }

        List<Bson> predicates = new ArrayList<>();
        for (JsonNode selection : array(params.get("select"), "select")) {
            String field = field(selection);
            JsonNode opNode = selection.get("op");
            if (opNode == null || !opNode.isTextual()) {
                throw new IllegalArgumentException("select operator is required");
            }
            String op = opNode.textValue();
            JsonNode valueNode = selection.get("val");
            if ("exst".equals(op) || "nxst".equals(op)) {
                predicates.add(Filters.exists(field, "exst".equals(op)));
                continue;
            }
            if (valueNode == null || valueNode.isContainerNode()) {
                throw new IllegalArgumentException("select value must be a scalar");
            }
            Object value = scalar(valueNode);
            if ("excl".equals(op)) {
                if (!(value instanceof String text)) {
                    throw new IllegalArgumentException("text filter requires a string");
                }
                predicates.add(Filters.regex(field,
                        Pattern.compile("^(?!.*" + Pattern.quote(text) + ").*", Pattern.CASE_INSENSITIVE)));
                continue;
            }
            ATMongoWhereQuery predicate = new ATMongoWhereQuery();
            switch (op) {
                case "gt" -> predicate.gt(field, value);
                case "gte" -> predicate.gte(field, value);
                case "eq" -> predicate.eq(field, value);
                case "ne" -> predicate.ne(field, value);
                case "lte" -> predicate.lte(field, value);
                case "lt" -> predicate.lt(field, value);
                case "stw" -> predicate.startsWith(field, text(value));
                case "ctn" -> predicate.contains(field, text(value));
                case "endw" -> predicate.endsWith(field, text(value));
                default -> throw new IllegalArgumentException("unsupported select operator");
            }
            predicates.add(predicate.getQuery());
        }

        Document sort = new Document();
        for (JsonNode item : array(params.get("sort"), "sort")) {
            String field = field(item);
            JsonNode direction = item.get("dir");
            String dir = direction == null ? "" : direction.asText();
            if (!"1".equals(dir) && !"-1".equals(dir)) {
                throw new IllegalArgumentException("sort direction must be 1 or -1");
            }
            if (sort.containsKey(field)) {
                throw new IllegalArgumentException("duplicate sort field");
            }
            sort.append(field, Integer.parseInt(dir));
        }

        Bson filter = predicates.isEmpty() ? new BsonDocument() : Filters.and(predicates);
        return new ReadQuery(filter, sort, page, pageSize, (int) offset, Boolean.parseBoolean(countValue));
    }

    static List<JsonNode> selections(String json) {
        List<JsonNode> result = new ArrayList<>();
        array(json, "select").forEach(result::add);
        return result;
    }

    private static int positiveInt(Map<String, String> params, String key, int fallback) {
        try {
            int value = Integer.parseInt(params.getOrDefault(key, Integer.toString(fallback)));
            if (value < 1) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer", ex);
        }
    }

    private static JsonNode array(String json, String name) {
        if (json == null || json.isEmpty()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode value = MAPPER.readTree(json);
            if (value == null || !value.isArray() || value.size() > MAX_PREDICATES) {
                throw new IllegalArgumentException(name + " must be a bounded array");
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid " + name + " JSON");
        }
    }

    private static String field(JsonNode item) {
        JsonNode node = item.get("key");
        if (!item.isObject() || node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        String key = node.textValue();
        for (String part : key.split("\\.", -1)) {
            if (part.isEmpty() || part.startsWith("$") || part.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid field name");
            }
        }
        return key;
    }

    private static Object scalar(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        throw new IllegalArgumentException("select value must be a scalar");
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("text filter requires a string");
        }
        return text;
    }
}
