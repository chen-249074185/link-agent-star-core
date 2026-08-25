package com.star.agent.adapter;

// URL 图片下载器合同（ReplySender 发图片/地图用）：可注入实现，测试 mock 成功/失败
public interface ImageFetcher {

    // 下载 URL 字节；失败抛异常（调用方降级纯文本）
    byte[] fetch(String url) throws Exception;
}