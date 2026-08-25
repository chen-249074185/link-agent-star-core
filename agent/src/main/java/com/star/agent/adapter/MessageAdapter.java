package com.star.agent.adapter;

import com.star.agent.message.AgentMessage;

// 通道抽象：任何接入端实现这四个方法即可接入系统
// 微信=ILinkMessageAdapter；企微/其他端未来按同一合同实现（详见 docs/ADAPTER.md）
public interface MessageAdapter {

    // 收消息：轮询通道，有消息返回一条内部统一格式，没有返回 null（调用方自行决定间隔）
    AgentMessage receive() throws Exception;

    // 发文本回复
    void send(String userId, String text) throws Exception;

    // 发图片（byte[] 原始字节，fileName 带扩展名，caption 图片说明可空）
    void sendImage(String userId, byte[] imageBytes, String fileName, String caption) throws Exception;

    // 发文件
    void sendFile(String userId, byte[] fileBytes, String fileName, String caption) throws Exception;
}