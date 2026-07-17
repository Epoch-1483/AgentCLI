package edu.cqie.paiclidemo.hitl;

/**
 * 审批结果 —— 用户对一次工具调用审批的决策。
 * <p>
 * <b>决策类型</b>：
 * <ul>
 *   <li>{@code APPROVED} — 批准执行，使用原始参数</li>
 *   <li>{@code APPROVED_ALL} — 批准本次会话所有后续同类工具操作（批量放行）</li>
 *   <li>{@code REJECTED} — 拒绝执行，Agent 收到拒绝通知后可重新规划</li>
 *   <li>{@code SKIPPED} — 跳过本步骤，继续后续操作</li>
 * </ul>
 * <p>
 * 相比原版 paiCLI 简化了 APPROVED_ALL_BY_SERVER 和 MODIFIED 两种决策，
 * Demo 项目不涉及 MCP server 粒度管理和参数修改场景。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public record ApprovalResult(
        Decision decision,
        String reason
) {

    /** 决策枚举 */
    public enum Decision {
        /** 批准本次执行 */
        APPROVED,
        /** 批准本次会话所有后续同类工具 */
        APPROVED_ALL,
        /** 拒绝执行 */
        REJECTED,
        /** 跳过本步骤 */
        SKIPPED
    }

    // ==================== 工厂方法 ====================

    /** 批准执行 */
    public static ApprovalResult approve() {
        return new ApprovalResult(Decision.APPROVED, null);
    }

    /** 批准本次会话所有后续同类工具操作 */
    public static ApprovalResult approveAll() {
        return new ApprovalResult(Decision.APPROVED_ALL, null);
    }

    /** 拒绝执行（附带原因） */
    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(Decision.REJECTED, reason);
    }

    /** 跳过本步骤 */
    public static ApprovalResult skip() {
        return new ApprovalResult(Decision.SKIPPED, null);
    }

    // ==================== 便捷查询 ====================

    /** 是否为批准类决策（APPROVED 或 APPROVED_ALL） */
    public boolean isApproved() {
        return decision == Decision.APPROVED || decision == Decision.APPROVED_ALL;
    }

    /** 是否为全部批准 */
    public boolean isApprovedAll() {
        return decision == Decision.APPROVED_ALL;
    }

    /** 是否被拒绝 */
    public boolean isRejected() {
        return decision == Decision.REJECTED;
    }

    /** 是否被跳过 */
    public boolean isSkipped() {
        return decision == Decision.SKIPPED;
    }
}
