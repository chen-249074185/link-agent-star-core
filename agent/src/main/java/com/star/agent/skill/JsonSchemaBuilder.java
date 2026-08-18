package com.star.agent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonSchemaBuilder {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new java.util.ArrayList<>();

    public static JsonSchemaBuilder object() {
        return new JsonSchemaBuilder();
    }

    public JsonSchemaBuilder addString(String name, String desc, boolean isRequired) {
        properties.put(name, field("string", desc));
        if (isRequired) required.add(name);
        return this;
    }

    public JsonSchemaBuilder addInteger(String name, String desc, boolean isRequired) {
        properties.put(name, field("integer", desc));
        if (isRequired) required.add(name);
        return this;
    }

    private Map<String, Object> field(String type, String desc) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        if (desc != null) f.put("description", desc);
        return f;
    }

    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}