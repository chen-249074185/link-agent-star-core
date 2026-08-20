package com.star.agent.governance;

// 检查结果：放行 or 拒绝（带理由）
public record CheckResult(
        boolean allowed,   // true=放行 false=拒绝
        String reason      // 拒绝时的原因（给用户看的提示）
) {
    public static CheckResult pass() {            // 放行
        return new CheckResult(true, "");
    }
    public static CheckResult deny(String reason) { // 拒绝
        return new CheckResult(false, reason);
    }
}
