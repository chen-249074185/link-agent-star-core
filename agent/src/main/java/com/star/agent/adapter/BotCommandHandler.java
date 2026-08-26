package com.star.agent.adapter;

// 聊天命令处理合同：消息进 AI 前先问一句"这是不是命令"（如 #newbot/#logout）
// handle 返回 null = 不是命令，走正常对话；返回非 null = 命令已处理，直接作为回复发出
// 可空注入：通道不需要命令时传 null（通道抽象与具体命令解耦）
public interface BotCommandHandler {

    String handle(String text, String userId);
}