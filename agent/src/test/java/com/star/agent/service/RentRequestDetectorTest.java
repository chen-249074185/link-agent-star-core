package com.star.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RentRequestDetectorTest {

    private final RentRequestDetector detector = new RentRequestDetector();

    @Test
    void parse_fullRequest_returnsAllThree() {
        RentRequest req = detector.parse("在北京租房，预算6000，通勤30分钟内");

        assertEquals("北京", req.city());
        assertEquals(6000, req.maxPrice());
        assertEquals(30, req.maxCommute());
    }

    @Test
    void parse_anotherCity_works() {
        RentRequest req = detector.parse("在上海租房子，预算5000，通勤40分钟");

        assertEquals("上海", req.city());
        assertEquals(5000, req.maxPrice());
        assertEquals(40, req.maxCommute());
    }

    @Test
    void parse_plainChat_returnsNull() {
        assertNull(detector.parse("你好"));
        assertNull(detector.parse("北京天气"));
    }

    @Test
    void parse_missingCommute_returnsNull() {
        assertNull(detector.parse("在北京租房，预算6000"));
    }

    @Test
    void parse_missingPrice_returnsNull() {
        assertNull(detector.parse("在北京租房，通勤30分钟"));
    }
}