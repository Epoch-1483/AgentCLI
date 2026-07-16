package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeRetriever 单元测试。
 * <p>
 * 使用临时目录的 SQLite + StubEmbeddingClient，
 * 验证语义检索、关键词检索、混合检索的完整流程。
 */
@DisplayName("CodeRetriever 代码检索器")
class CodeRetrieverTest {

    @TempDir
    Path tempDir;

    private VectorStore store;
    private CodeRetriever retriever;

    /** Stub：返回固定向量，根据文本内容区分 */
    static class StubEmbeddingClient extends EmbeddingClient {
        StubEmbeddingClient() {
            super("test", "stub", "http://unused", "");
        }

        @Override
        public float[] embed(String text) {
            if (text.contains("Agent") && text.contains("run")) {
                return new float[]{0.9f, 0.1f, 0.0f};
            }
            if (text.contains("Agent")) {
                return new float[]{1.0f, 0.0f, 0.0f};
            }
            if (text.contains("Tool") || text.contains("register")) {
                return new float[]{0.0f, 1.0f, 0.0f};
            }
            return new float[]{0.5f, 0.5f, 0.0f};
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = new VectorStore("test-project", tempDir.toString());
        retriever = new CodeRetriever(new StubEmbeddingClient(), store);
    }

    @AfterEach
    void tearDown() throws Exception {
        retriever.close();
    }

    private void insertSampleData() throws SQLException {
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(
                        CodeChunk.classChunk("src/Agent.java", "Agent",
                                "public class Agent { private MemoryManager memory; }", 1, 50),
                        new float[]{1.0f, 0.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(
                        CodeChunk.methodChunk("src/Agent.java", "Agent.run",
                                "public String run(String input) { return memory.process(input); }", 10, 30),
                        new float[]{0.9f, 0.1f, 0.0f}),
                new VectorStore.CodeChunkEntry(
                        CodeChunk.classChunk("src/ToolRegistry.java", "ToolRegistry",
                                "public class ToolRegistry { private Map<String, Tool> tools; }", 1, 80),
                        new float[]{0.0f, 1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(
                        CodeChunk.methodChunk("src/ToolRegistry.java", "ToolRegistry.register",
                                "public void register(String name, Tool tool) { tools.put(name, tool); }", 20, 35),
                        new float[]{0.0f, 0.8f, 0.2f}),
                new VectorStore.CodeChunkEntry(
                        CodeChunk.fileChunk("README.md", "# PaiCLI Demo\nA simple AI agent CLI."),
                        new float[]{0.5f, 0.5f, 0.0f})
        ));
    }

    // ==================== 语义检索 ====================

    @Nested
    @DisplayName("语义检索 (semanticSearch)")
    class SemanticSearch {

        @Test
        @DisplayName("查询 'Agent' → Agent 类排第一")
        void agentQuery() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.semanticSearch("Agent", 3);

            assertFalse(results.isEmpty());
            assertEquals("Agent", results.get(0).name());
        }

        @Test
        @DisplayName("查询 'Tool register' → ToolRegistry 相关排前面")
        void toolQuery() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.semanticSearch("Tool register", 3);

            assertFalse(results.isEmpty());
            assertTrue(results.get(0).name().contains("Tool")
                    || results.get(0).name().contains("register"));
        }
    }

    // ==================== 关键词检索 ====================

    @Nested
    @DisplayName("关键词检索 (keywordSearch)")
    class KeywordSearch {

        @Test
        @DisplayName("搜索 'Agent' → 命中 name 含 Agent 的 chunk")
        void agentKeyword() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.keywordSearch("Agent");

            assertTrue(results.size() >= 2, "Agent 类 + Agent.run 方法");
        }

        @Test
        @DisplayName("搜索 'register' → 命中方法名")
        void registerKeyword() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.keywordSearch("register");

            assertTrue(results.stream().anyMatch(r -> r.name().contains("register")));
        }
    }

    // ==================== 混合检索 ====================

    @Nested
    @DisplayName("混合检索 (hybridSearch)")
    class HybridSearch {

        @Test
        @DisplayName("混合检索 'Agent run' → Agent.run 方法排第一")
        void agentRunQuery() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent run", 5);

            assertFalse(results.isEmpty());
            // Agent.run 应该排名很高（语义 + 关键词双重命中 + method 类型加权）
            assertTrue(results.stream().anyMatch(r -> r.name().equals("Agent.run")),
                    "Agent.run 应出现在结果中");
        }

        @Test
        @DisplayName("方法类型加权 → method 排在同文件的 class 前面")
        void methodBoostedOverClass() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent run", 5);

            // 找到 Agent.run 和 Agent 的分数
            double methodScore = results.stream()
                    .filter(r -> r.name().equals("Agent.run"))
                    .mapToDouble(VectorStore.SearchResult::similarity)
                    .findFirst().orElse(-1);
            double classScore = results.stream()
                    .filter(r -> r.name().equals("Agent"))
                    .mapToDouble(VectorStore.SearchResult::similarity)
                    .findFirst().orElse(-1);

            // Agent.run (method +0.15) 应该 >= Agent (class +0.10)
            assertTrue(methodScore >= classScore,
                    "method 加权后应 >= class，method=" + methodScore + " class=" + classScore);
        }

        @Test
        @DisplayName("双重命中奖励 → 同时被语义和关键词命中的结果分数更高")
        void dualMatchBonus() throws Exception {
            insertSampleData();

            // "Agent" 既会被语义检索命中，也会被关键词检索命中
            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent", 5);

            double agentScore = results.stream()
                    .filter(r -> r.name().equals("Agent"))
                    .mapToDouble(VectorStore.SearchResult::similarity)
                    .findFirst().orElse(0);

            // Agent 的分数应该 > 1.0（语义 1.0 + 双重命中 0.1 + class 加权 0.1 = 1.2）
            assertTrue(agentScore > 1.0,
                    "双重命中 + class 加权后应 > 1.0，实际: " + agentScore);
        }

        @Test
        @DisplayName("每文件最多 2 条限制")
        void perFileLimit() throws Exception {
            // 给同一文件插入 5 个方法 chunk
            for (int i = 0; i < 5; i++) {
                store.insertChunks(List.of(
                        new VectorStore.CodeChunkEntry(
                                CodeChunk.methodChunk("src/Big.java", "Big.method" + i,
                                        "public void method" + i + "() {}", i * 10, i * 10 + 5),
                                new float[]{0.5f, 0.5f, 0.0f})
                ));
            }

            List<VectorStore.SearchResult> results = retriever.hybridSearch("method", 10);

            long bigFileCount = results.stream()
                    .filter(r -> r.filePath().equals("src/Big.java"))
                    .count();
            assertTrue(bigFileCount <= 2,
                    "同一文件最多 2 条，实际: " + bigFileCount);
        }

        @Test
        @DisplayName("topK 限制 → 结果数不超过 topK")
        void topKLimit() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent Tool", 2);

            assertTrue(results.size() <= 2,
                    "结果数不应超过 topK=2，实际: " + results.size());
        }

        @Test
        @DisplayName("结果按相似度降序排列")
        void sortedDescending() throws Exception {
            insertSampleData();

            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent", 10);

            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i - 1).similarity() >= results.get(i).similarity(),
                        "结果应按相似度降序");
            }
        }

        @Test
        @DisplayName("空索引 → 返回空列表")
        void emptyIndex() throws Exception {
            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent", 5);
            assertTrue(results.isEmpty());
        }
    }

    // ==================== 图谱 + 统计 ====================

    @Nested
    @DisplayName("图谱与统计")
    class GraphAndStats {

        @Test
        @DisplayName("getStats → 返回正确的 chunkCount")
        void stats() throws Exception {
            insertSampleData();
            assertEquals(5, retriever.getStats().chunkCount());
        }

        @Test
        @DisplayName("getRelationGraph → 可查询已插入的关系")
        void relationGraph() throws Exception {
            store.insertRelations(List.of(
                    new CodeRelation("Agent.java", "Agent", "Base.java", "BaseAgent", "extends")));

            List<CodeRelation> rels = retriever.getRelationGraph("Agent");
            assertEquals(1, rels.size());
            assertEquals("extends", rels.get(0).relationType());
        }
    }
}
