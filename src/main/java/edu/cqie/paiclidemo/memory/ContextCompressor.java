package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * 上下文压缩器 - 当对话过长时，自动压缩旧消息。
 * <p>
 * 压缩策略（Map-Reduce）：
 * <ol>
 *   <li>Map：将旧消息分成每 5 条一组，每组独立生成摘要</li>
 *   <li>Reduce：将多个摘要合并为一个整体摘要</li>
 *   <li>保留最近 N 轮消息不参与压缩</li>
 *   <li>压缩后的摘要回注到 ConversationMemory</li>
 * </ol>
 * <p>
 * 还包含事实提取功能：从对话中提取跨会话仍有价值的稳定事实，存入长期记忆。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    private final LlmClient llmClient;
    private final int retainRecentRounds;

    // ==================== 压缩提示词 ====================

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论
            - 重要的技术细节

            对话片段：
            %s

            请用中文输出摘要，控制在200字以内。
            """;

    private static final String REDUCE_PROMPT = """
            请将以下多个摘要合并成一个整体摘要，保留所有关键信息。

            各片段摘要：
            %s

            请用中文输出合并摘要，控制在300字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            请从以下对话中提取"跨会话仍然成立、未来复用仍有价值"的稳定事实，格式为每行一条：
            - 用户偏好和习惯
            - 项目信息（名称、路径、技术栈）
            - 重要决策和约定

            只保留用户明确说明、或工具/代码库可验证的信息。
            不要提取：临时任务、一次性文件名、模型的猜测、用户请求句。

            对话内容：
            %s

            请每行一条事实，不要多余解释。
            """;

    // 临时性事实前缀（过滤掉）
    private static final List<String> EPHEMERAL_PREFIXES = List.of(
            "用户想", "用户要", "用户需要", "帮我", "让我",
            "新建", "创建", "删除", "修改", "生成", "当前任务", "本次任务"
    );

    // 持久性事实关键词（优先保留）
    private static final List<String> DURABLE_HINTS = List.of(
            "偏好", "习惯", "喜欢", "项目", "技术栈", "版本",
            "配置", "模型", "约定", "规则", "默认"
    );

    public ContextCompressor(LlmClient llmClient) {
        this(llmClient, 3);
    }

    /**
     * @param llmClient          LLM 客户端
     * @param retainRecentRounds 保留最近 N 轮完整消息不压缩
     */
    public ContextCompressor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    // ==================== 短期记忆压缩 ====================

    /**
     * 压缩短期记忆。
     *
     * @param memory 短期记忆
     * @return 压缩后的摘要文本，如果不需要压缩则返回 null
     */
    public String compress(ConversationMemory memory) {
        List<MemoryEntry> allEntries = memory.getAll();
        if (allEntries.size() <= retainRecentRounds) {
            return null; // 条目太少，不需要压缩
        }

        // 分割：旧消息 vs 近期消息
        int splitPoint = allEntries.size() - retainRecentRounds;
        List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));
        List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size()));

        // Map 阶段
        List<String> chunkSummaries = mapPhase(oldEntries);
        if (chunkSummaries.isEmpty()) {
            return null;
        }

        // Reduce 阶段
        String finalSummary;
        if (chunkSummaries.size() == 1) {
            finalSummary = chunkSummaries.get(0);
        } else {
            finalSummary = reducePhase(chunkSummaries);
        }

        // 清空旧记忆，注入摘要，回注近期记忆
        memory.clear();
        MemoryEntry summaryEntry = new MemoryEntry(
                "summary-" + UUID.randomUUID().toString().substring(0, 8),
                "[历史对话摘要] " + finalSummary,
                MemoryEntry.MemoryType.SUMMARY,
                null,
                MemoryEntry.estimateTokens(finalSummary)
        );
        memory.store(summaryEntry);

        for (MemoryEntry entry : recentEntries) {
            memory.store(entry);
        }

        return finalSummary;
    }

    // ==================== 事实提取 ====================

    /**
     * 从对话记忆中提取关键事实，存入长期记忆。
     *
     * @param entries       对话记忆条目
     * @param longTermMemory 长期记忆实例
     * @return 提取到的事实列表
     */
    public List<String> extractFacts(List<MemoryEntry> entries, LongTermMemory longTermMemory) {
        if (entries.isEmpty()) {
            return List.of();
        }

        StringBuilder conversation = new StringBuilder();
        for (MemoryEntry entry : entries) {
            String source = resolveSource(entry);
            conversation.append(source.toUpperCase())
                    .append("(").append(entry.getType()).append("): ")
                    .append(entry.getContent()).append("\n\n");
        }

        try {
            String prompt = String.format(EXTRACT_FACTS_PROMPT, conversation);
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("你是一个信息提取助手，只输出关键事实。"),
                    LlmClient.Message.user(prompt)
            );

            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            String factsText = response.content();

            List<String> facts = new ArrayList<>();
            for (String line : factsText.split("\n")) {
                String fact = normalizeFactLine(line);
                if (isPersistentFact(fact)) {
                    facts.add(fact);

                    MemoryEntry factEntry = new MemoryEntry(
                            "fact-" + UUID.randomUUID().toString().substring(0, 8),
                            fact,
                            MemoryEntry.MemoryType.FACT,
                            Map.of("source", "fact_extractor"),
                            MemoryEntry.estimateTokens(fact)
                    );
                    longTermMemory.store(factEntry);
                }
            }
            return facts;
        } catch (IOException e) {
            log.warn("事实提取失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== Map-Reduce 内部方法 ====================

    /** Map 阶段：将旧消息分片，每片独立摘要 */
    private List<String> mapPhase(List<MemoryEntry> oldEntries) {
        List<String> summaries = new ArrayList<>();
        int chunkSize = 5;
        List<List<MemoryEntry>> chunks = partition(oldEntries, chunkSize);

        for (List<MemoryEntry> chunk : chunks) {
            StringBuilder chunkText = new StringBuilder();
            for (MemoryEntry entry : chunk) {
                chunkText.append(entry.getType()).append(": ")
                        .append(entry.getContent()).append("\n\n");
            }

            try {
                String prompt = String.format(MAP_PROMPT, chunkText);
                List<LlmClient.Message> messages = List.of(
                        LlmClient.Message.system("你是一个对话摘要助手。"),
                        LlmClient.Message.user(prompt)
                );

                LlmClient.ChatResponse response = llmClient.chat(messages, null);
                summaries.add(response.content());
            } catch (IOException e) {
                log.warn("摘要生成失败: {}", e.getMessage());
                // 降级：截取前 200 字
                String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
                summaries.add("[压缩] " + fallback);
            }
        }

        return summaries;
    }

    /** Reduce 阶段：合并多个摘要 */
    private String reducePhase(List<String> summaries) {
        String joined = String.join("\n\n---\n\n", summaries);

        try {
            String prompt = String.format(REDUCE_PROMPT, joined);
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("你是一个摘要合并助手。"),
                    LlmClient.Message.user(prompt)
            );

            LlmClient.ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            log.warn("摘要合并失败: {}", e.getMessage());
            return String.join("；", summaries);
        }
    }

    // ==================== 工具方法 ====================

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private String resolveSource(MemoryEntry entry) {
        String source = entry.getMetadata().get("source");
        if (source != null && !source.isBlank()) {
            return source;
        }
        if (entry.getId().startsWith("user-")) {
            return "user";
        }
        if (entry.getId().startsWith("assistant-")) {
            return "assistant";
        }
        if (entry.getId().startsWith("tool-")) {
            return "tool";
        }
        return "unknown";
    }

    private String normalizeFactLine(String line) {
        if (line == null) {
            return "";
        }
        String fact = line.trim();
        if (fact.startsWith("- ")) {
            fact = fact.substring(2);
        }
        else if (fact.startsWith("* ")) {
            fact = fact.substring(2);
        }
        return fact.trim();
    }

    /** 判断是否为值得持久化的事实 */
    private boolean isPersistentFact(String fact) {
        if (fact == null || fact.length() <= 5) {
            return false;
        }

        String lower = fact.toLowerCase();
        // 过滤临时性请求
        for (String prefix : EPHEMERAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return false;
            }
        }
        // 匹配持久性关键词
        for (String hint : DURABLE_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        // 含冒号的通常是事实陈述
        return fact.contains("：") || fact.contains(":");
    }
}
