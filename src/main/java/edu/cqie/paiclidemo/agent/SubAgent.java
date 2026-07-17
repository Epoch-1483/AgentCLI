package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 子代理 —— 可配置角色的轻量 Agent。
 * <p>
 * 每个 SubAgent 有独立的：
 * <ul>
 *   <li>名称（如 "worker-1"、"planner"、"reviewer"）</li>
 *   <li>角色（{@link AgentRole}）→ 决定系统提示词和是否可以使用工具</li>
 *   <li>对话历史（任务间可清空，系统提示词保留）</li>
 * </ul>
 * <p>
 * 共享的：
 * <ul>
 *   <li>{@link LlmClient} — 所有子代理调用同一个 LLM 服务</li>
 *   <li>{@link ToolRegistry} — 所有子代理共享工具集（但只有 WORKER 会实际调用）</li>
 * </ul>
 * <p>
 * <b>核心设计：角色决定行为</b>
 * <pre>
 *  ┌──────────┬──────────────┬──────────────────────────────┐
 *  │ 角色      │ 使用工具？    │ 输出形式                      │
 *  ├──────────┼──────────────┼──────────────────────────────┤
 *  │ PLANNER  │ ❌            │ JSON 执行计划                 │
 *  │ WORKER   │ ✅（ReAct）   │ 执行结果文本                   │
 *  │ REVIEWER │ ❌            │ JSON 审查结论                  │
 *  └──────────┴──────────────┴──────────────────────────────┘
 * </pre>
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    /** 子代理内部 ReAct 循环的最大迭代次数 */
    private static final int MAX_ITERATIONS = 5;

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;

    // ==================== 构造方法 ====================

    /**
     * 创建子代理。
     *
     * @param name         名称（用于日志和输出展示）
     * @param role         角色（决定系统提示词和工具权限）
     * @param llmClient    LLM 客户端（共享）
     * @param toolRegistry 工具注册表（共享，但只有 WORKER 会调用）
     */
    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(LlmClient.Message.system(getSystemPrompt()));
    }

    // ==================== 核心方法 ====================

    /**
     * 执行任务，将流式输出写入指定 PrintStream（默认 System.out）。
     */
    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    /**
     * 执行任务并将输出写入指定 PrintStream。
     * <p>
     * 并发执行时，编排器为每个步骤传入独立的 PrintStream（缓冲），
     * 避免多个 Agent 同时写入 System.out 造成输出交错。
     * <p>
     * <b>流程</b>：
     * <ol>
     *   <li>将任务内容注入对话历史</li>
     *   <li>调用 LLM（WORKER 传入工具定义，其他角色不传）</li>
     *   <li>如果 LLM 请求调用工具 → 执行工具 → 继续循环</li>
     *   <li>如果 LLM 返回纯文本 → 作为 RESULT 返回</li>
     * </ol>
     */
    public AgentMessage execute(AgentMessage task, PrintStream out) {
        log.info("[{}] executing task: type={}", name, task.type());

        // 将任务注入对话
        conversationHistory.add(LlmClient.Message.user(task.content()));

        // 只有 WORKER 传工具定义
        List<LlmClient.Tool> toolDefs = shouldUseTools()
                ? toolRegistry.getToolDefinitions() : null;

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            try {
                LlmClient.ChatResponse response = llmClient.chat(conversationHistory, toolDefs);

                if (response.hasToolCalls()) {
                    // WORKER 的工具调用分支
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.content(), response.toolCalls()));

                    for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                        String toolName = toolCall.function().name();
                        out.println("  🔧 [" + name + "] 调用工具: " + toolName);

                        String result = toolRegistry.executeTool(toolCall);
                        conversationHistory.add(LlmClient.Message.tool(toolCall.id(), result));
                    }
                    // 继续循环，让 LLM 根据工具结果继续推理
                } else {
                    // LLM 返回最终结果
                    conversationHistory.add(LlmClient.Message.assistant(response.content()));
                    return AgentMessage.result(name, role, response.content());
                }
            } catch (IOException e) {
                log.error("[{}] LLM call failed", name, e);
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }
        }

        // 达到最大迭代
        log.warn("[{}] reached max iterations ({})", name, MAX_ITERATIONS);
        return AgentMessage.error(name, role,
                "达到最大迭代次数 (" + MAX_ITERATIONS + ")，任务未完成");
    }

    /**
     * 执行任务（带上下文注入）。
     * <p>
     * 编排器在分配步骤时，会将前序步骤的结果作为上下文注入，
     * 让 WORKER 了解已完成的依赖步骤。
     *
     * @param task    任务消息
     * @param context 前序步骤的上下文（可为 null 或空）
     * @param out     输出流
     */
    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(
                task.fromAgent(), task.fromRole(), enrichedContent, task.type());
        return execute(enrichedTask, out);
    }

    // ==================== REVIEWER 专用 ====================

    /**
     * 审查执行结果（仅 REVIEWER 角色使用）。
     * <p>
     * 将原始任务描述 + 执行结果拼接为审查输入，交给 LLM 判断是否通过。
     *
     * @param originalTask    原始任务描述
     * @param executionResult 执行结果
     * @param out             输出流
     * @return APPROVAL / REJECTION / ERROR 消息
     */
    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
        return execute(reviewTask, out);
    }

    // ==================== 历史管理 ====================

    /**
     * 清空对话历史（保留系统提示词），准备处理下一个独立任务。
     * <p>
     * 防止上一个任务的上下文"污染"下一个任务的执行。
     */
    public void clearHistory() {
        LlmClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    // ==================== 系统提示词 ====================

    /**
     * 根据角色返回对应的系统提示词。
     * <p>
     * 三个角色的提示词分别指导 LLM 以不同方式处理输入：
     * <ul>
     *   <li>PLANNER — 输出 JSON 格式的执行计划（steps + dependencies）</li>
     *   <li>WORKER — 使用工具完成具体操作</li>
     *   <li>REVIEWER — 输出 JSON 格式的审查结论（approved + issues）</li>
     * </ul>
     */
    String getSystemPrompt() {
        return switch (role) {
            case PLANNER -> PLANNER_PROMPT;
            case WORKER -> WORKER_PROMPT;
            case REVIEWER -> REVIEWER_PROMPT;
        };
    }

    /**
     * 只有 WORKER 角色可以调用工具。
     * <p>
     * PLANNER 只输出分析文本（JSON 计划），REVIEWER 只输出审查结论，
     * 它们都不需要也不应该调用外部工具。
     */
    private boolean shouldUseTools() {
        return role == AgentRole.WORKER;
    }

    // ==================== Getters ====================

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    // ==================== 角色提示词常量 ====================

    private static final String PLANNER_PROMPT =
            "你是一个任务规划专家。你的职责是分析用户的需求，将其拆解为可执行的子任务。\n\n"
                    + "你必须输出一个 JSON 格式的执行计划，格式如下：\n"
                    + "```json\n"
                    + "{\n"
                    + "  \"steps\": [\n"
                    + "    { \"id\": \"1\", \"description\": \"步骤描述\", \"type\": \"COMMAND\", \"dependencies\": [] },\n"
                    + "    { \"id\": \"2\", \"description\": \"步骤描述\", \"type\": \"ANALYSIS\", \"dependencies\": [\"1\"] }\n"
                    + "  ]\n"
                    + "}\n"
                    + "```\n\n"
                    + "规则：\n"
                    + "1. 每个步骤必须有明确的 description\n"
                    + "2. type 可选值：COMMAND（执行命令）、ANALYSIS（分析）、FILE_READ（读文件）、FILE_WRITE（写文件）、VERIFICATION（验证）\n"
                    + "3. dependencies 是该步骤依赖的前序步骤 id 列表（无依赖则为空数组）\n"
                    + "4. 相互独立的步骤不要设置依赖关系，这样可以并行执行\n"
                    + "5. 只输出 JSON，不要输出其他内容\n"
                    + "请用中文描述步骤。";

    private static final String WORKER_PROMPT =
            "你是一个任务执行者。请使用提供的工具来完成具体操作。\n\n"
                    + "工作规则：\n"
                    + "1. 仔细阅读任务描述和前序步骤的结果\n"
                    + "2. 如果需要计算或获取信息，主动使用工具\n"
                    + "3. 完成后直接输出结果，不需要额外解释\n"
                    + "请用中文回复。";

    private static final String REVIEWER_PROMPT =
            "你是一个结果审查专家。你的职责是检查任务执行结果的质量和正确性。\n\n"
                    + "你必须输出一个 JSON 格式的审查结论，格式如下：\n"
                    + "```json\n"
                    + "{\n"
                    + "  \"approved\": true,\n"
                    + "  \"summary\": \"审查总结\",\n"
                    + "  \"issues\": [],\n"
                    + "  \"suggestions\": []\n"
                    + "}\n"
                    + "```\n\n"
                    + "审查标准：\n"
                    + "1. 结果是否正确回答了原始任务的问题\n"
                    + "2. 数据是否准确（如果有计算，检查计算结果）\n"
                    + "3. 是否遗漏了重要的边界情况\n\n"
                    + "如果结果合格，approved 设为 true；如果不合格，设为 false 并在 issues 中说明问题。\n"
                    + "只输出 JSON，不要输出其他内容。";
}
