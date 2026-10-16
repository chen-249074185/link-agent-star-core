package com.star.agent.travel.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 游记：travel_log 表
// 发布游记 → 足迹点入地图 → 该省点亮；删除 → 无该省游记则熄灭
public class TravelLogDo {

    private Long id;
    private String userId;         // 归属（多用户隔离）
    private String province;       // 省份（点亮地图的 key）
    private String city;           // 城市（可空）
    private String locationName;   // 具体地点名（可空）
    private Double lng;            // 经度（可空，缺省回退省级行政中心坐标）
    private Double lat;            // 纬度（可空）
    private String content;        // 游记正文
    private String imgList;        // 图片 URL JSON 数组（可空）
    private LocalDate travelDate;  // 旅行日期（可空=发布当天）
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImgList() {
        return imgList;
    }

    public void setImgList(String imgList) {
        this.imgList = imgList;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(LocalDate travelDate) {
        this.travelDate = travelDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}