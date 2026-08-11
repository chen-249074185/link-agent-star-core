package com.star.agent.tool;

import java.util.Map;

//工具叫什么名字
public interface ToolExecutor {
     String name();

     String execute(String args);

     String description();

     Map<String,Object> parameters();
}
