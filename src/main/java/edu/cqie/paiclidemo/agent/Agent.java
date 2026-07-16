package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.llm.LlmClient;
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
 * 关键设计原则：
 * - LLM 自己决定何时停止（不再调用工具 = 任务完成）
 * - 对话历史作为上下文在每轮迭代中传递
 * - 设置最大迭代次数防止死循环
 * <p>
 *
 * @author Fonzo
 * @date 2026/07/15
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /** 安全阈值：最大迭代次数，防止模型陷入死循环 */
    private static final int MAX_ITERATIONS = 10;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;

    /** 对话历史 —— 在整个会话过程中持续累积 */
    private final List<LlmClient.Message> conversationHistory = new ArrayList<>();

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;

        // 初始化系统提示词 —— 告诉模型它是一个有工具能力的助手
        conversationHistory.add(LlmClient.Message.system(
                "你是一个智能助手，可以使用提供的工具来帮助用户解决问题。"
                        + "当你需要获取信息、执行计算或完成特定任务时，请主动使用工具。"
                        + "每次调用工具后，根据工具返回的结果继续推理，直到得出最终答案。"
                        + "请用中文回复。"
        ));
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
                    // LLM 在下一轮迭代中会看到这个结果并继续推理
                    conversationHistory.add(LlmClient.Message.tool(
                            toolCall.id(),
                            result
                    ));
                }

                // 继续循环 → 让 LLM 根据工具结果继续推理
                // （如果工具结果足够，LLM 下一轮就不会再调工具，直接输出最终回复）

            } else {
                // --- 无工具调用 → LLM 认为任务完成，输出最终回复 ---
                log.info("LLM 输出最终回复（无工具调用）");

                conversationHistory.add(LlmClient.Message.assistant(response.content()));

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

    /** 清空对话历史，重新开始新会话 */
    public void clearHistory() {
        conversationHistory.clear();
        // 重新添加系统提示
        conversationHistory.add(LlmClient.Message.system(
                "你是一个智能助手，可以使用提供的工具来帮助用户解决问题。"
                        + "当你需要获取信息、执行计算或完成特定任务时，请主动使用工具。"
                        + "每次调用工具后，根据工具返回的结果继续推理，直到得出最终答案。"
                        + "请用中文回复。"
        ));
    }

    /** 获取当前对话历史（用于调试） */
    public List<LlmClient.Message> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }
}
