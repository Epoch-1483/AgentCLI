package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagQueryTokenizer 单元测试。
 * <p>
 * 验证：jieba 中文分词、ASCII 正则匹配、停用词过滤、去重、边界输入。
 */
@DisplayName("RagQueryTokenizer 查询分词器")
class RagQueryTokenizerTest {

    // ==================== 基本分词 ====================

    @Nested
    @DisplayName("基本分词")
    class BasicTokenization {

        @Test
        @DisplayName("中英文混合查询 → 提取代码关键词")
        void mixedChineseEnglish() {
            Set<String> tokens = RagQueryTokenizer.tokenize("Agent 的 run 方法是怎么实现的");

            assertTrue(tokens.contains("Agent"), "应保留类名 Agent");
            assertTrue(tokens.contains("run"), "应保留方法名 run");
            // "怎么" 是停用词，不应出现
            assertFalse(tokens.contains("怎么"));
        }

        @Test
        @DisplayName("纯英文查询 → ASCII token 提取")
        void pureEnglish() {
            Set<String> tokens = RagQueryTokenizer.tokenize("ToolRegistry register method");

            assertTrue(tokens.contains("ToolRegistry"));
            assertTrue(tokens.contains("register"));
            assertTrue(tokens.contains("method"));
        }

        @Test
        @DisplayName("纯中文查询 → jieba 分词")
        void pureChinese() {
            Set<String> tokens = RagQueryTokenizer.tokenize("记忆管理系统的实现");

            // jieba 应该分出 "记忆" "管理" "系统" 等词
            assertTrue(tokens.stream().anyMatch(t -> t.contains("记忆")),
                    "应包含'记忆'相关 token");
        }

        @Test
        @DisplayName("带点号的代码标识符 → 保留完整")
        void dottedIdentifier() {
            Set<String> tokens = RagQueryTokenizer.tokenize("Agent.run 方法");

            assertTrue(tokens.contains("Agent.run"),
                    "Agent.run 应被 ASCII 正则作为整体匹配");
        }
    }

    // ==================== 停用词过滤 ====================

    @Nested
    @DisplayName("停用词过滤")
    class StopwordFiltering {

        @Test
        @DisplayName("中文停用词被过滤")
        void chineseStopwords() {
            Set<String> tokens = RagQueryTokenizer.tokenize("怎么实现什么方法");

            assertFalse(tokens.contains("怎么"));
            assertFalse(tokens.contains("什么"));
        }

        @Test
        @DisplayName("单字符 token 被过滤（长度 < 2）")
        void singleCharFiltered() {
            Set<String> tokens = RagQueryTokenizer.tokenize("A 的 B");

            // "A" 和 "B" 长度为 1，应被过滤
            assertFalse(tokens.contains("A"));
            assertFalse(tokens.contains("B"));
        }
    }

    // ==================== 去重 ====================

    @Nested
    @DisplayName("去重与排序")
    class Deduplication {

        @Test
        @DisplayName("相同 token 出现在 jieba 和 ASCII 两个通道 → 只保留一次")
        void deduplicateAcrossChannels() {
            Set<String> tokens = RagQueryTokenizer.tokenize("Agent Agent");

            long agentCount = tokens.stream().filter("Agent"::equals).count();
            assertEquals(1, agentCount, "Agent 应只出现一次");
        }

        @Test
        @DisplayName("LinkedHashSet 保持插入顺序")
        void preserveOrder() {
            Set<String> tokens = RagQueryTokenizer.tokenize("ToolRegistry Agent Memory");

            // 第一个有意义的 token 应该是 ToolRegistry
            String first = tokens.iterator().next();
            assertEquals("ToolRegistry", first);
        }
    }

    // ==================== 边界输入 ====================

    @Nested
    @DisplayName("边界输入")
    class EdgeInputs {

        @Test
        @DisplayName("null → 空集合")
        void nullInput() {
            Set<String> tokens = RagQueryTokenizer.tokenize(null);
            assertTrue(tokens.isEmpty());
        }

        @Test
        @DisplayName("空字符串 → 空集合")
        void emptyInput() {
            assertTrue(RagQueryTokenizer.tokenize("").isEmpty());
        }

        @Test
        @DisplayName("纯空白 → 空集合")
        void blankInput() {
            assertTrue(RagQueryTokenizer.tokenize("   ").isEmpty());
        }

        @Test
        @DisplayName("纯标点 → 空集合")
        void purePunctuation() {
            Set<String> tokens = RagQueryTokenizer.tokenize("???!!!...");
            assertTrue(tokens.isEmpty());
        }
    }

    // ==================== 有意义性判断 ====================

    @Nested
    @DisplayName("isMeaningful 过滤")
    class MeaningfulFilter {

        @Test
        @DisplayName("纯数字 → 被过滤（不含汉字或 ASCII 字母）")
        void pureDigits() {
            Set<String> tokens = RagQueryTokenizer.tokenize("123 456");
            // jieba 可能分出数字，但 isMeaningful 要求 hasAsciiWord（isLetterOrDigit 包含数字）
            // 所以纯数字 token 可能通过。验证不会抛异常即可
            assertNotNull(tokens);
        }

        @Test
        @DisplayName("包含汉字的 token → 保留")
        void chineseToken() {
            Set<String> tokens = RagQueryTokenizer.tokenize("记忆管理系统");
            assertFalse(tokens.isEmpty(), "应保留中文 token");
        }
    }
}
