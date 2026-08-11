package com.star.agent.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//既是仓库（存工具）又是柜台（派活）
@Component
public class ToolDispatcher {
    private final Map<String,ToolExecutor> toolMap = new HashMap<>();

    public ToolDispatcher(List<ToolExecutor> executors){
        for(ToolExecutor e : executors){
            toolMap.put(e.name(),e);
        }
    }

    public String dispatch(String toolName,String args){
        ToolExecutor executor = toolMap.get(toolName);
        if(executor == null){
            return "没有这个工具：" + toolName;
        }
        return  executor.execute(args);
    }
}
