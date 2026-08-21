package com.star.agent.service;

// 解析出的完整租房需求：城市/预算/通勤上限
public record RentRequest(String city, int maxPrice, int maxCommute) {
}