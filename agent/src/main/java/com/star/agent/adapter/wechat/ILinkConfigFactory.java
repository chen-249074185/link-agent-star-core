package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;

//打包微信 SDK 的连接"设置清单"
public class ILinkConfigFactory {
    public static ILinkConfig create() {
        return ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .writeTimeoutMs(35000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(1000)
                .retryMaxDelayMs(10000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .channelVersion("1.0.0")
                .build();
    }
}
