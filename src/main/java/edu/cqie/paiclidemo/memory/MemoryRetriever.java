package edu.cqie.paiclidemo.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从短期记忆和长期记忆中检索最相关的信息。
 * <p>
 * 为什么需要检索？短期记忆和长期记忆都有了，但 Agent 处理新输入时，
 * 不能把所有记忆全塞给 LLM——大部分跟当前任务根本不沾边，纯属浪费 token。
 * MemoryRetriever 干的就是从两层记忆里把最相关的条目捞出来。
 * <p>
 * 检索策略（三个评分维度）：
 * <ul>
 *   <li><b>关键词匹配</b>：把查询分词后逐词匹配，命中越多分数越高（0.0~1.0）</li>
 *   <li><b>时间衰减</b>：24 小时内从 1.0 线性衰减到 0.5，三天前的旧事权重自然低</li>
 *   <li><b>来源加权</b>：长期记忆经过提取和精炼，信息密度更高，给 1.2 倍权重</li>
 * </ul>
 * <p>
 * 最终得分 = keywordScore × timeDecay × sourceWeight
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class MemoryRetriever {

    /** 长期记忆的来源加权系数（经过提取和精炼，信息密度更高） */
    private static final double LONG_TERM_BOOST = 1.2;

    /** 时间衰减的半衰期（小时），24h 内从 1.0 衰减到 0.5 */
    private static final double DECAY_HOURS = 24.0;

    /** 时间衰减的最低值（不管多旧都至少保留 50% 权重） */
    private static final double MIN_DECAY = 0.5;

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
    }

    // ==================== 检索结果（含评分明细） ====================

    /**
     * 带评分明细的检索结果。
     * <p>
     * 公开 ScoredEntry 用于调试展示（如 /memory search 命令），
     * 可以看到每条记忆的关键词匹配分、时间衰减系数、来源加权和最终得分。
     *
     * @param entry         记忆条目
     * @param score         最终得分 = keywordScore × timeDecay × sourceWeight
     * @param keywordScore  关键词匹配分（0.0~1.0，命中词数/总词数）
     * @param timeDecay     时间衰减系数（0.5~1.0）
     * @param sourceWeight  来源加权（短期=1.0，长期=1.2）
     * @param fromShortTerm 是否来自短期记忆
     */
    public record ScoredEntry(
            MemoryEntry entry,
            double score,
            double keywordScore,
            double timeDecay,
            double sourceWeight,
            boolean fromShortTerm
    ) {}

    // ==================== 核心检索方法 ====================

    /**
     * 从两层记忆中检索与查询最相关的记忆（返回原始条目列表）。
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度降序排列的记忆列表
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        return retrieveScored(query, limit).stream()
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 从两层记忆中检索，返回带评分明细的结果。
     * <p>
     * 与 {@link #retrieve} 的区别：这个方法返回 {@link ScoredEntry}，
     * 包含每个维度的评分，适合调试和可视化展示。
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度降序排列的评分结果列表
     */
    public List<ScoredEntry> retrieveScored(String query, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();

        // 1.从短期记忆中检索（sourceWeight = 1.0）
        for (MemoryEntry entry : shortTermMemory.getAll()) {
            ScoredEntry se = scoreEntry(entry, query, 1.0, true);
            if (se != null) {
                scored.add(se);
            }
        }

        // 2.从长期记忆中检索（sourceWeight = 1.2，因为更精炼）
        for (MemoryEntry entry : longTermMemory.getAll()) {
            ScoredEntry se = scoreEntry(entry, query, LONG_TERM_BOOST, false);
            if (se != null) {
                scored.add(se);
            }
        }

        // 3.按最终得分降序排序，取 top-N
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 仅从长期记忆中检索稳定事实，用于 system prompt 注入。
     * <p>
     * 当前轮用户输入和短期对话已经在 message history 里，不应再次以"相关记忆"
     * 身份注入给模型，否则容易让模型把当前请求误读成历史事实。
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度降序排列的长期记忆列表
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return longTermMemory.getAll().stream()
                .map(entry -> scoreEntry(entry, query, LONG_TERM_BOOST, false))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    // ==================== 上下文构建 ====================

    /**
     * 构建上下文：将相关长期记忆组装成文本，用于注入到 LLM 的 system prompt 中。
     * <p>
     * 只检索长期记忆（短期记忆已在对话历史中），按 token 预算截断。
     *
     * @param query     查询文本（当前用户输入）
     * @param maxTokens 记忆上下文的最大 token 数
     * @return 格式化的记忆上下文文本，无相关记忆时返回空字符串
     */
    public String buildContextForQuery(String query, int maxTokens) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");

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

    // ==================== 评分算法 ====================

    /**
     * 对单条记忆计算完整的相关度评分。
     * <p>
     * 评分公式：score = keywordScore × timeDecay × sourceWeight
     * <ul>
     *   <li>keywordScore = 命中词数 / 查询总词数（0.0~1.0），精确包含直接得 1.0</li>
     *   <li>timeDecay = max(0.5, 1.0 - ageHours/24.0)</li>
     *   <li>sourceWeight = 1.0（短期）或 1.2（长期）</li>
     * </ul>
     *
     * @return ScoredEntry 或 null（无关键词匹配时）
     */
    private ScoredEntry scoreEntry(MemoryEntry entry, String query,
                                   double sourceWeight, boolean fromShortTerm) {
        double keywordScore = computeKeywordScore(entry, query);
        if (keywordScore <= 0) return null;

        double timeDecay = computeTimeDecay(entry);
        double finalScore = keywordScore * timeDecay * sourceWeight;

        return new ScoredEntry(entry, finalScore, keywordScore, timeDecay,
                sourceWeight, fromShortTerm);
    }

    /**
     * 计算关键词匹配分数（0.0 ~ 1.0）。
     * <p>
     * 规则：
     * <ol>
     *   <li>如果内容精确包含完整查询 → 直接返回 1.0</li>
     *   <li>否则把查询分词，统计命中词占总词数的比例</li>
     *   <li>无命中 → 返回 0</li>
     * </ol>
     */
    double computeKeywordScore(MemoryEntry entry, String query) {
        if (query == null || query.isBlank()) return 0;

        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        // 精确包含 → 满分
        if (contentLower.contains(queryLower)) {
            return 1.0;
        }

        // 分词匹配
        Set<String> queryWords = ConversationMemory.tokenize(queryLower);
        if (queryWords.isEmpty()) return 0;

        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }

        if (matchedWords == 0) return 0;
        return (double) matchedWords / queryWords.size();
    }

    /**
     * 计算时间衰减系数（0.5 ~ 1.0）。
     * <p>
     * 规则：24 小时内从 1.0 线性衰减到 0.5，超过 24 小时固定在 0.5。
     * 这样三天前的旧事权重自然就低了，但不会完全消失。
     */
    double computeTimeDecay(MemoryEntry entry) {
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        return Math.max(MIN_DECAY, 1.0 - ageHours / DECAY_HOURS);
    }
}
