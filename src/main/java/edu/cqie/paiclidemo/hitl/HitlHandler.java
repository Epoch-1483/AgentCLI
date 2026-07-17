package edu.cqie.paiclidemo.hitl;

/**
 * HITL 处理器接口 —— 定义人工审批的交互契约。
 * <p>
 * <b>职责</b>：展示审批请求 → 收集用户决策 → 返回结果。
 * <p>
 * <b>关键约定</b>：
 * <ul>
 *   <li>{@link #requestApproval(ApprovalRequest)} 是<b>阻塞调用</b>，
 *       等待用户做出决策后才返回</li>
 *   <li>"是否需要审批"的判断由 {@link ApprovalPolicy} 在上游完成，
 *       Handler 只负责展示和收集</li>
 *   <li>"全部批准"缓存由 Handler 管理，{@code /clear} 或新会话时清除</li>
 * </ul>
 * <p>
 * Demo 版本提供一个实现 {@link TerminalHitlHandler}，
 * 基于终端 BufferedReader/PrintStream 进行交互。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public interface HitlHandler {

    /**
     * 向用户展示审批请求并收集决策。
     * <p>
     * 此方法<b>阻塞</b>直到用户做出决策（y/a/n/s）。
     *
     * @param request 审批请求（包含工具名、参数、风险等级等）
     * @return 用户的审批决策
     */
    ApprovalResult requestApproval(ApprovalRequest request);

    /**
     * HITL 功能是否启用。
     * <p>
     * 关闭时所有工具调用直接放行，不弹审批。
     */
    boolean isEnabled();

    /**
     * 启用/关闭 HITL 功能。
     */
    void setEnabled(boolean enabled);

    /**
     * 检查指定工具是否已获得"全部批准"（会话级缓存）。
     * <p>
     * 用户选择 {@code [a] 全部批准} 后，后续同类工具调用不再弹审批。
     */
    boolean isApprovedAll(String toolName);

    /**
     * 清除所有"全部批准"缓存。
     * <p>
     * 在 {@code /clear} 命令或新会话开始时调用。
     */
    void clearApprovedAll();
}
