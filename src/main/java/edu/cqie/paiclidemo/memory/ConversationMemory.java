package edu.cqie.paiclidemo.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 短期记忆 - 管理当前对话的上下文。
 * <p>
 * 核心机制：
 * <ul>
 *   <li>使用 LinkedHashMap 维护有序的对话条目（FIFO 顺序）</li>
 *   <li>当 token 超出预算时，自动淘汰最旧的消息</li>
 *   <li>被淘汰的消息暂存到 compressedSummaries，等待压缩为摘要</li>
 *   <li>短期记忆已作为对话历史直接传给 LLM，无需重复检索</li>
 * </ul>
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class ConversationMemory implements Memory {

    private final LinkedHashMap<String, MemoryEntry> entries;
    private int maxTokens;
    private int currentTokens;
    private final List<MemoryEntry> compressedSummaries;

    /**
     * @param maxTokens 最大 token 预算，超出时触发淘汰
     */
    public ConversationMemory(int maxTokens) {
        this.entries = new LinkedHashMap<>();
        this.maxTokens = maxTokens;
        this.currentTokens = 0;
        this.compressedSummaries = new ArrayList<>();
    }

    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.getId(), entry);
        currentTokens += entry.getTokenCount();

        // 超出预算时自动淘汰最旧的条目
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        String queryLower = query.toLowerCase();
        // 简化版关键词匹配：按空格分词
        Set<String> queryWords = tokenize(queryLower);

        return entries.values().stream()
                .filter(entry -> matches(entry.getContent(), queryWords))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            currentTokens -= removed.getTokenCount();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        currentTokens = 0;
        compressedSummaries.clear();
    }

    @Override
    public int getTokenCount() {
        return currentTokens;
    }

    @Override
    public int size() {
        return entries.size();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.maxTokens = maxTokens;
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    /** 获取被淘汰的消息列表（等待压缩为摘要） */
    public List<MemoryEntry> getCompressedSummaries() {
        return Collections.unmodifiableList(compressedSummaries);
    }

    /** 将压缩摘要注入回记忆（上下文压缩后调用） */
    public void injectSummary(MemoryEntry summary) {
        compressedSummaries.clear();
        entries.put(summary.getId(), summary);
        currentTokens += summary.getTokenCount();
    }

    /** 获取记忆使用率（0.0 ~ 1.0+） */
    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }

    /** 生成记忆状态摘要（用于调试展示） */
    public String getStatusSummary() {
        return String.format("短期记忆: %d条 / %d tokens (预算: %d, 使用率: %.0f%%, 已压缩: %d条)",
                entries.size(), currentTokens, maxTokens, getUsageRatio() * 100, compressedSummaries.size());
    }

    // ==================== 内部方法 ====================

    /** 淘汰最旧的一条记忆，暂存到压缩摘要列表 */
    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, MemoryEntry> oldest = it.next();
            it.remove();
            currentTokens -= oldest.getValue().getTokenCount();
            compressedSummaries.add(oldest.getValue());
        }
    }

    // ==================== 简化版分词/匹配 ====================

    /**
     * 简化的关键词分词：按空格和常见标点分割。
     * 原版 paiCLI 使用 jieba 分词，这里简化为直接分割。
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();
        // 按空格和常见中英文标点分割
        for (String word : text.split("[\\s,;.!?，；。！？、]+")) {
            String trimmed = word.trim();
            if (trimmed.length() >= 1) {
                tokens.add(trimmed.toLowerCase());
            }
        }
        return tokens;
    }

    /** 检查内容是否匹配查询关键词 */
    static boolean matches(String content, Set<String> queryWords) {
        if (queryWords.isEmpty()) return false;
        String contentLower = content.toLowerCase();
        for (String word : queryWords) {
            if (contentLower.contains(word)) {
                return true; // 任一关键词匹配即可
            }
        }
        return false;
    }
}
