package edu.cqie.paiclidemo.memory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryRetriever（记忆检索器）单元测试。
 * <p>
 * 覆盖三个评分维度和混合检索排序逻辑。
 */
@DisplayName("MemoryRetriever - 记忆检索器")
class MemoryRetrieverTest {

    @TempDir
    Path tempDir;

    private ConversationMemory shortTerm;
    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        shortTerm = new ConversationMemory(1000);
        longTerm = new LongTermMemory(tempDir.toFile());
        retriever = new MemoryRetriever(shortTerm, longTerm);
    }

    // ==================== 辅助方法 ====================

    private MemoryEntry makeEntry(String id, String content, MemoryEntry.MemoryType type, Instant timestamp) {
        return new MemoryEntry(id, content, type, timestamp,
                Map.of("source", "test"), MemoryEntry.estimateTokens(content));
    }

    private MemoryEntry makeEntry(String id, String content, MemoryEntry.MemoryType type) {
        return makeEntry(id, content, type, Instant.now());
    }

    // ==================== 关键词匹配 ====================

    @Nested
    @DisplayName("关键词匹配评分")
    class KeywordScoring {

        @Test
        @DisplayName("精确包含完整查询 → 得分 1.0")
        void exactMatch() {
            MemoryEntry entry = makeEntry("e1", "用户偏好使用深色主题", MemoryEntry.MemoryType.FACT);
            double score = retriever.computeKeywordScore(entry, "深色主题");
            assertEquals(1.0, score, 0.01);
        }

        @Test
        @DisplayName("部分关键词匹配 → 按比例得分")
        void partialMatch() {
            MemoryEntry entry = makeEntry("e1", "项目使用 Java 和 Maven 构建", MemoryEntry.MemoryType.FACT);
            // 查询 "Java Maven" 有两个词，都命中 → 1.0
            double score = retriever.computeKeywordScore(entry, "Java Maven");
            assertEquals(1.0, score, 0.01);
        }

        @Test
        @DisplayName("部分命中 → 得分 = 命中数/总词数")
        void halfMatch() {
            MemoryEntry entry = makeEntry("e1", "项目使用 Java 语言", MemoryEntry.MemoryType.FACT);
            // "Java Python" 两个词，只命中 Java → 0.5
            double score = retriever.computeKeywordScore(entry, "Java Python");
            assertEquals(0.5, score, 0.01);
        }

        @Test
        @DisplayName("无关键词命中 → 得分 0")
        void noMatch() {
            MemoryEntry entry = makeEntry("e1", "今天天气不错", MemoryEntry.MemoryType.CONVERSATION);
            double score = retriever.computeKeywordScore(entry, "Java Maven");
            assertEquals(0.0, score, 0.01);
        }

        @Test
        @DisplayName("空查询 → 得分 0")
        void emptyQuery() {
            MemoryEntry entry = makeEntry("e1", "某些内容", MemoryEntry.MemoryType.FACT);
            double score = retriever.computeKeywordScore(entry, "");
            assertEquals(0.0, score, 0.01);
        }
    }

    // ==================== 时间衰减 ====================

    @Nested
    @DisplayName("时间衰减评分")
    class TimeDecayScoring {

        @Test
        @DisplayName("刚创建的记忆 → 衰减系数接近 1.0")
        void freshEntry() {
            MemoryEntry entry = makeEntry("e1", "内容", MemoryEntry.MemoryType.FACT, Instant.now());
            double decay = retriever.computeTimeDecay(entry);
            assertTrue(decay > 0.95, "刚创建的记忆衰减应接近 1.0, 实际: " + decay);
        }

        @Test
        @DisplayName("12 小时前的记忆 → 衰减系数约 0.5")
        void twelveHoursOld() {
            Instant twelveHoursAgo = Instant.now().minus(12, ChronoUnit.HOURS);
            MemoryEntry entry = makeEntry("e1", "内容", MemoryEntry.MemoryType.FACT, twelveHoursAgo);
            double decay = retriever.computeTimeDecay(entry);
            // 1.0 - 12/24 = 0.5
            assertEquals(0.5, decay, 0.05);
        }

        @Test
        @DisplayName("超过 24 小时的记忆 → 衰减固定为 0.5")
        void veryOldEntry() {
            Instant threeDaysAgo = Instant.now().minus(72, ChronoUnit.HOURS);
            MemoryEntry entry = makeEntry("e1", "内容", MemoryEntry.MemoryType.FACT, threeDaysAgo);
            double decay = retriever.computeTimeDecay(entry);
            assertEquals(0.5, decay, 0.01);
        }
    }

    // ==================== 混合检索与排序 ====================

    @Nested
    @DisplayName("混合检索与排序")
    class RetrievalAndRanking {

        @Test
        @DisplayName("从两层记忆中检索并按相关度排序")
        void mixedRetrieval() {
            // 短期记忆：一条相关的
            shortTerm.store(makeEntry("s1", "用户正在学习 Java 编程",
                    MemoryEntry.MemoryType.CONVERSATION));

            // 长期记忆：一条高度相关的
            longTerm.store(makeEntry("l1", "用户偏好 Java 语言，项目用 Maven 构建",
                    MemoryEntry.MemoryType.FACT));

            // 一条不相关的
            longTerm.store(makeEntry("l2", "今天天气很好，适合户外运动",
                    MemoryEntry.MemoryType.FACT));

            List<MemoryEntry> results = retriever.retrieve("Java Maven 编程", 5);

            // 应该有两条结果（不相关的被过滤掉）
            assertEquals(2, results.size());
            // 长期记忆的那条应该排第一（关键词命中更多 + 1.2 加权）
            assertTrue(results.get(0).getContent().contains("Maven"));
        }

        @Test
        @DisplayName("长期记忆获得 1.2 倍加权")
        void longTermBoost() {
            // 短期和长期存入完全相同内容的记忆
            shortTerm.store(makeEntry("s1", "Java 项目",
                    MemoryEntry.MemoryType.CONVERSATION));
            longTerm.store(makeEntry("l1", "Java 项目",
                    MemoryEntry.MemoryType.FACT));

            List<MemoryRetriever.ScoredEntry> scored = retriever.retrieveScored("Java", 5);

            assertEquals(2, scored.size());
            // 长期记忆的应该排在前面（1.2 倍加权）
            assertFalse(scored.get(0).fromShortTerm());
            assertTrue(scored.get(1).fromShortTerm());

            // 验证加权比例
            double longScore = scored.get(0).score();
            double shortScore = scored.get(1).score();
            assertEquals(longScore / shortScore, 1.2, 0.05);
        }

        @Test
        @DisplayName("limit 限制返回条数")
        void limitResults() {
            for (int i = 0; i < 10; i++) {
                longTerm.store(makeEntry("l" + i, "Java 项目 " + i,
                        MemoryEntry.MemoryType.FACT));
            }

            List<MemoryEntry> results = retriever.retrieve("Java", 3);
            assertEquals(3, results.size());
        }

        @Test
        @DisplayName("无匹配时返回空列表")
        void noMatch() {
            shortTerm.store(makeEntry("s1", "今天天气不错",
                    MemoryEntry.MemoryType.CONVERSATION));
            longTerm.store(makeEntry("l1", "周末去爬山",
                    MemoryEntry.MemoryType.FACT));

            List<MemoryEntry> results = retriever.retrieve("Java 编程", 5);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("仅检索长期记忆（retrieveLongTerm）")
        void retrieveLongTermOnly() {
            shortTerm.store(makeEntry("s1", "Java 短期对话",
                    MemoryEntry.MemoryType.CONVERSATION));
            longTerm.store(makeEntry("l1", "Java 长期事实",
                    MemoryEntry.MemoryType.FACT));

            List<MemoryEntry> results = retriever.retrieveLongTerm("Java", 5);
            assertEquals(1, results.size());
            assertEquals("l1", results.get(0).getId());
        }
    }

    // ==================== ScoredEntry 评分明细 ====================

    @Nested
    @DisplayName("评分明细（ScoredEntry）")
    class ScoredEntryDetails {

        @Test
        @DisplayName("ScoredEntry 包含各维度评分")
        void scoredEntryBreakdown() {
            longTerm.store(makeEntry("l1", "用户偏好 Java 语言",
                    MemoryEntry.MemoryType.FACT));

            List<MemoryRetriever.ScoredEntry> results = retriever.retrieveScored("Java", 5);

            assertEquals(1, results.size());
            MemoryRetriever.ScoredEntry se = results.get(0);

            // 验证各维度
            assertTrue(se.keywordScore() > 0, "关键词匹配分应 > 0");
            assertTrue(se.timeDecay() >= 0.5 && se.timeDecay() <= 1.0,
                    "时间衰减应在 [0.5, 1.0]");
            assertEquals(1.2, se.sourceWeight(), 0.01, "长期记忆来源加权应为 1.2");
            assertFalse(se.fromShortTerm());

            // 验证最终得分 = keyword × timeDecay × sourceWeight
            double expected = se.keywordScore() * se.timeDecay() * se.sourceWeight();
            assertEquals(expected, se.score(), 0.001);
        }

        @Test
        @DisplayName("短期记忆的 sourceWeight 为 1.0")
        void shortTermWeight() {
            shortTerm.store(makeEntry("s1", "Java 编程",
                    MemoryEntry.MemoryType.CONVERSATION));

            List<MemoryRetriever.ScoredEntry> results = retriever.retrieveScored("Java", 5);

            assertEquals(1, results.size());
            assertEquals(1.0, results.get(0).sourceWeight(), 0.01);
            assertTrue(results.get(0).fromShortTerm());
        }
    }

    // ==================== 上下文构建 ====================

    @Nested
    @DisplayName("上下文构建（buildContextForQuery）")
    class ContextBuilding {

        @Test
        @DisplayName("有相关长期记忆时生成上下文文本")
        void buildContext() {
            longTerm.store(makeEntry("l1", "用户偏好 Java 语言",
                    MemoryEntry.MemoryType.FACT));
            longTerm.store(makeEntry("l2", "项目使用 Maven 构建",
                    MemoryEntry.MemoryType.FACT));

            String context = retriever.buildContextForQuery("Java 项目", 500);
            assertTrue(context.contains("相关长期记忆"));
            assertTrue(context.contains("Java"));
        }

        @Test
        @DisplayName("无相关记忆时返回空字符串")
        void emptyContext() {
            longTerm.store(makeEntry("l1", "今天天气不错",
                    MemoryEntry.MemoryType.FACT));

            String context = retriever.buildContextForQuery("Java 编程", 500);
            assertEquals("", context);
        }

        @Test
        @DisplayName("上下文不超过 token 限制")
        void tokenLimit() {
            // 存入大量长期记忆
            for (int i = 0; i < 20; i++) {
                longTerm.store(makeEntry("l" + i,
                        "这是一条关于 Java 编程的很长的事实描述，包含很多技术细节，用于测试 token 限制功能 " + i,
                        MemoryEntry.MemoryType.FACT));
            }

            String context = retriever.buildContextForQuery("Java", 20);
            int tokens = MemoryEntry.estimateTokens(context);
            // 允许一定误差（按条目粒度截断）
            assertTrue(tokens < 100, "token 应受限, 实际: " + tokens);
        }
    }
}
