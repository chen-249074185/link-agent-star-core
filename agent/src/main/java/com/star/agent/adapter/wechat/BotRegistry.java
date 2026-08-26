package com.star.agent.adapter.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 多 Bot 注册表：统一管理所有运行中的微信 Bot（botId → worker）
// 职责：登记启停、清点状态；多 Bot 并行时从这里统一管，不散落各处
@Component
public class BotRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BotRegistry.class);

    private final Map<String, BotWorker> workers = new ConcurrentHashMap<>();

    // 登记并启动一个 Bot；同 botId 重复登记=顶号（先停旧实例）
    public void start(String botId, BotWorker worker) {
        BotWorker prev = workers.put(botId, worker);
        if (prev != null && prev != worker) {
            prev.stop();
            logger.info("botId={} 重复登记，已停止旧实例", botId);
        }
        Thread thread = new Thread(worker, "bot-" + botId);
        thread.setDaemon(true);
        thread.start();
        logger.info("Bot 已登记并启动: {}", botId);
    }

    // 停止并注销一个 Bot
    public void stop(String botId) {
        BotWorker worker = workers.remove(botId);
        if (worker != null) {
            worker.stop();
            logger.info("Bot 已停止并注销: {}", botId);
        }
    }

    // 全部停止（应用关闭时）
    public void stopAll() {
        int n = workers.size();
        workers.values().forEach(BotWorker::stop);
        workers.clear();
        logger.info("全部 Bot 已停止（共 {} 个）", n);
    }

    // 当前登记的 botId 清单
    public List<String> botIds() {
        return List.copyOf(workers.keySet());
    }

    // 是否在运行
    public boolean isRunning(String botId) {
        return workers.containsKey(botId);
    }

    // 运行中数量
    public int size() {
        return workers.size();
    }
}