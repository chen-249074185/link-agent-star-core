package com.star.agent.workflow.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 工作流上下文：整条流水线共享的"料箱/背包"
// userId/traceId = 工单身份证（谁发起的、追踪号）；data = 节点间传数据的抽屉柜；executedNodes = 已跑过的工序清单
public class WorkflowContext {
    private final String userId;

    private final String traceId;

    // 中间数据：前一个节点 put 进去，后一个节点 get 出来，节点靠它传话
    private final Map<String,Object> data = new HashMap<>();

    // 执行栈：按顺序记录跑过哪些节点，出故障时看进度用
    private final List<String> executedNodes = new ArrayList<>();

    public WorkflowContext(String userId, String traceId) {
        this.userId = userId;
        this.traceId = traceId;
    }

    // 放料：把结果存进背包，钥匙是名字（如 "houses"）
    public void put(String key,Object value){
        data.put(key,value);
    }

    // 取料：按钥匙从背包拿前面节点放的东西
    public Object get(String key){
        return data.get(key);
    }

    // 记账：这个节点跑完了，把名字写进执行栈
    public void record(String nodeName){
        executedNodes.add(nodeName);
    }

    public List<String> getExecutedNodes() {
        return executedNodes;
    }

    public String getUserId(){
        return userId;
    }

    public String getTraceId() {
        return traceId;
    }
}
