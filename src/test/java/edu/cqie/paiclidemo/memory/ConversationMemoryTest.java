package edu.cqie.paiclidemo.memory;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationMemory（短期记忆）单元测试。
 */
@DisplayName("ConversationMemory - 短期记忆")
class ConversationMemoryTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory(100); // 100 token 预算
    }

    // ==================== 辅助方法 ====================

    private MemoryEntry makeEntry(String id, String content, int tokens) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "test"), tokens);
    }

    // ==================== 基本存取 ====================

    @Nested
    @DisplayName("基本存取操作")
    class BasicOps {

        @Test
        @DisplayName("store + retrieve：按 ID 存取")
        void storeAndRetrieve() {
            MemoryEntry entry = makeEntry("e1", "你好", 5);
            memory.store(entry);

            Optional<MemoryEntry> found = memory.retrieve("e1");
            assertTrue(found.isPresent());
            assertEquals("你好", found.get().getContent());
        }

        @Test
        @DisplayName("retrieve 不存在的 ID 返回 empty")
        void retrieveNonExistent() {
            assertTrue(memory.retrieve("not-exist").isEmpty());
        }

        @Test
        @DisplayName("getAll 返回所有条目（有序）")
        void getAll() {
            memory.store(makeEntry("e1", "第一条", 5));
            memory.store(makeEntry("e2", "第二条", 5));
            memory.store(makeEntry("e3", "第三条", 5));

            List<MemoryEntry> all = memory.getAll();
            assertEquals(3, all.size());
            assertEquals("e1", all.get(0).getId());
            assertEquals("e3", all.get(2).getId());
        }

        @Test
        @DisplayName("delete：删除成功返回 true")
        void delete() {
            memory.store(makeEntry("e1", "待删除", 10));
            assertTrue(memory.delete("e1"));
            assertEquals(0, memory.size());
            assertEquals(0, memory.getTokenCount());
        }

        @Test
        @DisplayName("delete 不存在的 ID 返回 false")
        void deleteNonExistent() {
            assertFalse(memory.delete("not-exist"));
        }

        @Test
        @DisplayName("clear：清空所有")
        void clear() {
            memory.store(makeEntry("e1", "内容1", 10));
            memory.store(makeEntry("e2", "内容2", 10));
            memory.clear();
            assertEquals(0, memory.size());
            assertEquals(0, memory.getTokenCount());
            assertTrue(memory.getCompressedSummaries().isEmpty());
        }

        @Test
        @DisplayName("size 和 getTokenCount 正确跟踪")
        void sizeAndTokens() {
            assertEquals(0, memory.size());
            assertEquals(0, memory.getTokenCount());

            memory.store(makeEntry("e1", "a", 10));
            assertEquals(1, memory.size());
            assertEquals(10, memory.getTokenCount());

            memory.store(makeEntry("e2", "b", 20));
            assertEquals(2, memory.size());
            assertEquals(30, memory.getTokenCount());
        }
    }

    // ==================== FIFO 淘汰 ====================

    @Nested
    @DisplayName("Token 预算与 FIFO 淘汰")
    class Eviction {

        @Test
        @DisplayName("超出预算时自动淘汰最旧条目")
        void autoEviction() {
            // 预算 100 tokens
            memory.store(makeEntry("e1", "第一条", 40));
            memory.store(makeEntry("e2", "第二条", 40));
            // 此时 80 tokens，还没超

            memory.store(makeEntry("e3", "第三条", 40));
            // 120 > 100，淘汰 e1
            assertEquals(2, memory.size());
            assertTrue(memory.retrieve("e1").isEmpty());
            assertTrue(memory.retrieve("e2").isPresent());
            assertTrue(memory.retrieve("e3").isPresent());
        }

        @Test
        @DisplayName("被淘汰的条目进入 compressedSummaries")
        void evictedToSummaries() {
            memory.store(makeEntry("e1", "第一条", 40));
            memory.store(makeEntry("e2", "第二条", 40));
            memory.store(makeEntry("e3", "第三条", 40));

            List<MemoryEntry> summaries = memory.getCompressedSummaries();
            assertEquals(1, summaries.size());
            assertEquals("e1", summaries.get(0).getId());
        }

        @Test
        @DisplayName("连续淘汰多条")
        void multipleEvictions() {
            // 每条 30 tokens, 预算 100
            memory.store(makeEntry("e1", "1", 30));
            memory.store(makeEntry("e2", "2", 30));
            memory.store(makeEntry("e3", "3", 30));
            // 90 tokens, OK

            memory.store(makeEntry("e4", "4", 30));
            // 120 > 100, 淘汰 e1 → 90 > 100? no, but size=3>1
            // 淘汰后 currentTokens=90, 90 > 100? 不，但 entries.size()=3>1 且 90 <= 100
            // 实际上：加 e4 后 currentTokens=120
            // while: 120>100 && 4>1 → evict e1 → currentTokens=90, size=3
            // while: 90>100? NO → stop
            assertEquals(3, memory.size());
            assertEquals(1, memory.getCompressedSummaries().size());
        }

        @Test
        @DisplayName("至少保留 1 条即使超预算")
        void keepAtLeastOne() {
            // 一条超预算的消息
            memory.store(makeEntry("e1", "超长消息", 200));
            assertEquals(1, memory.size());
            // 不会淘汰到最后一条都没有
        }
    }

    // ==================== 搜索 ====================

    @Nested
    @DisplayName("关键词搜索")
    class Search {

        @Test
        @DisplayName("按关键词搜索匹配内容")
        void searchByKeyword() {
            memory.store(makeEntry("e1", "用户喜欢吃苹果", 10));
            memory.store(makeEntry("e2", "今天的天气很好", 10));
            memory.store(makeEntry("e3", "苹果是一种水果", 10));

            List<MemoryEntry> results = memory.search("苹果", 5);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("搜索无匹配返回空列表")
        void searchNoMatch() {
            memory.store(makeEntry("e1", "你好", 5));
            List<MemoryEntry> results = memory.search("香蕉", 5);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("搜索结果受 limit 限制")
        void searchLimit() {
            memory.store(makeEntry("e1", "关键词a", 5));
            memory.store(makeEntry("e2", "关键词b", 5));
            memory.store(makeEntry("e3", "关键词c", 5));

            List<MemoryEntry> results = memory.search("关键词", 2);
            assertEquals(2, results.size());
        }
    }

    // ==================== 摘要注入 ====================

    @Nested
    @DisplayName("摘要注入")
    class SummaryInjection {

        @Test
        @DisplayName("injectSummary 清空压缩摘要并注入新摘要")
        void injectSummary() {
            memory.store(makeEntry("e1", "旧消息", 40));
            memory.store(makeEntry("e2", "旧消息2", 40));
            memory.store(makeEntry("e3", "旧消息3", 40)); // 触发淘汰

            assertFalse(memory.getCompressedSummaries().isEmpty());

            MemoryEntry summary = makeEntry("summary-1", "[摘要] 历史对话...", 20);
            memory.injectSummary(summary);

            assertTrue(memory.getCompressedSummaries().isEmpty());
            assertTrue(memory.retrieve("summary-1").isPresent());
        }
    }

    // ==================== 使用率与状态 ====================

    @Nested
    @DisplayName("使用率与状态")
    class UsageStats {

        @Test
        @DisplayName("getUsageRatio 正确计算")
        void usageRatio() {
            assertEquals(0.0, memory.getUsageRatio());
            memory.store(makeEntry("e1", "内容", 50));
            assertEquals(0.5, memory.getUsageRatio(), 0.01);
        }

        @Test
        @DisplayName("getStatusSummary 包含关键信息")
        void statusSummary() {
            memory.store(makeEntry("e1", "内容", 20));
            String status = memory.getStatusSummary();
            assertTrue(status.contains("短期记忆"));
            assertTrue(status.contains("1条"));
            assertTrue(status.contains("20 tokens"));
        }

        @Test
        @DisplayName("setMaxTokens 触发重新淘汰")
        void setMaxTokensTriggersEviction() {
            memory.store(makeEntry("e1", "a", 30));
            memory.store(makeEntry("e2", "b", 30));
            assertEquals(2, memory.size());

            memory.setMaxTokens(40);
            // 60 > 40, 淘汰 e1
            assertEquals(1, memory.size());
        }

        @Test
        @DisplayName("setMaxTokens 非法值抛异常")
        void setMaxTokensInvalid() {
            assertThrows(IllegalArgumentException.class, () -> memory.setMaxTokens(0));
            assertThrows(IllegalArgumentException.class, () -> memory.setMaxTokens(-1));
        }
    }

    // ==================== 分词/匹配 ====================

    @Nested
    @DisplayName("简化的分词与匹配")
    class Tokenizer {

        @Test
        @DisplayName("tokenize 按空格和标点分词")
        void tokenize() {
            var tokens = ConversationMemory.tokenize("你好 世界，测试");
            assertTrue(tokens.contains("你好"));
            assertTrue(tokens.contains("世界"));
            assertTrue(tokens.contains("测试"));
        }

        @Test
        @DisplayName("tokenize 空字符串返回空集合")
        void tokenizeEmpty() {
            assertTrue(ConversationMemory.tokenize("").isEmpty());
            assertTrue(ConversationMemory.tokenize(null).isEmpty());
        }

        @Test
        @DisplayName("matches 任一关键词匹配即返回 true")
        void matches() {
            assertTrue(ConversationMemory.matches("你好世界", java.util.Set.of("世界")));
            assertFalse(ConversationMemory.matches("你好世界", java.util.Set.of("苹果")));
        }
    }
}
