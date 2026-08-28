package com.star.agent.adapter.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 根路径重定向到登录页：访问 / 先进 login.html（登录后跳 index.html 对话页）
@Controller
public class RootController {

    @GetMapping("/")
    public String root() {
        return "redirect:/login.html";
    }
}