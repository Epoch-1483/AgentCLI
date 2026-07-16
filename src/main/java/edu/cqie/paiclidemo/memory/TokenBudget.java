package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.llm.LlmClient;

import java.util.List;

/**
 * Token 预算管理器 - 确保对话不会超出模型的上下文窗口。
 * <p>
 * 策略：
 * 1. 设定总 token 预算 = 上下文窗口 - 系统提示预留 - 工具定义预留 - 回复预留
 * 2. 跟踪每次 LLM 调用的 token 消耗
 * 3. 当短期记忆占用率超过阈值时触发压缩
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class TokenBudget {

    private final int contextWindow;       // 模型上下文窗口大小
    private final int reservedForSystem;   // 系统提示预留
    private final int reservedForTools;    // 工具定义预留
    private final int reservedForResponse; // 回复预留

    // 累计 token 消耗统计
    private int totalInputTokens;
    private int totalOutputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    /**
     * @param contextWindow       模型上下文窗口（如 128K = 131072）
     * @param reservedForSystem   系统提示预留 token 数
     * @param reservedForTools    工具定义预留 token 数
     * @param reservedForResponse 回复预留 token 数
     */
    public TokenBudget(int contextWindow, int reservedForSystem,
                       int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
        this.totalInputTokens = 0;
        this.totalOutputTokens = 0;
        this.llmCallCount = 0;
    }

    /** 获取对话历史可用的 token 预算 */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /** 检查给定的消息列表是否在预算内 */
    public boolean isWithinBudget(List<LlmClient.Message> messages) {
        int estimated = estimateMessagesTokens(messages);
        return estimated <= getAvailableForConversation();
    }

    /**
     * 检查是否需要压缩。
     *
     * @param memory       短期记忆
     * @param triggerRatio 触发压缩的占用率（0.0-1.0），如 0.8 表示占用 80% 时触发
     */
    public boolean needsCompression(ConversationMemory memory, double triggerRatio) {
        int compressionBudget = Math.min(memory.getMaxTokens(), getAvailableForConversation());
        return memory.getTokenCount() >= compressionBudget * triggerRatio;
    }

    /** 记录一次 LLM 调用的 token 消耗 */
    public void recordUsage(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        llmCallCount++;
    }

    /** 获取 token 使用统计报告 */
    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    // ==================== Getter ====================

    public int getContextWindow() { return contextWindow; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getLlmCallCount() { return llmCallCount; }

    // ==================== 静态工具方法 ====================

    /**
     * 估算消息列表的 token 总数。
     * <p>
     * 使用 MemoryEntry.estimateTokens() 近似计算，
     * 每条消息额外加 4 tokens 开销（role、separator 等）。
     */
    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (LlmClient.Message msg : messages) {
            total += MemoryEntry.estimateTokens(msg.content());
            if (msg.toolCalls() != null) {
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    total += MemoryEntry.estimateTokens(tc.function().arguments());
                }
            }
        }
        total += messages.size() * 4; // 每条消息约 4 tokens 开销
        return total;
    }
}
