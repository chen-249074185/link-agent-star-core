package com.star.agent.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaBuilderTest {

    @Test
    void buildObjectSchema() {
        Map<String, Object> schema = JsonSchemaBuilder.object()
                .addString("city", "城市名", true)
                .build();

        assertEquals("object", schema.get("type"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("city"));
        assertEquals("string", ((Map<String, Object>) properties.get("city")).get("type"));
    }

    @Test
    void requiredOnlyContainsMarkedFields() {
        Map<String, Object> schema = JsonSchemaBuilder.object()
                .addString("city", "城市名", true)
                .addInteger("days", "未来天数", false)
                .build();

        assertEquals(List.of("city"), schema.get("required"));
    }

    @Test
    void noRequiredFieldOmitsRequiredKey() {
        Map<String, Object> schema = JsonSchemaBuilder.object()
                .addInteger("days", "未来天数", false)
                .build();

        assertFalse(schema.containsKey("required"));
    }
}