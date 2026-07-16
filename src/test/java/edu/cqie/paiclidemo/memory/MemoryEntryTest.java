package edu.cqie.paiclidemo.memory;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryEntry 单元测试。
 */
@DisplayName("MemoryEntry - 记忆条目")
class MemoryEntryTest {

    @Nested
    @DisplayName("构造与 Getter")
    class Construction {

        @Test
        @DisplayName("基本构造：所有字段正确赋值")
        void basicConstruction() {
            Instant now = Instant.now();
            MemoryEntry entry = new MemoryEntry("id-1", "测试内容",
                    MemoryEntry.MemoryType.CONVERSATION, now,
                    Map.of("source", "user"), 10);

            assertEquals("id-1", entry.getId());
            assertEquals("测试内容", entry.getContent());
            assertEquals(MemoryEntry.MemoryType.CONVERSATION, entry.getType());
            assertEquals(now, entry.getTimestamp());
            assertEquals("user", entry.getMetadata().get("source"));
            assertEquals(10, entry.getTokenCount());
        }

        @Test
        @DisplayName("简化构造：timestamp 默认 now")
        void simplifiedConstructor() {
            MemoryEntry entry = new MemoryEntry("id-2", "内容",
                    MemoryEntry.MemoryType.FACT, Map.of(), 5);
            assertNotNull(entry.getTimestamp());
            // 时间戳应该是最近的
            assertTrue(Instant.now().minusSeconds(5).isBefore(entry.getTimestamp()));
        }

        @Test
        @DisplayName("null timestamp 自动填充为 now")
        void nullTimestamp() {
            MemoryEntry entry = new MemoryEntry("id-3", "内容",
                    MemoryEntry.MemoryType.SUMMARY, null, Map.of(), 5);
            assertNotNull(entry.getTimestamp());
        }

        @Test
        @DisplayName("null metadata 转为空 Map")
        void nullMetadata() {
            MemoryEntry entry = new MemoryEntry("id-4", "内容",
                    MemoryEntry.MemoryType.TOOL_RESULT, null, null, 5);
            assertNotNull(entry.getMetadata());
            assertTrue(entry.getMetadata().isEmpty());
        }

        @Test
        @DisplayName("所有记忆类型枚举值存在")
        void allMemoryTypes() {
            assertEquals(4, MemoryEntry.MemoryType.values().length);
            assertNotNull(MemoryEntry.MemoryType.valueOf("CONVERSATION"));
            assertNotNull(MemoryEntry.MemoryType.valueOf("FACT"));
            assertNotNull(MemoryEntry.MemoryType.valueOf("SUMMARY"));
            assertNotNull(MemoryEntry.MemoryType.valueOf("TOOL_RESULT"));
        }
    }

    @Nested
    @DisplayName("Token 估算")
    class TokenEstimation {

        @Test
        @DisplayName("纯英文文本")
        void englishText() {
            // "hello world" = 11 chars / 4 = ~3 tokens
            int tokens = MemoryEntry.estimateTokens("hello world");
            assertTrue(tokens >= 2 && tokens <= 4,
                    "英文 11 字符应约 3 tokens, 实际: " + tokens);
        }

        @Test
        @DisplayName("纯中文文本")
        void chineseText() {
            // "你好世界" = 4 中文字符 / 1.5 = ~3 tokens
            int tokens = MemoryEntry.estimateTokens("你好世界");
            assertTrue(tokens >= 2 && tokens <= 4,
                    "中文 4 字应约 3 tokens, 实际: " + tokens);
        }

        @Test
        @DisplayName("中英混合文本")
        void mixedText() {
            // "你好world" = 2 中文 + 5 英文
            int tokens = MemoryEntry.estimateTokens("你好world");
            assertTrue(tokens > 0, "混合文本 token 应 > 0");
        }

        @Test
        @DisplayName("空字符串和 null")
        void emptyAndNull() {
            assertEquals(0, MemoryEntry.estimateTokens(""));
            assertEquals(0, MemoryEntry.estimateTokens(null));
        }

        @Test
        @DisplayName("长文本的 token 数合理")
        void longText() {
            String longText = "a".repeat(1000);
            int tokens = MemoryEntry.estimateTokens(longText);
            // 1000 chars / 4 = 250 tokens
            assertEquals(250, tokens);
        }
    }

    @Test
    @DisplayName("toString 格式正确")
    void toStringFormat() {
        MemoryEntry entry = new MemoryEntry("test-1", "短内容",
                MemoryEntry.MemoryType.FACT, Map.of(), 5);
        String str = entry.toString();
        assertTrue(str.contains("FACT"));
        assertTrue(str.contains("test-1"));
        assertTrue(str.contains("短内容"));
    }

    @Test
    @DisplayName("toString 长内容截断")
    void toStringTruncation() {
        String longContent = "a".repeat(200);
        MemoryEntry entry = new MemoryEntry("test-2", longContent,
                MemoryEntry.MemoryType.CONVERSATION, Map.of(), 50);
        String str = entry.toString();
        assertTrue(str.contains("..."));
        assertTrue(str.length() < longContent.length());
    }
}
