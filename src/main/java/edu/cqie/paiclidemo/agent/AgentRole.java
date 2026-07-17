package edu.cqie.paiclidemo.agent;

/**
 * Agent 角色定义 —— Multi-Agent 系统中的角色分工。
 * <p>
 * 在多 Agent 协作模式中，不同的 SubAgent 承担不同的职责：
 * <ul>
 *   <li>{@link #PLANNER} — 负责分析任务、拆解执行计划</li>
 *   <li>{@link #WORKER} — 负责执行具体步骤，可以调用工具</li>
 *   <li>{@link #REVIEWER} — 负责检查执行结果质量，通过或打回重做</li>
 * </ul>
 * <p>
 * 每个角色有独立的系统提示词（见 {@link SubAgent#getSystemPrompt()}），
 * 指导 LLM 在该角色下应有的行为方式。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public enum AgentRole {

    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成实际操作"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
