package com.star.agent.adapter;

// 断线重登回调：MessageDispatcher 检测到通道失效（receive 异常）时调用，
// 登录/恢复逻辑留在实现方（如 BotWorker），重登成功后重新 attach 通道即可继续
public interface ReconnectHandler {

    // 通道断开：实现方负责重新登录/恢复并让 adapter 换绑新连接；抛异常则本次重登失败（记日志继续等下次）
    void onDisconnected() throws Exception;
}