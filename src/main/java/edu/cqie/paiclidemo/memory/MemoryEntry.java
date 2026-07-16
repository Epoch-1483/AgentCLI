package edu.cqie.paiclidemo.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元。
 * <p>
 * 每条记忆包含：唯一 ID、内容、类型、时间戳、元数据和 Token 估算值。
 * 不同类型（对话/事实/摘要/工具结果）在压缩时采用不同策略。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class MemoryEntry {

    /** 记忆类型枚举 */
    public enum MemoryType {
        CONVERSATION,  // 对话记忆（用户消息、助手回复）
        FACT,          // 事实记忆（用户偏好、项目信息等，跨会话持久化）
        SUMMARY,       // 摘要记忆（压缩后的历史对话）
        TOOL_RESULT    // 工具执行结果（压缩时优先裁剪）
    }

    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;    // 时间戳
    private final Map<String, String> metadata; // 元数据
    private final int tokenCount;

    public MemoryEntry(String id, String content, MemoryType type,
                       Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? metadata : Map.of();
        this.tokenCount = tokenCount;
    }

    // ==================== Getter ====================

    public String getId() { return id; }
    public String getContent() { return content; }
    public MemoryType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, String> getMetadata() { return metadata; }
    public int getTokenCount() { return tokenCount; }

    // ==================== Token 估算 ====================

    /**
     * 粗略估算文本的 token 数。
     * <p>
     * 规则：中文约 1.5 字/token，英文约 4 字符/token。
     * 这种近似法避免了调用 tokenizer 的延迟，适合实时预算计算。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long chineseChars = text.chars()
                .filter(c -> c > 0x4E00 && c < 0x9FFF)
                .count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
