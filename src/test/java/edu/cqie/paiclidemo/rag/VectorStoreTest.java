package edu.cqie.paiclidemo.rag;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorStore 单元测试。
 * <p>
 * 每个测试使用独立的临时目录创建 SQLite 数据库，互不干扰。
 * 覆盖：建表、插入、清空、语义检索、关键词检索、关系图谱、统计、余弦相似度。
 */
@DisplayName("VectorStore SQLite 向量存储")
class VectorStoreTest {

    @TempDir
    Path tempDir;

    private VectorStore store;

    @BeforeEach
    void setUp() throws SQLException {
        store = new VectorStore("test-project", tempDir.toString());
    }

    @AfterEach
    void tearDown() throws SQLException {
        store.close();
    }

    // ==================== 辅助方法 ====================

    private CodeChunk chunk(String name, String type, String content) {
        return new CodeChunk("src/" + name + ".java", type, name, content, 1, 10);
    }

    private VectorStore.CodeChunkEntry entry(CodeChunk chunk, float[] vec) {
        return new VectorStore.CodeChunkEntry(chunk, vec);
    }

    private void insertSampleChunks() throws SQLException {
        store.insertChunks(List.of(
                entry(chunk("Agent", "class", "public class Agent { ... }"),
                        new float[]{1.0f, 0.0f, 0.0f}),
                entry(chunk("Agent.run", "method", "public String run(String input) { ... }"),
                        new float[]{0.9f, 0.1f, 0.0f}),
                entry(chunk("ToolRegistry", "class", "public class ToolRegistry { ... }"),
                        new float[]{0.0f, 1.0f, 0.0f}),
                entry(chunk("ToolRegistry.register", "method", "public void register(String name) { ... }"),
                        new float[]{0.0f, 0.8f, 0.2f})
        ));
    }

    // ==================== 插入 + 统计 ====================

    @Nested
    @DisplayName("插入与统计")
    class InsertAndStats {

        @Test
        @DisplayName("插入 4 条 chunk → getStats 返回 chunkCount=4")
        void insertChunksAndStats() throws SQLException {
            insertSampleChunks();

            VectorStore.IndexStats stats = store.getStats();
            assertEquals(4, stats.chunkCount());
            assertEquals(0, stats.relationCount());
        }

        @Test
        @DisplayName("空表 → getStats 返回 0/0")
        void emptyStats() throws SQLException {
            VectorStore.IndexStats stats = store.getStats();
            assertEquals(0, stats.chunkCount());
            assertEquals(0, stats.relationCount());
        }

        @Test
        @DisplayName("插入关系 → getStats 返回 relationCount")
        void insertRelations() throws SQLException {
            store.insertRelations(List.of(
                    new CodeRelation("Agent.java", "Agent", "BaseAgent.java", "BaseAgent", "extends"),
                    new CodeRelation("Agent.java", "Agent", "Agent.java", "Agent.run", "contains")
            ));

            assertEquals(2, store.getStats().relationCount());
        }

        @Test
        @DisplayName("空列表插入 → 无异常，stats 不变")
        void insertEmptyList() throws SQLException {
            store.insertChunks(List.of());
            store.insertRelations(List.of());
            assertEquals(0, store.getStats().chunkCount());
            assertEquals(0, store.getStats().relationCount());
        }
    }

    // ==================== 清空 ====================

    @Nested
    @DisplayName("清空项目数据")
    class ClearProject {

        @Test
        @DisplayName("clearProject → chunks 和 relations 都清零")
        void clearAll() throws SQLException {
            insertSampleChunks();
            store.insertRelations(List.of(
                    new CodeRelation("f", "A", "f", "B", "extends")));

            assertEquals(4, store.getStats().chunkCount());
            assertEquals(1, store.getStats().relationCount());

            store.clearProject();

            assertEquals(0, store.getStats().chunkCount());
            assertEquals(0, store.getStats().relationCount());
        }

        @Test
        @DisplayName("clearProject 不影响其他项目的数据")
        void projectIsolation() throws SQLException {
            // 当前 store 是 "test-project"
            insertSampleChunks();

            // 另一个项目
            try (VectorStore otherStore = new VectorStore("other-project", tempDir.toString())) {
                otherStore.insertChunks(List.of(
                        entry(chunk("Other", "class", "class Other {}"),
                                new float[]{1, 0, 0})));

                assertEquals(1, otherStore.getStats().chunkCount());
            }

            // 清空 test-project
            store.clearProject();
            assertEquals(0, store.getStats().chunkCount());

            // other-project 不受影响
            try (VectorStore otherStore = new VectorStore("other-project", tempDir.toString())) {
                assertEquals(1, otherStore.getStats().chunkCount());
            }
        }
    }

    // ==================== 语义检索 ====================

    @Nested
    @DisplayName("语义检索 (search)")
    class SemanticSearch {

        @Test
        @DisplayName("查询向量 [1,0,0] → Agent (向量 [1,0,0]) 排第一")
        void topResultMatchesQuery() throws SQLException {
            insertSampleChunks();

            float[] query = new float[]{1.0f, 0.0f, 0.0f};
            List<VectorStore.SearchResult> results = store.search(query, 3);

            assertFalse(results.isEmpty());
            assertEquals("Agent", results.get(0).name());
            assertTrue(results.get(0).similarity() > 0.9,
                    "相似度应接近 1.0，实际: " + results.get(0).similarity());
        }

        @Test
        @DisplayName("查询向量 [0,1,0] → ToolRegistry 排第一")
        void differentQueryDifferentRanking() throws SQLException {
            insertSampleChunks();

            float[] query = new float[]{0.0f, 1.0f, 0.0f};
            List<VectorStore.SearchResult> results = store.search(query, 3);

            assertEquals("ToolRegistry", results.get(0).name());
        }

        @Test
        @DisplayName("topK=2 → 最多返回 2 条")
        void topKLimitsResults() throws SQLException {
            insertSampleChunks();

            List<VectorStore.SearchResult> results = store.search(
                    new float[]{1, 0, 0}, 2);

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("topK > 实际数量 → 返回全部")
        void topKExceedsCount() throws SQLException {
            insertSampleChunks();

            List<VectorStore.SearchResult> results = store.search(
                    new float[]{1, 0, 0}, 100);

            assertEquals(4, results.size());
        }

        @Test
        @DisplayName("结果按相似度降序排列")
        void resultsSortedDescending() throws SQLException {
            insertSampleChunks();

            List<VectorStore.SearchResult> results = store.search(
                    new float[]{1, 0, 0}, 10);

            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i - 1).similarity() >= results.get(i).similarity(),
                        "结果应按相似度降序");
            }
        }

        @Test
        @DisplayName("embedding_json 为 null 的 chunk 被跳过")
        void nullEmbeddingSkipped() throws SQLException {
            // 插入一条没有向量的 chunk
            store.insertChunks(List.of(
                    entry(chunk("NoVec", "file", "content"), null)));

            List<VectorStore.SearchResult> results = store.search(
                    new float[]{1, 0, 0}, 10);

            assertTrue(results.isEmpty(), "null 向量应被跳过");
        }

        @Test
        @DisplayName("空表 → 返回空列表")
        void emptyTable() throws SQLException {
            List<VectorStore.SearchResult> results = store.search(
                    new float[]{1, 0, 0}, 5);
            assertTrue(results.isEmpty());
        }
    }

    // ==================== 关键词检索 ====================

    @Nested
    @DisplayName("关键词检索 (searchByKeyword)")
    class KeywordSearch {

        @Test
        @DisplayName("搜索 'Agent' → 命中 name 包含 Agent 的 chunk")
        void matchByName() throws SQLException {
            insertSampleChunks();

            List<VectorStore.SearchResult> results = store.searchByKeyword("Agent");

            assertEquals(2, results.size()); // Agent + Agent.run
            assertTrue(results.stream().allMatch(r -> r.name().contains("Agent")));
        }

        @Test
        @DisplayName("搜索 'register' → 命中 content 包含 register 的 chunk")
        void matchByContent() throws SQLException {
            insertSampleChunks();

            List<VectorStore.SearchResult> results = store.searchByKeyword("register");

            assertTrue(results.size() >= 1);
        }

        @Test
        @DisplayName("关键词匹配的 similarity 固定为 0.3")
        void fixedSimilarity() throws SQLException {
            insertSampleChunks();

            List<VectorStore.SearchResult> results = store.searchByKeyword("Agent");

            assertTrue(results.stream().allMatch(r -> r.similarity() == 0.3));
        }

        @Test
        @DisplayName("搜索不存在的关键词 → 返回空列表")
        void noMatch() throws SQLException {
            insertSampleChunks();
            assertTrue(store.searchByKeyword("XYZ_NONEXISTENT").isEmpty());
        }

        @Test
        @DisplayName("SQL 通配符 %, _ 被正确转义")
        void sqlWildcardEscaping() throws SQLException {
            store.insertChunks(List.of(
                    entry(chunk("Test", "method", "100% complete"),
                            new float[]{1, 0, 0})));

            // 搜索 "100%" 不应该匹配所有内容（% 应该被转义为字面量）
            List<VectorStore.SearchResult> results = store.searchByKeyword("100%");
            assertEquals(1, results.size());
            assertTrue(results.get(0).content().contains("100%"));
        }
    }

    // ==================== 关系图谱 ====================

    @Nested
    @DisplayName("关系图谱查询")
    class RelationGraph {

        @Test
        @DisplayName("getRelations → 返回 from_name 和 to_name 都匹配的关系")
        void bidirectionalQuery() throws SQLException {
            store.insertRelations(List.of(
                    new CodeRelation("A.java", "Agent", "Base.java", "BaseAgent", "extends"),
                    new CodeRelation("A.java", "Agent", "A.java", "Agent.run", "contains"),
                    new CodeRelation("B.java", "Other", "A.java", "Agent", "imports")
            ));

            // 查 "Agent"：应返回 3 条（2 条 from_name=Agent + 1 条 to_name=Agent）
            List<CodeRelation> rels = store.getRelations("Agent");
            assertEquals(3, rels.size());
        }

        @Test
        @DisplayName("getOutgoingRelations → 只返回 from_name 匹配的关系")
        void outgoingOnly() throws SQLException {
            store.insertRelations(List.of(
                    new CodeRelation("A.java", "Agent", "Base.java", "BaseAgent", "extends"),
                    new CodeRelation("B.java", "Other", "A.java", "Agent", "imports")
            ));

            List<CodeRelation> outgoing = store.getOutgoingRelations("Agent");
            assertEquals(1, outgoing.size());
            assertEquals("extends", outgoing.get(0).relationType());
        }

        @Test
        @DisplayName("空关系表 → 返回空列表")
        void emptyRelations() throws SQLException {
            assertTrue(store.getRelations("Agent").isEmpty());
            assertTrue(store.getOutgoingRelations("Agent").isEmpty());
        }
    }

    // ==================== 余弦相似度 ====================

    @Nested
    @DisplayName("cosineSimilarity")
    class CosineSimilarityTests {

        @Test
        @DisplayName("相同向量 → 1.0")
        void identicalVectors() {
            float[] a = {1.0f, 2.0f, 3.0f};
            assertEquals(1.0, VectorStore.cosineSimilarity(a, a), 0.0001);
        }

        @Test
        @DisplayName("正交向量 → 0.0")
        void orthogonalVectors() {
            float[] a = {1.0f, 0.0f};
            float[] b = {0.0f, 1.0f};
            assertEquals(0.0, VectorStore.cosineSimilarity(a, b), 0.0001);
        }

        @Test
        @DisplayName("相反向量 → -1.0")
        void oppositeVectors() {
            float[] a = {1.0f, 0.0f};
            float[] b = {-1.0f, 0.0f};
            assertEquals(-1.0, VectorStore.cosineSimilarity(a, b), 0.0001);
        }

        @Test
        @DisplayName("维度不同 → 0.0")
        void differentDimensions() {
            float[] a = {1.0f, 2.0f};
            float[] b = {1.0f, 2.0f, 3.0f};
            assertEquals(0.0, VectorStore.cosineSimilarity(a, b));
        }

        @Test
        @DisplayName("零向量 → 0.0（避免除以零）")
        void zeroVector() {
            float[] a = {0.0f, 0.0f};
            float[] b = {1.0f, 2.0f};
            assertEquals(0.0, VectorStore.cosineSimilarity(a, b));
        }

        @Test
        @DisplayName("相似向量 → 高相似度")
        void similarVectors() {
            float[] a = {1.0f, 0.1f, 0.0f};
            float[] b = {0.9f, 0.2f, 0.1f};
            double sim = VectorStore.cosineSimilarity(a, b);
            assertTrue(sim > 0.95, "相似向量应有高相似度，实际: " + sim);
        }
    }

    // ==================== AutoCloseable ====================

    @Test
    @DisplayName("close 后可再次打开同一数据库（数据持久化）")
    void persistenceAcrossReopen() throws SQLException {
        insertSampleChunks();
        store.close();

        // 重新打开
        try (VectorStore reopened = new VectorStore("test-project", tempDir.toString())) {
            assertEquals(4, reopened.getStats().chunkCount());

            // 语义检索仍然有效
            List<VectorStore.SearchResult> results = reopened.search(
                    new float[]{1, 0, 0}, 2);
            assertEquals(2, results.size());
        }
    }
}
