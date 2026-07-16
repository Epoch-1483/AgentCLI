package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeIndex 单元测试。
 * <p>
 * 使用临时目录存放测试文件和 SQLite 数据库，
 * 用 StubEmbeddingClient 替代真实 API 调用（返回固定向量）。
 */
@DisplayName("CodeIndex 索引流水线")
class CodeIndexTest {

    @TempDir
    Path tempDir;

    /** 收集进度消息，用于断言 */
    private final List<String> progressMessages = new ArrayList<>();

    /** 桩 EmbeddingClient：不调用真实 API，返回固定维度向量 */
    static class StubEmbeddingClient extends EmbeddingClient {
        private int callCount = 0;

        StubEmbeddingClient() {
            super("test", "stub-model", "http://unused", "");
        }

        @Override
        public float[] embed(String text) {
            callCount++;
            // 根据文本内容生成不同向量（用于区分检索结果）
            if (text.contains("class")) {
                return new float[]{1.0f, 0.0f, 0.0f};
            } else if (text.contains("method") || text.contains("void")) {
                return new float[]{0.0f, 1.0f, 0.0f};
            }
            return new float[]{0.5f, 0.5f, 0.0f};
        }

        int getCallCount() {
            return callCount;
        }
    }

    private CodeIndex createIndex(StubEmbeddingClient stubClient) {
        return new CodeIndex(stubClient, progressMessages::add) {
            @Override
            VectorStore createVectorStore(String projectPath) throws SQLException {
                // 将 SQLite 数据库指向 tempDir
                return new VectorStore(projectPath, tempDir.toString());
            }
        };
    }

    // ==================== 文件收集 ====================

    @Nested
    @DisplayName("文件收集 (collectFiles)")
    class FileCollection {

        @Test
        @DisplayName("收集 .java 和 .py 文件")
        void collectCodeFiles() throws IOException {
            Files.writeString(tempDir.resolve("App.java"), "class App {}");
            Files.writeString(tempDir.resolve("script.py"), "print('hi')");
            Files.writeString(tempDir.resolve("image.png"), "binary data");

            CodeIndex index = createIndex(new StubEmbeddingClient());
            List<Path> files = new ArrayList<>();
            index.collectFiles(tempDir, files);

            assertEquals(2, files.size());
            assertTrue(files.stream().anyMatch(f -> f.toString().endsWith(".java")));
            assertTrue(files.stream().anyMatch(f -> f.toString().endsWith(".py")));
        }

        @Test
        @DisplayName("跳过 node_modules / target / .git 目录")
        void skipNonCodeDirs() throws IOException {
            Path src = Files.createDirectory(tempDir.resolve("src"));
            Path nodeModules = Files.createDirectory(tempDir.resolve("node_modules"));
            Path target = Files.createDirectory(tempDir.resolve("target"));
            Path git = Files.createDirectory(tempDir.resolve(".git"));

            Files.writeString(src.resolve("Main.java"), "class Main {}");
            Files.writeString(nodeModules.resolve("lib.js"), "module.exports = {}");
            Files.writeString(target.resolve("output.class"), "bytecode");
            Files.writeString(git.resolve("config"), "git config");

            CodeIndex index = createIndex(new StubEmbeddingClient());
            List<Path> files = new ArrayList<>();
            index.collectFiles(tempDir, files);

            assertEquals(1, files.size());
            assertTrue(files.get(0).toString().contains("Main.java"));
        }

        @Test
        @DisplayName("跳过以 . 开头的隐藏目录")
        void skipHiddenDirs() throws IOException {
            Path hidden = Files.createDirectory(tempDir.resolve(".hidden"));
            Files.writeString(hidden.resolve("secret.java"), "class Secret {}");
            Files.writeString(tempDir.resolve("Public.java"), "class Public {}");

            CodeIndex index = createIndex(new StubEmbeddingClient());
            List<Path> files = new ArrayList<>();
            index.collectFiles(tempDir, files);

            assertEquals(1, files.size());
            assertTrue(files.get(0).toString().contains("Public.java"));
        }

        @Test
        @DisplayName("递归收集子目录中的文件")
        void recursiveCollection() throws IOException {
            Path sub1 = Files.createDirectories(tempDir.resolve("src/main"));
            Path sub2 = Files.createDirectories(tempDir.resolve("src/test"));

            Files.writeString(sub1.resolve("App.java"), "class App {}");
            Files.writeString(sub2.resolve("AppTest.java"), "class AppTest {}");

            CodeIndex index = createIndex(new StubEmbeddingClient());
            List<Path> files = new ArrayList<>();
            index.collectFiles(tempDir, files);

            assertEquals(2, files.size());
        }

        @Test
        @DisplayName("不收集非代码扩展名（.exe, .jar, .png）")
        void skipBinaryFiles() throws IOException {
            Files.writeString(tempDir.resolve("app.exe"), "binary");
            Files.writeString(tempDir.resolve("lib.jar"), "binary");
            Files.writeString(tempDir.resolve("logo.png"), "binary");
            Files.writeString(tempDir.resolve("README.md"), "# Title");

            CodeIndex index = createIndex(new StubEmbeddingClient());
            List<Path> files = new ArrayList<>();
            index.collectFiles(tempDir, files);

            assertEquals(1, files.size());
            assertTrue(files.get(0).toString().endsWith(".md"));
        }
    }

    // ==================== 端到端索引 ====================

    @Nested
    @DisplayName("端到端索引 (index)")
    class EndToEndIndex {

        @Test
        @DisplayName("索引一个 Java 文件 → chunk + embedding 写入 VectorStore")
        void indexJavaFile() throws Exception {
            Files.writeString(tempDir.resolve("Agent.java"), """
                    public class Agent {
                        public String run(String input) {
                            return input;
                        }
                    }
                    """);

            StubEmbeddingClient stub = new StubEmbeddingClient();
            CodeIndex index = createIndex(stub);
            CodeIndex.IndexResult result = index.index(tempDir.toString());

            assertTrue(result.chunkCount() > 0, "应该产生至少 1 个 chunk");
            assertTrue(result.message().contains("索引完成"));
            assertTrue(stub.getCallCount() > 0, "应该调用了 Embedding API");

            // 验证数据持久化到 VectorStore
            try (VectorStore store = new VectorStore(tempDir.toAbsolutePath().normalize().toString(),
                    tempDir.toString())) {
                VectorStore.IndexStats stats = store.getStats();
                assertEquals(result.chunkCount(), stats.chunkCount());
            }
        }

        @Test
        @DisplayName("索引混合文件（Java + Python + Markdown）")
        void indexMixedFiles() throws IOException {
            Files.writeString(tempDir.resolve("App.java"), """
                    public class App {
                        public static void main(String[] args) {}
                    }
                    """);
            Files.writeString(tempDir.resolve("utils.py"), "def hello():\n    print('hi')");
            Files.writeString(tempDir.resolve("README.md"), "# Project\n\nDescription.");

            StubEmbeddingClient stub = new StubEmbeddingClient();
            CodeIndex index = createIndex(stub);
            CodeIndex.IndexResult result = index.index(tempDir.toString());

            // Java: 1 classChunk + 1 methodChunk = 2
            // Python: 1 fileChunk (small file)
            // Markdown: 1 fileChunk (small file)
            assertTrue(result.chunkCount() >= 4,
                    "混合文件应产生多个 chunk，实际: " + result.chunkCount());
        }

        @Test
        @DisplayName("重复索引 → 全量替换（旧数据被清空）")
        void reindexReplacesAll() throws IOException {
            // 第一次索引：2 个文件
            Files.writeString(tempDir.resolve("A.java"), "public class A {}");
            Files.writeString(tempDir.resolve("B.java"), "public class B {}");

            StubEmbeddingClient stub = new StubEmbeddingClient();
            CodeIndex index = createIndex(stub);
            CodeIndex.IndexResult first = index.index(tempDir.toString());
            int firstCount = first.chunkCount();

            // 删除一个文件，重新索引
            Files.delete(tempDir.resolve("B.java"));
            CodeIndex.IndexResult second = index.index(tempDir.toString());

            assertTrue(second.chunkCount() < firstCount,
                    "删除文件后重新索引，chunk 数应减少");
        }

        @Test
        @DisplayName("空目录 → 返回 0 chunks + 警告消息")
        void emptyDirectory() {
            CodeIndex index = createIndex(new StubEmbeddingClient());
            CodeIndex.IndexResult result = index.index(tempDir.toString());

            assertEquals(0, result.chunkCount());
            assertTrue(result.message().contains("未找到"));
        }

        @Test
        @DisplayName("不存在的路径 → 返回 0 + 错误消息")
        void nonExistentPath() {
            CodeIndex index = createIndex(new StubEmbeddingClient());
            CodeIndex.IndexResult result = index.index("/nonexistent/path/12345");

            assertEquals(0, result.chunkCount());
            assertTrue(result.message().contains("不存在"));
        }

        @Test
        @DisplayName("单个文件解析失败 → 不中断整体流程")
        void individualFileFailure() throws IOException {
            // 一个正常的 Java 文件
            Files.writeString(tempDir.resolve("Good.java"), """
                    public class Good {
                        public void ok() {}
                    }
                    """);
            // 一个语法严重错误的 Java 文件（CodeChunker 会回退到文本分块，不会抛异常）
            Files.writeString(tempDir.resolve("Bad.java"), "{{{{ invalid java");

            StubEmbeddingClient stub = new StubEmbeddingClient();
            CodeIndex index = createIndex(stub);
            CodeIndex.IndexResult result = index.index(tempDir.toString());

            // 两个文件都应该成功处理（Bad.java 回退到文本分块）
            assertTrue(result.chunkCount() >= 2, "应该有来自两个文件的 chunks");
            assertTrue(result.message().contains("索引完成"));
        }
    }

    // ==================== 进度回调 ====================

    @Nested
    @DisplayName("进度回调 (ProgressListener)")
    class ProgressReporting {

        @Test
        @DisplayName("索引过程发送进度消息")
        void progressMessagesEmitted() throws IOException {
            Files.writeString(tempDir.resolve("Test.java"), "public class Test {}");

            CodeIndex index = createIndex(new StubEmbeddingClient());
            index.index(tempDir.toString());

            assertFalse(progressMessages.isEmpty(), "应该发送了进度消息");
            assertTrue(progressMessages.stream().anyMatch(m -> m.contains("开始索引")),
                    "应包含'开始索引'消息");
            assertTrue(progressMessages.stream().anyMatch(m -> m.contains("发现")),
                    "应包含'发现 N 个文件'消息");
            assertTrue(progressMessages.stream().anyMatch(m -> m.contains("索引完成")),
                    "应包含'索引完成'消息");
        }

        @Test
        @DisplayName("noop listener 不抛异常")
        void noopListener() {
            CodeIndex index = new CodeIndex(new StubEmbeddingClient(), CodeIndex.ProgressListener.noop());
            // 不应抛异常
            CodeIndex.IndexResult result = index.index("/nonexistent/path");
            assertEquals(0, result.chunkCount());
        }
    }

    // ==================== IndexResult ====================

    @Nested
    @DisplayName("IndexResult record")
    class IndexResultTests {

        @Test
        @DisplayName("record 字段正确")
        void fieldsCorrect() {
            CodeIndex.IndexResult r = new CodeIndex.IndexResult(42, 5, "done");
            assertEquals(42, r.chunkCount());
            assertEquals(5, r.relationCount());
            assertEquals("done", r.message());
        }
    }
}
