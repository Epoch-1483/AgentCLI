package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchResultFormatter 单元测试。
 */
@DisplayName("SearchResultFormatter 结果格式化")
class SearchResultFormatterTest {

    private VectorStore.SearchResult result(String name, String type,
                                            String content, double sim) {
        return new VectorStore.SearchResult(
                "src/" + name + ".java", type, name, content, sim);
    }

    // ==================== formatForCli ====================

    @Nested
    @DisplayName("formatForCli")
    class CliFormat {

        @Test
        @DisplayName("包含结果数量、摘要、编号列表")
        void basicStructure() {
            List<VectorStore.SearchResult> results = List.of(
                    result("Agent", "class", "public class Agent {}", 0.95),
                    result("Agent.run", "method", "public String run() {}", 0.80)
            );

            String output = SearchResultFormatter.formatForCli("Agent", results);

            assertTrue(output.contains("📋 找到 2 个相关代码块"));
            assertTrue(output.contains("1. [class:Agent]"));
            assertTrue(output.contains("2. [method:Agent.run]"));
            assertTrue(output.contains("0.950"));
            assertTrue(output.contains("0.800"));
        }

        @Test
        @DisplayName("CLI 片段截断为 120 字符")
        void cliSnippetLength() {
            String longContent = "x".repeat(200);
            List<VectorStore.SearchResult> results = List.of(
                    result("Big", "file", longContent, 0.5));

            String output = SearchResultFormatter.formatForCli("test", results);

            assertTrue(output.contains("..."), "超长内容应被截断并显示...");
            // 截断后片段 = 120 个 'x' + "..."
            assertTrue(output.contains("x".repeat(120) + "..."));
        }

        @Test
        @DisplayName("空结果 → 显示 '没有命中'")
        void emptyResults() {
            String output = SearchResultFormatter.formatForCli(
                    "test", List.of());

            assertTrue(output.contains("找到 0 个"));
            assertTrue(output.contains("没有命中"));
        }

        @Test
        @DisplayName("代码片段中换行被缩进对齐")
        void snippetIndentation() {
            String code = "line1\nline2\nline3";
            List<VectorStore.SearchResult> results = List.of(
                    result("Test", "method", code, 0.5));

            String output = SearchResultFormatter.formatForCli("test", results);

            // 换行后应有 "   " 前缀（缩进对齐）
            assertTrue(output.contains("line1\n   line2\n   line3"),
                    "换行后应缩进对齐");
        }
    }

    // ==================== formatForTool ====================

    @Nested
    @DisplayName("formatForTool")
    class ToolFormat {

        @Test
        @DisplayName("Tool 片段截断为 180 字符（比 CLI 的 120 更长）")
        void toolSnippetLength() {
            String longContent = "y".repeat(200);
            List<VectorStore.SearchResult> results = List.of(
                    result("Big", "file", longContent, 0.5));

            String output = SearchResultFormatter.formatForTool("test", results);

            assertTrue(output.contains("y".repeat(180) + "..."),
                    "Tool 模式截断为 180 字符");
        }

        @Test
        @DisplayName("包含 '检索摘要' 和 '检索结果' 标题")
        void structureHeaders() {
            List<VectorStore.SearchResult> results = List.of(
                    result("Agent", "class", "class Agent {}", 0.9));

            String output = SearchResultFormatter.formatForTool("Agent", results);

            assertTrue(output.contains("检索摘要:"));
            assertTrue(output.contains("检索结果:"));
        }

        @Test
        @DisplayName("Tool 和 CLI 输出结构不同")
        void differentFromCli() {
            List<VectorStore.SearchResult> results = List.of(
                    result("A", "class", "class A {}", 0.9));

            String cli = SearchResultFormatter.formatForCli("A", results);
            String tool = SearchResultFormatter.formatForTool("A", results);

            assertTrue(cli.contains("📋"), "CLI 有 emoji");
            assertFalse(tool.contains("📋"), "Tool 无 emoji");
            assertTrue(tool.contains("检索摘要:"), "Tool 用中文标题");
        }
    }

    // ==================== buildSummary ====================

    @Nested
    @DisplayName("buildSummary")
    class SummaryBuilder {

        @Test
        @DisplayName("摘要包含 top 结果的 type 和 name")
        void containsTopResult() {
            List<VectorStore.SearchResult> results = List.of(
                    result("Agent", "class", "class Agent {}", 0.95));

            String summary = SearchResultFormatter.buildSummary("Agent", results);

            assertTrue(summary.contains("[class:Agent]"));
        }

        @Test
        @DisplayName("摘要包含文件名列表")
        void containsFileNames() {
            List<VectorStore.SearchResult> results = List.of(
                    result("Agent", "class", "class Agent {}", 0.9),
                    result("ToolRegistry", "class", "class ToolRegistry {}", 0.8));

            String summary = SearchResultFormatter.buildSummary("Agent Tool", results);

            assertTrue(summary.contains("Agent.java"));
            assertTrue(summary.contains("ToolRegistry.java"));
        }

        @Test
        @DisplayName("摘要包含查询关键词")
        void containsQueryTokens() {
            List<VectorStore.SearchResult> results = List.of(
                    result("Agent", "class", "class Agent {}", 0.9));

            String summary = SearchResultFormatter.buildSummary("Agent run 方法", results);

            // RagQueryTokenizer 应该提取出 "Agent" 和 "run"
            assertTrue(summary.contains("Agent") || summary.contains("run"),
                    "摘要应包含查询关键词");
        }

        @Test
        @DisplayName("空结果 → '没有命中可用代码块'")
        void emptySummary() {
            String summary = SearchResultFormatter.buildSummary("test", List.of());
            assertTrue(summary.contains("没有命中"));
        }

        @Test
        @DisplayName("超过 3 个文件 → 显示 '等文件'")
        void manyFiles() {
            List<VectorStore.SearchResult> results = List.of(
                    result("A", "class", "class A {}", 0.9),
                    result("B", "class", "class B {}", 0.8),
                    result("C", "class", "class C {}", 0.7),
                    result("D", "class", "class D {}", 0.6));

            String summary = SearchResultFormatter.buildSummary("test", results);

            assertTrue(summary.contains("等文件"),
                    "超过 3 个文件应显示'等文件'");
        }
    }

    // ==================== buildSnippet ====================

    @Nested
    @DisplayName("buildSnippet")
    class SnippetBuilder {

        @Test
        @DisplayName("短内容 → 不截断")
        void shortContent() {
            assertEquals("hello world",
                    SearchResultFormatter.buildSnippet("hello world", 120));
        }

        @Test
        @DisplayName("长内容 → 截断 + '...'")
        void longContent() {
            String result = SearchResultFormatter.buildSnippet("a".repeat(200), 50);
            assertEquals(53, result.length()); // 50 + "..."
            assertTrue(result.endsWith("..."));
        }

        @Test
        @DisplayName("null 内容 → '(无内容片段)'")
        void nullContent() {
            assertEquals("(无内容片段)",
                    SearchResultFormatter.buildSnippet(null, 120));
        }

        @Test
        @DisplayName("空内容 → '(无内容片段)'")
        void emptyContent() {
            assertEquals("(无内容片段)",
                    SearchResultFormatter.buildSnippet("", 120));
        }

        @Test
        @DisplayName("Windows 换行 \\r\\n → 统一为 \\n")
        void normalizeLineEndings() {
            String result = SearchResultFormatter.buildSnippet("a\r\nb\r\nc", 120);
            assertEquals("a\nb\nc", result);
        }
    }

    // ==================== shortenPath ====================

    @Nested
    @DisplayName("shortenPath")
    class PathShortener {

        @Test
        @DisplayName("短路径（≤3 段）→ 不变")
        void shortPath() {
            assertEquals("src/Agent.java",
                    SearchResultFormatter.shortenPath("src/Agent.java"));
        }

        @Test
        @DisplayName("长路径 → 只保留最后 3 段")
        void longPath() {
            String result = SearchResultFormatter.shortenPath(
                    "/Users/dev/project/src/main/Agent.java");
            assertEquals("src/main/Agent.java".replace('/', java.io.File.separatorChar),
                    result);
        }

        @Test
        @DisplayName("恰好 3 段 → 不变")
        void exactThreeSegments() {
            assertEquals("src/main/Agent.java",
                    SearchResultFormatter.shortenPath("src/main/Agent.java"));
        }
    }
}
