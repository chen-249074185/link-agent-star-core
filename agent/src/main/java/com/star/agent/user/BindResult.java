package com.star.agent.user;

// 绑定邮箱结果：
//   bound=true        → 已绑定（或本来就是自己邮箱）
//   merged=true       → 本次触发并完成了账号合并（纯邮箱账号并入当前账号后绑定）
//   mergeToken!=null  → 邮箱被纯邮箱账号占用，需调 /api/user/confirm-merge 二次确认后才执行合并
public record BindResult(boolean bound, boolean merged, String mergeToken) {
}