package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.memory.ConversationHistoryCompactor;
import edu.cqie.paiclidemo.memory.MemoryManager;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent —— 智能体的核心引擎。
 * <p>
 * 实现经典的 ReAct（Reasoning + Acting）循环：
 * <pre>
 *   用户输入 → LLM 思考 → [需要工具?] → 执行工具 → 观察结果 → LLM 继续思考 → ... → 最终回复
 * </pre>
 * <p>
 * Memory 集成：
 * - 可选的 MemoryManager 提供短期记忆（自动压缩）和长期记忆（跨会话持久化）
 * - ConversationHistoryCompactor 压缩实际的 LLM 消息列表
 * - 每次 LLM 调用前注入相关长期记忆上下文
 * <p>
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /** 安全阈值：最大迭代次数，防止模型陷入死循环 */
    private static final int MAX_ITERATIONS = 10;

    /** 对话历史压缩的 token 触发阈值 */
    private static final int COMPACT_TRIGGER_TOKENS = 6000;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;   // 可选，null = 无记忆
    private final ConversationHistoryCompactor historyCompactor;

    /** 对话历史 —— 在整个会话过程中持续累积 */
    private final List<LlmClient.Message> conversationHistory = new ArrayList<>();

    /** 基础系统提示词 */
    private static final String BASE_SYSTEM_PROMPT =
            "你是一个智能助手，可以使用提供的工具来帮助用户解决问题。"
                    + "当你需要获取信息、执行计算或完成特定任务时，请主动使用工具。"
                    + "每次调用工具后，根据工具返回的结果继续推理，直到得出最终答案。"
                    + "请用中文回复。";

    /**
     * 带记忆的构造方法。
     *
     * @param llmClient     LLM 客户端
     * @param toolRegistry  工具注册中心
     * @param memoryManager 记忆管理器（可选，null = 无记忆功能）
     */
    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.historyCompactor = memoryManager != null
                ? new ConversationHistoryCompactor(llmClient) : null;

        // 初始化系统提示词
        rebuildSystemPrompt("");
    }

    /**
     * 无记忆的构造方法（向后兼容）。
     */
    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null);
    }

    // ==================== 核心方法：ReAct 循环 ====================

    /**
     * 处理用户输入，执行 ReAct 循环，返回最终回复。
     * <p>
     * 流程详解：
     * <ol>
     *   <li>将用户消息加入对话历史</li>
     *   <li>调用 LLM，传入完整对话历史 + 可用工具列表</li>
     *   <li>检查 LLM 响应：
     *       <ul>
     *         <li>如果包含 tool_calls → 执行工具，将结果加入历史，回到第 2 步</li>
     *         <li>如果不包含 tool_calls → 这就是最终回复，退出循环</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param userInput 用户输入的文本
     * @return LLM 的最终回复文本
     */
    public String run(String userInput) throws IOException {
        // ① 将用户消息加入对话历史
        conversationHistory.add(LlmClient.Message.user(userInput));

        // 记忆集成：记录用户消息 + 注入长期记忆上下文
        if (memoryManager != null) {
            memoryManager.addUserMessage(userInput);

            // 将相关长期记忆注入 system prompt
            String memoryContext = memoryManager.buildMemoryContext(userInput, 500);
            rebuildSystemPrompt(memoryContext);

            // 检查并触发对话历史压缩
            if (historyCompactor != null) {
                historyCompactor.compactIfNeeded(conversationHistory, COMPACT_TRIGGER_TOKENS);
            }
        }

        // 获取工具定义列表（每轮迭代共用）
        List<LlmClient.Tool> toolDefs = toolRegistry.getToolDefinitions();

        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        // ② 进入 ReAct 循环
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            log.info("===== ReAct 迭代 {}/{} =====", iteration, MAX_ITERATIONS);

            // ③ 调用 LLM
            LlmClient.ChatResponse response = llmClient.chat(conversationHistory, toolDefs);

            // 累计 token 用量
            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();

            // ④ 判断：LLM 是否请求调用工具？
            if (response.hasToolCalls()) {
                // --- 有工具调用 → Act + Observe 阶段 ---
                log.info("LLM 请求调用 {} 个工具", response.toolCalls().size());

                // 将 assistant 消息（含 tool_calls）加入历史
                conversationHistory.add(LlmClient.Message.assistant(
                        response.content(),
                        response.toolCalls()
                ));

                // 逐个执行工具，收集结果
                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    log.info("  → 调用工具: {}({})",
                            toolCall.function().name(),
                            toolCall.function().arguments());

                    // 通过 ToolRegistry 执行工具
                    String result = toolRegistry.executeTool(toolCall);

                    log.info("  ← 工具结果: {} chars", result.length());

                    // 将工具执行结果作为 tool 消息加入历史
                    conversationHistory.add(LlmClient.Message.tool(
                            toolCall.id(),
                            result
                    ));

                    // 记忆集成：记录工具结果
                    if (memoryManager != null) {
                        memoryManager.addToolResult(toolCall.function().name(), result);
                    }
                }

                // 继续循环 → 让 LLM 根据工具结果继续推理

            } else {
                // --- 无工具调用 → LLM 认为任务完成，输出最终回复 ---
                log.info("LLM 输出最终回复（无工具调用）");

                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                // 记忆集成：记录助手回复 + token 使用
                if (memoryManager != null) {
                    memoryManager.addAssistantMessage(response.content());
                    memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens);
                }

                log.info("ReAct 循环结束 | 迭代次数: {} | 总 token: in={}, out={}",
                        iteration, totalInputTokens, totalOutputTokens);

                return response.content();
            }
        }

        // ⑤ 达到最大迭代次数，强制退出
        log.warn("达到最大迭代次数 {}，强制结束", MAX_ITERATIONS);
        return "[警告] 达到最大迭代次数 (" + MAX_ITERATIONS + ")，任务未能完成。";
    }

    // ==================== 辅助方法 ====================

    /**
     * 清空对话历史，重新开始新会话。
     * <p>
     * 如果启用了记忆功能，会先从当前对话中提取关键事实存入长期记忆，
     * 再清空短期对话历史。这样即使对话被清空，重要的知识仍被保留。
     */
    public void clearHistory() {
        // 清空前提取事实到长期记忆
        if (memoryManager != null) {
            try {
                List<String> facts = memoryManager.extractFactsFromConversation();
                if (!facts.isEmpty()) {
                    log.info("从对话中提取了 {} 条事实到长期记忆", facts.size());
                }
            } catch (Exception e) {
                log.warn("事实提取失败: {}", e.getMessage());
            }
            memoryManager.clearShortTerm();
        }

        conversationHistory.clear();
        rebuildSystemPrompt("");
    }

    /** 获取当前对话历史（用于调试） */
    public List<LlmClient.Message> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }

    /** 获取记忆管理器（用于调试或外部查询） */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    // ==================== 内部方法 ====================

    /**
     * 重建系统提示词。
     * <p>
     * 将基础提示词 + 可选的记忆上下文组装成 system message，
     * 替换 conversationHistory 中的第一条消息。
     */
    private void rebuildSystemPrompt(String memoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(BASE_SYSTEM_PROMPT);

        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append("\n\n").append(memoryContext);
        }

        LlmClient.Message systemMsg = LlmClient.Message.system(sb.toString());

        if (conversationHistory.isEmpty()) {
            conversationHistory.add(systemMsg);
        } else {
            // 替换第一条 system 消息
            conversationHistory.set(0, systemMsg);
        }
    }
}
