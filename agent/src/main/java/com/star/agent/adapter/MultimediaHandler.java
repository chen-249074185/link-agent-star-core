package com.star.agent.adapter;

import com.star.agent.message.AgentMessage;

// 多媒体消息处理合同：图片/语音等非纯文本消息在进 AI 前先问一句"能否直接处理"
// handle 可自行用 adapter 发图/发语音；返回 null = 已处理完毕（无需再回文本），返回非 null = 回复文本由调用方发出
// 可空注入：通道/场景不需要时传 null（与 BotCommandHandler 同模式）
public interface MultimediaHandler {

    String handle(AgentMessage msg, MessageAdapter adapter) throws Exception;
}