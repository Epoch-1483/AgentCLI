package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeChunker 单元测试。
 * <p>
 * 通过 {@link CodeChunker#chunkContent(String, String)} 直接传入字符串内容，
 * 无需创建临时文件，测试更快更可靠。
 */
@DisplayName("CodeChunker 代码分块器")
class CodeChunkerTest {

    private CodeChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new CodeChunker();
    }

    // ==================== Java AST 分块 ====================

    @Nested
    @DisplayName("Java AST 分块")
    class JavaAstChunking {

        @Test
        @DisplayName("简单类 + 两个方法 → 1 classChunk + 2 methodChunks")
        void simpleClassWithMethods() {
            String code = """
                    package com.example;

                    public class Calculator {
                        private int result;

                        public int add(int a, int b) {
                            return a + b;
                        }

                        public int multiply(int a, int b) {
                            return a * b;
                        }
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Calculator.java", code);

            // 1 classChunk + 2 methodChunks = 3
            assertEquals(3, chunks.size());

            // 第一个是 classChunk
            CodeChunk classChunk = chunks.get(0);
            assertEquals("class", classChunk.chunkType());
            assertEquals("Calculator", classChunk.name());
            assertTrue(classChunk.content().contains("public class Calculator"));

            // 后两个是 methodChunks
            CodeChunk addMethod = chunks.get(1);
            assertEquals("method", addMethod.chunkType());
            assertEquals("Calculator.int add(int, int)", addMethod.name());
            assertTrue(addMethod.content().contains("return a + b"));

            CodeChunk mulMethod = chunks.get(2);
            assertEquals("method", mulMethod.chunkType());
            assertEquals("Calculator.int multiply(int, int)", mulMethod.name());
            assertTrue(mulMethod.content().contains("return a * b"));
        }

        @Test
        @DisplayName("没有方法的类 → 只产生 classChunk")
        void classWithNoMethods() {
            String code = """
                    public class Config {
                        public static final String VERSION = "1.0";
                        public static final int MAX_RETRY = 3;
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Config.java", code);

            assertEquals(1, chunks.size());
            assertEquals("class", chunks.get(0).chunkType());
            assertEquals("Config", chunks.get(0).name());
        }

        @Test
        @DisplayName("类级 chunk 内容包含声明头部（最多前 5 行）")
        void classChunkHeaderLimit() {
            String code = """
                    public class Agent {
                        private LlmClient client;
                        private ToolRegistry registry;
                        private MemoryManager memory;
                        private List<Message> history;
                        private int maxIterations;
                        private String systemPrompt;

                        public String run(String input) {
                            return input;
                        }
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Agent.java", code);

            CodeChunk classChunk = chunks.get(0);
            assertEquals("class", classChunk.chunkType());
            // 类头部应该包含类声明和前几个字段
            assertTrue(classChunk.content().contains("public class Agent"));
            assertTrue(classChunk.content().contains("private LlmClient client"));
            // 方法体不应出现在 classChunk 里
            assertFalse(classChunk.content().contains("return input"));
        }

        @Test
        @DisplayName("方法 chunk 包含完整方法体 + 行号")
        void methodChunkContentAndLines() {
            String code = """
                    public class Service {
                        public void process(String data) {
                            validate(data);
                            transform(data);
                            save(data);
                        }
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Service.java", code);

            CodeChunk methodChunk = chunks.get(1); // index 1 = method
            assertEquals("method", methodChunk.chunkType());
            assertEquals("Service.void process(String)", methodChunk.name());
            assertTrue(methodChunk.content().contains("validate(data)"));
            assertTrue(methodChunk.content().contains("transform(data)"));
            assertTrue(methodChunk.content().contains("save(data)"));
            // 行号应该是有效的正数
            assertTrue(methodChunk.startLine() > 0);
            assertTrue(methodChunk.endLine() >= methodChunk.startLine());
        }

        @Test
        @DisplayName("接口也能被分块（interface 是 ClassOrInterfaceDeclaration 的子类型）")
        void interfaceChunking() {
            String code = """
                    public interface Memory {
                        void store(String entry);
                        List<String> search(String query);
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Memory.java", code);

            // 1 classChunk（接口也算） + 2 methodChunks
            assertEquals(3, chunks.size());
            assertEquals("class", chunks.get(0).chunkType());
            assertEquals("Memory", chunks.get(0).name());
        }

        @Test
        @DisplayName("多个类 → 每个类各自产生 chunks")
        void multipleClasses() {
            String code = """
                    public class Outer {
                        public void outerMethod() {}
                    }

                    class Helper {
                        public void helperMethod() {}
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Outer.java", code);

            // Outer: 1 class + 1 method = 2
            // Helper: 1 class + 1 method = 2
            // Total: 4
            assertEquals(4, chunks.size());

            // 验证类名
            assertTrue(chunks.stream().anyMatch(c -> c.name().equals("Outer")));
            assertTrue(chunks.stream().anyMatch(c -> c.name().equals("Helper")));
            assertTrue(chunks.stream().anyMatch(c -> c.name().equals("Outer.void outerMethod()")));
            assertTrue(chunks.stream().anyMatch(c -> c.name().equals("Helper.void helperMethod()")));
        }

        @Test
        @DisplayName("record 类型 → JavaParser 的 RecordDeclaration 不是 ClassOrInterfaceDeclaration，回退文本分块")
        void recordType() {
            String code = """
                    public record Point(int x, int y) {
                        public double distance() {
                            return Math.sqrt(x * x + y * y);
                        }
                    }
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Point.java", code);

            // record 是 RecordDeclaration，不是 ClassOrInterfaceDeclaration
            // 所以 findAll(ClassOrInterfaceDeclaration.class) 找不到 → 回退到文本分块
            assertFalse(chunks.isEmpty());
            assertEquals("file", chunks.get(0).chunkType());
        }
    }

    // ==================== 非 Java 文件分块 ====================

    @Nested
    @DisplayName("非 Java 文件分块")
    class NonJavaChunking {

        @Test
        @DisplayName("小文件（≤ 2000 字符）→ 单个 fileChunk")
        void smallFile() {
            String content = "# PaiCLI Demo\n\n一个简单的 AI Agent CLI 工具。";

            List<CodeChunk> chunks = chunker.chunkContent("README.md", content);

            assertEquals(1, chunks.size());
            assertEquals("file", chunks.get(0).chunkType());
            assertEquals("README.md", chunks.get(0).name());
            assertEquals(content, chunks.get(0).content());
        }

        @Test
        @DisplayName("大文件（> 2000 字符）→ 多个 fileChunk，带 #序号")
        void largeFile() {
            // 构造 > 2000 字符的内容：每行 50 字符，共 50 行 = 2500 字符
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append("Line ").append(String.valueOf(i + 1).repeat(1)).append(" ")
                        .append("x".repeat(43)).append("\n");
            }
            String content = sb.toString();
            assertTrue(content.length() > 2000, "测试前提：内容应超过 2000 字符");

            List<CodeChunk> chunks = chunker.chunkContent("data.csv", content);

            assertTrue(chunks.size() >= 2, "应该至少分成 2 段");
            for (int i = 0; i < chunks.size(); i++) {
                CodeChunk chunk = chunks.get(i);
                assertEquals("file", chunk.chunkType());
                assertEquals("data.csv#" + (i + 1), chunk.name());
                assertTrue(chunk.content().length() <= 2000,
                        "每段不超过 2000 字符，实际: " + chunk.content().length());
            }
        }

        @Test
        @DisplayName("恰好等于 MAX_CHUNK_CHARS → 单个 fileChunk（不触发分段）")
        void exactBoundary() {
            String content = "a".repeat(2000);

            List<CodeChunk> chunks = chunker.chunkContent("exact.txt", content);

            assertEquals(1, chunks.size());
            assertEquals(2000, chunks.get(0).content().length());
        }

        @Test
        @DisplayName("各种非 Java 扩展名 → 都走通用文本分块")
        void variousExtensions() {
            String content = "some content";

            for (String ext : List.of(".py", ".js", ".ts", ".md", ".xml", ".json", ".yaml")) {
                List<CodeChunk> chunks = chunker.chunkContent("file" + ext, content);
                assertEquals(1, chunks.size(), "扩展名 " + ext + " 应该产生 1 个 chunk");
                assertEquals("file", chunks.get(0).chunkType());
            }
        }
    }

    // ==================== AST 解析失败回退 ====================

    @Nested
    @DisplayName("AST 解析失败回退")
    class AstFallback {

        @Test
        @DisplayName("语法错误的 .java → 回退到文本分块")
        void invalidJavaSyntax() {
            String badCode = "this is not valid java code at all {{{}}}}}";

            List<CodeChunk> chunks = chunker.chunkContent("Bad.java", badCode);

            assertFalse(chunks.isEmpty());
            // 回退后应该产生 file 类型的 chunk
            assertEquals("file", chunks.get(0).chunkType());
        }

        @Test
        @DisplayName("空 .java 文件 → 回退到文本分块")
        void emptyJavaFile() {
            List<CodeChunk> chunks = chunker.chunkContent("Empty.java", "");

            assertEquals(1, chunks.size());
            assertEquals("file", chunks.get(0).chunkType());
        }

        @Test
        @DisplayName("纯注释 .java 文件（没有类声明）→ 回退到文本分块")
        void commentOnlyJavaFile() {
            String code = """
                    // This is just a comment file
                    // No class declarations here
                    // Just some notes
                    """;

            List<CodeChunk> chunks = chunker.chunkContent("Notes.java", code);

            assertEquals(1, chunks.size());
            assertEquals("file", chunks.get(0).chunkType());
        }

        @Test
        @DisplayName("大文件 AST 解析失败 → 回退后正确分段")
        void largeFileAstFallback() {
            // 无效 Java 语法 + 超过 2000 字符
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                sb.append("not java line ").append(i).append(" ").append("x".repeat(30)).append("\n");
            }
            String badCode = sb.toString();
            assertTrue(badCode.length() > 2000);

            List<CodeChunk> chunks = chunker.chunkContent("Big.java", badCode);

            assertTrue(chunks.size() >= 2);
            chunks.forEach(c -> assertEquals("file", c.chunkType()));
        }
    }

    // ==================== extractLines ====================

    @Nested
    @DisplayName("extractLines 工具方法")
    class ExtractLines {

        @Test
        @DisplayName("提取中间几行")
        void extractMiddleLines() {
            String content = "line1\nline2\nline3\nline4\nline5";

            String result = chunker.extractLines(content, 2, 4);

            assertEquals("line2\nline3\nline4", result);
        }

        @Test
        @DisplayName("提取第一行")
        void extractFirstLine() {
            String content = "first\nsecond\nthird";

            String result = chunker.extractLines(content, 1, 1);

            assertEquals("first", result);
        }

        @Test
        @DisplayName("endLine 超过总行数 → 截断到末尾")
        void endLineBeyondContent() {
            String content = "line1\nline2";

            String result = chunker.extractLines(content, 1, 100);

            assertEquals("line1\nline2", result);
        }

        @Test
        @DisplayName("处理 Windows 换行符 \\r\\n")
        void windowsLineEndings() {
            String content = "line1\r\nline2\r\nline3";

            String result = chunker.extractLines(content, 1, 2);

            assertEquals("line1\nline2", result);
        }
    }

    // ==================== chunkFile（集成测试） ====================

    @Nested
    @DisplayName("chunkFile 集成（使用临时文件）")
    class ChunkFileIntegration {

        @Test
        @DisplayName("读取临时 .java 文件并分块")
        void chunkTempJavaFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
            java.nio.file.Path file = tempDir.resolve("Test.java");
            java.nio.file.Files.writeString(file, """
                    public class Test {
                        public void hello() {
                            System.out.println("Hello");
                        }
                    }
                    """);

            List<CodeChunk> chunks = chunker.chunkFile(file);

            assertFalse(chunks.isEmpty());
            assertTrue(chunks.stream().anyMatch(c -> c.chunkType().equals("class")));
            assertTrue(chunks.stream().anyMatch(c -> c.chunkType().equals("method")));
        }

        @Test
        @DisplayName("读取临时 .py 文件并分块")
        void chunkTempPyFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
            java.nio.file.Path file = tempDir.resolve("hello.py");
            java.nio.file.Files.writeString(file, "def hello():\n    print('Hello')");

            List<CodeChunk> chunks = chunker.chunkFile(file);

            assertEquals(1, chunks.size());
            assertEquals("file", chunks.get(0).chunkType());
        }
    }
}
