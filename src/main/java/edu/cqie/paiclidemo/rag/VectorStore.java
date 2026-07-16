package edu.cqie.paiclidemo.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 向量存储 + 代码关系图谱 —— RAG 系统的持久化层。
 * <p>
 * <b>核心设计</b>：向量以 JSON 数组（如 {@code [0.12, -0.34, ...]}）存储在 SQLite 的 TEXT 字段中，
 * 检索时全量加载到内存计算余弦相似度。对于代码库规模（通常几百到几千个 chunk），
 * 这个方案足够快；规模再大可以换 FAISS / pgvector 等专用向量数据库。
 * <p>
 * <b>两张表</b>：
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ code_chunks                                                     │
 * │ ┌──────────┬──────────┬────────┬──────┬─────────┬────────────┐ │
 * │ │ id (PK)  │ project  │ file   │ type │ name    │ content    │ │
 * │ │ AUTOINC  │ _path    │ _path  │      │         │            │ │
 * │ ├──────────┼──────────┼────────┼──────┼─────────┼────────────┤ │
 * │ │ embedding_json (TEXT)  ← float[] 序列化为 JSON 数组           │ │
 * │ │ created_at (TIMESTAMP)                                       │ │
 * │ └──────────────────────────────────────────────────────────────┘ │
 * │                                                                 │
 * │ code_relations                                                  │
 * │ ┌──────────┬──────────┬──────────┬──────────┬────────────────┐ │
 * │ │ id (PK)  │ project  │ from_    │ to_      │ relation_type  │ │
 * │ │ AUTOINC  │ _path    │ name     │ name     │ extends/impl.. │ │
 * │ └──────────┴──────────┴──────────┴──────────┴────────────────┘ │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>项目隔离</b>：所有查询都带 {@code project_path} 条件，
 * 同一个 SQLite 文件可以同时存储多个项目的索引数据。
 * <p>
 * <b>数据库位置</b>：{@code src/main/resources/db/codebase.db}
 * （可通过 JVM 系统属性 {@code paicli-demo.rag.dir} 自定义）
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class VectorStore implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection connection;
    private final String projectPath;

    // ==================== 构造方法 ====================

    /**
     * 创建 VectorStore，使用默认的数据库目录。
     * <p>
     * 默认路径：{@code src/main/resources/db/codebase.db}
     *
     * @param projectPath 项目路径（用于数据隔离）
     */
    public VectorStore(String projectPath) throws SQLException {
        this(projectPath, defaultDbDir());
    }

    /**
     * 创建 VectorStore，指定数据库目录（方便测试）。
     *
     * @param projectPath 项目路径
     * @param dbDir       SQLite 数据库文件所在目录
     */
    VectorStore(String projectPath, String dbDir) throws SQLException {
        this.projectPath = projectPath;
        java.io.File dir = new java.io.File(dbDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String dbPath = dir.getAbsolutePath() + "/codebase.db";
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
    }

    private static String defaultDbDir() {
        return System.getProperty("paicli-demo.rag.dir",
                "src/main/resources/db");
    }

    // ==================== 建表 + 索引 ====================

    /**
     * 初始化数据库表结构和索引。
     * <p>
     * 使用 {@code CREATE TABLE IF NOT EXISTS}，多次调用安全无副作用。
     * 索引用于加速按 project_path / file_path / chunk_type 的过滤查询。
     */
    private void initTables() throws SQLException {
        String createChunks = """
                CREATE TABLE IF NOT EXISTS code_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    chunk_type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding_json TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

        String createRelations = """
                CREATE TABLE IF NOT EXISTS code_relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    from_file TEXT NOT NULL,
                    from_name TEXT NOT NULL,
                    to_file TEXT,
                    to_name TEXT,
                    relation_type TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

        // 索引：加速 WHERE project_path = ? 等过滤条件
        String createIdxProject = "CREATE INDEX IF NOT EXISTS idx_project ON code_chunks(project_path)";
        String createIdxFile = "CREATE INDEX IF NOT EXISTS idx_file ON code_chunks(file_path)";
        String createIdxType = "CREATE INDEX IF NOT EXISTS idx_type ON code_chunks(chunk_type)";
        String createIdxRelProject = "CREATE INDEX IF NOT EXISTS idx_rel_project ON code_relations(project_path)";
        String createIdxRelFrom = "CREATE INDEX IF NOT EXISTS idx_rel_from ON code_relations(from_name)";
        String createIdxRelTo = "CREATE INDEX IF NOT EXISTS idx_rel_to ON code_relations(to_name)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createChunks);
            stmt.execute(createRelations);
            stmt.execute(createIdxProject);
            stmt.execute(createIdxFile);
            stmt.execute(createIdxType);
            stmt.execute(createIdxRelProject);
            stmt.execute(createIdxRelFrom);
            stmt.execute(createIdxRelTo);
        }
    }

    // ==================== 清空 ====================

    /**
     * 清空当前项目的所有索引数据（代码块 + 关系图谱）。
     * <p>
     * 索引重建时先调 clearProject() 再 insert，实现全量替换。
     */
    public void clearProject() throws SQLException {
        try (PreparedStatement ps1 = connection.prepareStatement(
                "DELETE FROM code_chunks WHERE project_path = ?");
             PreparedStatement ps2 = connection.prepareStatement(
                "DELETE FROM code_relations WHERE project_path = ?")) {
            ps1.setString(1, projectPath);
            ps2.setString(1, projectPath);
            ps1.executeUpdate();
            ps2.executeUpdate();
        }
    }

    // ==================== 批量插入 ====================

    /**
     * 批量插入代码块（事务保护）。
     * <p>
     * 使用 {@code addBatch + executeBatch} 批量提交，
     * 关闭 autoCommit 包裹在事务中，任何一条失败都整体回滚。
     *
     * @param entries 带向量的代码块列表
     */
    public void insertChunks(List<CodeChunkEntry> entries) throws SQLException {
        String sql = """
                INSERT INTO code_chunks (project_path, file_path, chunk_type, name, content, embedding_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeChunkEntry entry : entries) {
                ps.setString(1, projectPath);
                ps.setString(2, entry.chunk().filePath());
                ps.setString(3, entry.chunk().chunkType());
                ps.setString(4, entry.chunk().name());
                ps.setString(5, entry.chunk().content());
                if (entry.embedding() != null) {
                    ps.setString(6, embeddingToJson(entry.embedding()));
                } else {
                    ps.setNull(6, Types.VARCHAR);
                }
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * 批量插入代码关系（事务保护）。
     */
    public void insertRelations(List<CodeRelation> relations) throws SQLException {
        String sql = """
                INSERT INTO code_relations (project_path, from_file, from_name, to_file, to_name, relation_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeRelation rel : relations) {
                ps.setString(1, projectPath);
                ps.setString(2, rel.fromFile());
                ps.setString(3, rel.fromName());
                ps.setString(4, rel.toFile());
                ps.setString(5, rel.toName());
                ps.setString(6, rel.relationType());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    // ==================== 语义检索 ====================

    /**
     * 语义检索：根据查询向量，返回最相似的 TopK 代码块。
     * <p>
     * <b>实现原理</b>：
     * <ol>
     *   <li>从 SQLite 加载当前项目的全部 code_chunks</li>
     *   <li>对每条记录，将 JSON 反序列化为 float[]</li>
     *   <li>计算查询向量与存储向量的余弦相似度</li>
     *   <li>按相似度降序排序，取前 topK 条</li>
     * </ol>
     * <p>
     * <b>为什么不用 SQLite 扩展做向量检索？</b><br>
     * sqlite-vec 等扩展需要额外安装，不符合 CLI 工具"零配置"的设计理念。
     * 全量加载到内存算余弦，对于几百~几千个 chunk 的代码库规模，延迟在毫秒级。
     *
     * @param queryEmbedding 查询文本的向量
     * @param topK           返回的最大结果数
     * @return 按相似度降序排列的检索结果
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK) throws SQLException {
        String sql = "SELECT file_path, chunk_type, name, content, embedding_json "
                + "FROM code_chunks WHERE project_path = ?";
        List<SearchResult> candidates = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String embeddingJson = rs.getString("embedding_json");
                    if (embeddingJson == null || embeddingJson.isEmpty()) {
                        continue;
                    }
                    float[] embedding = jsonToEmbedding(embeddingJson);
                    double similarity = cosineSimilarity(queryEmbedding, embedding);
                    candidates.add(new SearchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            similarity
                    ));
                }
            }
        }

        // 按相似度降序排序，取 TopK
        candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return candidates.size() > topK
                ? new ArrayList<>(candidates.subList(0, topK))
                : candidates;
    }

    // ==================== 关键词检索 ====================

    /**
     * 关键词检索：通过 SQL LIKE 精确匹配类名/方法名/代码内容。
     * <p>
     * 与语义检索互补 —— 语义检索擅长理解意图，关键词检索擅长精确匹配名称。
     * 例如搜索 "Agent.run" 时，关键词检索能精确命中，而语义检索可能返回
     * 语义相近但名字不同的方法。
     * <p>
     * 命中的结果统一赋予基准相似度 0.3，后续由 CodeRetriever 做加权调整。
     *
     * @param keyword 搜索关键词
     * @return 匹配的代码块（similarity 固定为 0.3）
     */
    public List<SearchResult> searchByKeyword(String keyword) throws SQLException {
        String sql = """
                SELECT file_path, chunk_type, name, content FROM code_chunks
                WHERE project_path = ? AND (name LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')
                """;
        List<SearchResult> results = new ArrayList<>();
        // 转义 SQL LIKE 通配符，防止用户输入 "%" 或 "_" 干扰查询
        String escaped = keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            0.3  // 关键词匹配基准分
                    ));
                }
            }
        }
        return results;
    }

    // ==================== 关系图谱查询 ====================

    /**
     * 查询与指定名称相关的所有关系（入边 + 出边）。
     */
    public List<CodeRelation> getRelations(String name) throws SQLException {
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type
                FROM code_relations
                WHERE project_path = ? AND (from_name = ? OR to_name = ?)
                """;
        List<CodeRelation> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CodeRelation(
                            rs.getString("from_file"), rs.getString("from_name"),
                            rs.getString("to_file"), rs.getString("to_name"),
                            rs.getString("relation_type")));
                }
            }
        }
        return results;
    }

    /**
     * 查询指定类/方法的所有出向关系（from_name = name）。
     */
    public List<CodeRelation> getOutgoingRelations(String name) throws SQLException {
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type
                FROM code_relations
                WHERE project_path = ? AND from_name = ?
                """;
        List<CodeRelation> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CodeRelation(
                            rs.getString("from_file"), rs.getString("from_name"),
                            rs.getString("to_file"), rs.getString("to_name"),
                            rs.getString("relation_type")));
                }
            }
        }
        return results;
    }

    // ==================== 统计 ====================

    /**
     * 统计当前项目的索引数据量。
     */
    public IndexStats getStats() throws SQLException {
        int chunks = 0, relations = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM code_chunks WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) chunks = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM code_relations WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) relations = rs.getInt(1);
            }
        }
        return new IndexStats(chunks, relations);
    }

    // ==================== 向量工具方法 ====================

    /**
     * 余弦相似度：衡量两个向量在语义空间中的"方向一致程度"。
     * <p>
     * 公式：cos(θ) = (A · B) / (|A| × |B|)
     * <ul>
     *   <li>1.0 = 完全相同方向（最相似）</li>
     *   <li>0.0 = 正交（无关）</li>
     *   <li>-1.0 = 完全相反方向</li>
     * </ul>
     * 维度不一致时返回 0.0（视为无关）。
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String embeddingToJson(float[] embedding) {
        try {
            return mapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("向量序列化失败", e);
        }
    }

    private float[] jsonToEmbedding(String json) {
        try {
            return mapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("向量反序列化失败", e);
        }
    }

    // ==================== AutoCloseable ====================

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // ==================== 内部 Record ====================

    /**
     * 带向量的代码块条目（insertChunks 的输入单元）。
     */
    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}

    /**
     * 检索结果（search / searchByKeyword 的输出单元）。
     *
     * @param similarity 相似度分数：语义检索为余弦相似度 [−1, 1]，关键词检索固定 0.3
     */
    public record SearchResult(String filePath, String chunkType,
                               String name, String content, double similarity) {}

    /**
     * 索引统计。
     */
    public record IndexStats(int chunkCount, int relationCount) {}
}
