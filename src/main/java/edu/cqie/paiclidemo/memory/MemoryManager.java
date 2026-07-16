package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Memory 管理器 - Memory 系统的门面类（Facade）。
 * <p>
 * 统一管理短期记忆、长期记忆、上下文压缩和 token 预算，
 * 为 Agent 提供简洁的记忆存取接口。
 * <p>
 * Agent 只需调用：
 * - {@code addUserMessage()} / {@code addAssistantMessage()} / {@code addToolResult()} 存取记忆
 * - {@code compressIfNeeded()} 自动压缩
 * - {@code buildMemoryContext()} 获取注入到 system prompt 的记忆上下文
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private final TokenBudget tokenBudget;

    /** 触发压缩的短期记忆占用率阈值（0.8 = 80%） */
    private final double compressionTriggerRatio;

    /** 短期记忆 token 预算 */
    private static final int DEFAULT_SHORT_TERM_BUDGET = 8000;

    /** 模型上下文窗口 */
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /** 工具结果在记忆中的最大长度 */
    private static final int MAX_TOOL_RESULT_CHARS = 500;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, DEFAULT_SHORT_TERM_BUDGET, DEFAULT_CONTEXT_WINDOW);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null, 0.8);
    }

    /**
     * @param llmClient              LLM 客户端（用于压缩时的摘要生成）
     * @param shortTermBudget        短期记忆 token 预算
     * @param contextWindow          模型上下文窗口大小
     * @param longTermMemory         长期记忆实例（null = 创建默认的）
     * @param compressionTriggerRatio 触发压缩的占用率
     */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow,
                         LongTermMemory longTermMemory, double compressionTriggerRatio) {
        this.shortTermMemory = new ConversationMemory(shortTermBudget);
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(this.shortTermMemory, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextWindow);
        this.compressionTriggerRatio = compressionTriggerRatio;
    }

    // ==================== 短期记忆存取 ====================

    /** 添加用户消息到短期记忆 */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "user"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /** 添加助手回复到短期记忆 */
    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "assistant"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /** 添加工具执行结果到短期记忆（截断过长结果） */
    public void addToolResult(String toolName, String result) {
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;
        String content = "[" + toolName + "] " + truncated;
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    // ==================== 长期记忆 ====================

    /** 存储关键事实到长期记忆 */
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                Map.of("source", "fact"),
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    /** 搜索长期记忆（通过检索器，带评分排序） */
    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return retriever.retrieveLongTerm(query, limit);
    }

    /** 获取所有长期记忆 */
    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    /** 删除长期记忆条目 */
    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    // ==================== 记忆检索 ====================

    /**
     * 检索与查询最相关的记忆（短期+长期混合检索）。
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度降序排列的记忆列表
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    /**
     * 检索并返回带评分明细的结果（用于调试和可视化展示）。
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度降序排列的评分结果列表
     */
    public List<MemoryRetriever.ScoredEntry> retrieveScored(String query, int limit) {
        return retriever.retrieveScored(query, limit);
    }

    // ==================== 记忆上下文构建 ====================

    /**
     * 构建注入到 system prompt 的记忆上下文。
     * <p>
     * 只检索长期记忆中的相关事实（短期记忆已在 conversationHistory 中）。
     * 使用 MemoryRetriever 进行评分排序，确保最相关的记忆优先注入。
     *
     * @param query     当前用户输入（用于检索相关记忆）
     * @param maxTokens 记忆上下文的最大 token 数
     * @return 格式化的记忆上下文文本，无相关记忆时返回空字符串
     */
    public String buildMemoryContext(String query, int maxTokens) {
        List<MemoryEntry> relevant = retriever.retrieveLongTerm(query, 10);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关记忆\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    // ==================== 压缩 ====================

    /**
     * 检查并按需触发短期记忆压缩。
     *
     * @return 是否执行了压缩
     */
    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory, compressionTriggerRatio)) {
            return false;
        }

        int beforeTokens = shortTermMemory.getTokenCount();
        log.info("上下文占用达到压缩阈值（{}%），触发短期记忆压缩",
                (int) (compressionTriggerRatio * 100));

        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            int afterTokens = shortTermMemory.getTokenCount();
            log.info("短期记忆压缩完成: {} -> {} tokens", beforeTokens, afterTokens);
        }
        return summary != null;
    }

    /**
     * 从对话中提取关键事实，存入长期记忆。
     * 适合在会话结束、clear 命令时调用。
     */
    public List<String> extractFactsFromConversation() {
        List<MemoryEntry> entries = shortTermMemory.getAll();
        return compressor.extractFacts(entries, longTermMemory);
    }

    // ==================== 清空 ====================

    /** 清空短期记忆（保留长期记忆） */
    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    /** 清空长期记忆 */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    // ==================== 状态查询 ====================

    /** 获取记忆系统的整体状态 */
    public String getSystemStatus() {
        return shortTermMemory.getStatusSummary() + "\n"
                + longTermMemory.getStatusSummary() + "\n"
                + tokenBudget.getUsageReport();
    }

    /** 记录 token 使用 */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    // ==================== Getter ====================

    public ConversationMemory getShortTermMemory() { return shortTermMemory; }
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public MemoryRetriever getRetriever() { return retriever; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
}
