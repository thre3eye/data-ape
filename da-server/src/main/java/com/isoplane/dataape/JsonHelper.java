package com.isoplane.dataape;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonHelper {

    static final Logger log = LoggerFactory.getLogger(DataApeServer.class);

    private static ObjectMapper mapper = new ObjectMapper().configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    private static TypeFactory tFactory = mapper.getTypeFactory();
    private static CollectionType selectListType = tFactory.constructCollectionType(List.class, SelectParam.class);
    private static CollectionType sortListType = tFactory.constructCollectionType(List.class, SortParam.class);

    static public List<SortParam> toSortParam(String json_) {
        if (json_ == null || json_.length() == 0)
            return null;
        try {
            List<SortParam> list = mapper.readValue(json_, sortListType);
            return list;
        } catch (Exception ex) {
            log.error(String.format("toSortParam: %s", json_), ex);
            return null;
        }
    }

    static public List<SelectParam> toSelectParam(String json_) {
        if (json_ == null || json_.length() == 0)
            return null;
        try {
            List<SelectParam> list = mapper.readValue(json_, selectListType);
            return list;
        } catch (Exception ex) {
            log.error(String.format("toSelectParam: %s", json_), ex);
            return null;
        }
    }

    static public String serialize(Object obj_) {
        if (obj_ == null)
            return null;
        try {
            String str = mapper.writeValueAsString(obj_);
            return str;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static class SortParam {
        public String key;
        public Integer dir;
    }

    public static class SelectParam {
        public String key;
        public String op;
        public Object val;
    }
}
