package edu.cqie.paiclidemo.agent;

/**
 * Agent 间通信消息 —— Multi-Agent 协作的基本通信单元。
 * <p>
 * 在编排器（Orchestrator）和子代理（SubAgent）之间传递的所有信息，
 * 都封装为 {@code AgentMessage} 对象。每条消息包含：
 * <ul>
 *   <li>{@code fromAgent} — 发送者名称（如 "orchestrator"、"worker-1"、"reviewer"）</li>
 *   <li>{@code fromRole} — 发送者角色（PLANNER / WORKER / REVIEWER，编排器为 null）</li>
 *   <li>{@code content} — 消息正文</li>
 *   <li>{@code type} — 消息类型（6 种，见下文）</li>
 * </ul>
 * <p>
 * <b>消息类型说明</b>：
 * <pre>
 *  ┌───────────┬──────────────────────────────────────────────┐
 *  │ TASK      │ 编排器 → 子代理：分配任务                      │
 *  │ RESULT    │ 子代理 → 编排器：返回执行结果                  │
 *  │ FEEDBACK  │ 检查者 → 编排器：审查反馈（含改进建议）         │
 *  │ APPROVAL  │ 检查者 → 编排器：审查通过                      │
 *  │ REJECTION │ 检查者 → 编排器：审查拒绝，需重新执行           │
 *  │ ERROR     │ 子代理 → 编排器：执行中遭遇系统级错误           │
 *  └───────────┴──────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 提供 6 个静态工厂方法（{@code task}、{@code result}、{@code feedback}、
 * {@code approval}、{@code rejection}、{@code error}），简化消息创建。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public record AgentMessage(String fromAgent, AgentRole fromRole, String content, Type type) {

    /**
     * 消息类型枚举。
     */
    public enum Type {
        TASK,       // 任务分配
        RESULT,     // 执行结果
        FEEDBACK,   // 审查反馈
        APPROVAL,   // 审查通过
        REJECTION,  // 审查拒绝
        ERROR       // 系统错误
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建任务消息（编排器 → 子代理）。
     */
    public static AgentMessage task(String fromAgent, String content) {
        return new AgentMessage(fromAgent, null, content, Type.TASK);
    }

    /**
     * 创建结果消息（子代理 → 编排器）。
     */
    public static AgentMessage result(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, Type.RESULT);
    }

    /**
     * 创建反馈消息（检查者 → 编排器）。
     */
    public static AgentMessage feedback(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.FEEDBACK);
    }

    /**
     * 创建审批通过消息。
     */
    public static AgentMessage approval(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.APPROVAL);
    }

    /**
     * 创建拒绝消息（检查者认为结果不合格，需重新执行）。
     */
    public static AgentMessage rejection(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.REJECTION);
    }

    /**
     * 创建错误消息（子代理在执行过程中遇到系统级错误）。
     */
    public static AgentMessage error(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, Type.ERROR);
    }
}
