package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeChunk 单元测试。
 * <p>
 * 覆盖：
 * - 三种工厂方法（fileChunk / classChunk / methodChunk）
 * - toEmbeddingText() 格式验证
 * - record 自带的 equals / hashCode / toString
 * - 边界情况（空内容、长内容、特殊字符）
 */
@DisplayName("CodeChunk 代码块数据模型")
class CodeChunkTest {

    // ==================== 工厂方法 ====================

    @Nested
    @DisplayName("工厂方法")
    class FactoryMethods {

        @Test
        @DisplayName("fileChunk → type=file, name=filePath, lines=0-0")
        void fileChunk() {
            CodeChunk chunk = CodeChunk.fileChunk("src/main.py", "print('hello')");

            assertEquals("src/main.py", chunk.filePath());
            assertEquals("file", chunk.chunkType());
            assertEquals("src/main.py", chunk.name());      // name 等于 filePath
            assertEquals("print('hello')", chunk.content());
            assertEquals(0, chunk.startLine());
            assertEquals(0, chunk.endLine());
        }

        @Test
        @DisplayName("classChunk → type=class, 带行号范围")
        void classChunk() {
            String classHeader = "public class Agent {\n    private LlmClient client;";
            CodeChunk chunk = CodeChunk.classChunk(
                    "src/Agent.java", "Agent", classHeader, 10, 50);

            assertEquals("src/Agent.java", chunk.filePath());
            assertEquals("class", chunk.chunkType());
            assertEquals("Agent", chunk.name());
            assertEquals(classHeader, chunk.content());
            assertEquals(10, chunk.startLine());
            assertEquals(50, chunk.endLine());
        }

        @Test
        @DisplayName("methodChunk → type=method, name 含类名前缀")
        void methodChunk() {
            String methodBody = "public String run(String input) {\n    return input;\n}";
            CodeChunk chunk = CodeChunk.methodChunk(
                    "src/Agent.java", "Agent.run", methodBody, 42, 120);

            assertEquals("src/Agent.java", chunk.filePath());
            assertEquals("method", chunk.chunkType());
            assertEquals("Agent.run", chunk.name());
            assertEquals(methodBody, chunk.content());
            assertEquals(42, chunk.startLine());
            assertEquals(120, chunk.endLine());
        }
    }

    // ==================== toEmbeddingText ====================

    @Nested
    @DisplayName("toEmbeddingText")
    class EmbeddingText {

        @Test
        @DisplayName("格式为 [type:name] content")
        void basicFormat() {
            CodeChunk chunk = CodeChunk.methodChunk(
                    "src/Agent.java", "Agent.run",
                    "public String run() { return null; }", 1, 3);

            String text = chunk.toEmbeddingText();

            assertEquals("[method:Agent.run] public String run() { return null; }", text);
        }

        @Test
        @DisplayName("file 类型 → [file:path] content")
        void fileFormat() {
            CodeChunk chunk = CodeChunk.fileChunk("README.md", "# PaiCLI Demo");
            assertEquals("[file:README.md] # PaiCLI Demo", chunk.toEmbeddingText());
        }

        @Test
        @DisplayName("class 类型 → [class:ClassName] header")
        void classFormat() {
            CodeChunk chunk = CodeChunk.classChunk(
                    "src/Tool.java", "ToolRegistry",
                    "public class ToolRegistry {", 5, 5);
            assertEquals("[class:ToolRegistry] public class ToolRegistry {",
                    chunk.toEmbeddingText());
        }

        @Test
        @DisplayName("空内容 → [type:name] 后跟空格")
        void emptyContent() {
            CodeChunk chunk = CodeChunk.fileChunk("empty.txt", "");
            // String.format("[%s:%s] %s", "file", "empty.txt", "") → "[file:empty.txt] "
            assertEquals("[file:empty.txt] ", chunk.toEmbeddingText());
        }

        @Test
        @DisplayName("多行方法体 → 保留换行符")
        void multilineContent() {
            String code = "public void setup() {\n    init();\n    configure();\n}";
            CodeChunk chunk = CodeChunk.methodChunk("src/App.java", "App.setup", code, 1, 4);

            String text = chunk.toEmbeddingText();
            assertTrue(text.startsWith("[method:App.setup] "));
            assertTrue(text.contains("\n"));  // 换行符保留
            assertEquals("[method:App.setup] " + code, text);
        }

        @Test
        @DisplayName("含特殊字符的内容 → 不转义")
        void specialChars() {
            String code = "Map<String, List<Integer>> m = new HashMap<>();";
            CodeChunk chunk = CodeChunk.methodChunk("F.java", "F.m", code, 1, 1);

            String text = chunk.toEmbeddingText();
            assertTrue(text.contains("<String, List<Integer>>"));
            assertTrue(text.contains("HashMap<>()"));
        }
    }

    // ==================== record 行为 ====================

    @Nested
    @DisplayName("record 行为")
    class RecordBehavior {

        @Test
        @DisplayName("相同参数的两个实例 equals == true")
        void equality() {
            CodeChunk a = CodeChunk.methodChunk("F.java", "M.run", "code", 1, 5);
            CodeChunk b = CodeChunk.methodChunk("F.java", "M.run", "code", 1, 5);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("任一字段不同 → equals == false")
        void inequality() {
            CodeChunk base = CodeChunk.methodChunk("F.java", "M.run", "code", 1, 5);

            assertNotEquals(base, CodeChunk.methodChunk("X.java", "M.run", "code", 1, 5));  // 不同文件
            assertNotEquals(base, CodeChunk.classChunk("F.java", "M.run", "code", 1, 5));     // 不同类型
            assertNotEquals(base, CodeChunk.methodChunk("F.java", "M.stop", "code", 1, 5));   // 不同名称
            assertNotEquals(base, CodeChunk.methodChunk("F.java", "M.run", "other", 1, 5));   // 不同内容
            assertNotEquals(base, CodeChunk.methodChunk("F.java", "M.run", "code", 2, 5));    // 不同起始行
            assertNotEquals(base, CodeChunk.methodChunk("F.java", "M.run", "code", 1, 6));    // 不同结束行
        }

        @Test
        @DisplayName("toString 包含所有字段")
        void toStringContainsFields() {
            CodeChunk chunk = CodeChunk.fileChunk("test.py", "x = 1");
            String str = chunk.toString();

            assertTrue(str.contains("test.py"));
            assertTrue(str.contains("file"));
            assertTrue(str.contains("x = 1"));
        }

        @Test
        @DisplayName("null 字段不会在工厂方法中报错（record 允许 null）")
        void nullFields() {
            // record 本身不校验 null，这是设计上的灵活性
            CodeChunk chunk = new CodeChunk(null, "file", null, null, 0, 0);
            assertNull(chunk.filePath());
            assertNull(chunk.name());
            assertNull(chunk.content());
        }
    }

    // ==================== chunkType 一致性 ====================

    @Nested
    @DisplayName("chunkType 一致性")
    class ChunkTypeConsistency {

        @Test
        @DisplayName("三种工厂方法产生唯一的 chunkType 值")
        void uniqueTypes() {
            assertEquals("file", CodeChunk.fileChunk("f", "c").chunkType());
            assertEquals("class", CodeChunk.classChunk("f", "n", "c", 1, 1).chunkType());
            assertEquals("method", CodeChunk.methodChunk("f", "n", "c", 1, 1).chunkType());
        }

        @Test
        @DisplayName("toEmbeddingText 前缀与 chunkType 一致")
        void prefixMatchesType() {
            CodeChunk file = CodeChunk.fileChunk("a.py", "code");
            CodeChunk cls = CodeChunk.classChunk("A.java", "A", "code", 1, 10);
            CodeChunk method = CodeChunk.methodChunk("A.java", "A.m", "code", 2, 8);

            assertTrue(file.toEmbeddingText().startsWith("[file:"));
            assertTrue(cls.toEmbeddingText().startsWith("[class:"));
            assertTrue(method.toEmbeddingText().startsWith("[method:"));
        }
    }
}
