package com.star.agent.user;

// 登录/邮箱注册成功返回值：token（前端存 localStorage 带 Authorization 头）+ userId（本人身份）
public record AuthResult(String token, String userId) {
}