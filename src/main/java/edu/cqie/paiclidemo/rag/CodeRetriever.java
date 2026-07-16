package edu.cqie.paiclidemo.rag;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

/**
 * 代码检索器 —— 混合检索的统一入口。
 * <p>
 * <b>核心算法：语义 + 关键词混合检索（Hybrid Search）</b>
 * <pre>
 *  用户查询: "Agent 的 run 方法是怎么实现的"
 *       │
 *       ├─① 语义检索（理解意图）
 *       │   EmbeddingClient.embed(query) → float[] 向量
 *       │   VectorStore.search(vec, topK×2) → 余弦相似度排序
 *       │   → 语义相近的代码块（可能包含 "Agent.execute" 等）
 *       │
 *       ├─② 关键词检索（精确匹配）
 *       │   RagQueryTokenizer.tokenize(query) → {"Agent", "run", "方法"}
 *       │   每个关键词 → VectorStore.searchByKeyword(kw)
 *       │   → 名字/内容中包含这些关键词的代码块
 *       │
 *       └─③ 合并 + 加权排序
 *           ├─ 去重（filePath + name 为 key）
 *           ├─ 双重命中奖励 +0.1（同时被语义和关键词检索到）
 *           ├─ 关键词加权：name命中+0.3, file命中+0.1, content命中+0.1
 *           ├─ 类型加权：method+0.15, class+0.10
 *           ├─ 降序排序
 *           └─ 每文件最多 2 条，总共最多 topK 条
 * </pre>
 * <p>
 * <b>为什么混合检索比纯语义好？</b><br>
 * 纯语义检索容易"漂移" —— 用户问 "Agent.run"，模型可能返回 "PlanExecuteAgent.run"
 * 甚至 "main 方法"，因为它们语义接近。关键词检索能精确锚定 "Agent" 和 "run"，
 * 两者互补，大幅提升检索精度。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class CodeRetriever implements AutoCloseable {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    // ==================== 构造方法 ====================

    /**
     * 根据项目路径创建检索器（使用默认 EmbeddingClient）。
     */
    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(
                Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 根据项目路径 + 自定义 EmbeddingClient 创建检索器。
     */
    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(
                Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 包级可见构造器：直接传入 VectorStore（测试用，避免依赖文件系统）。
     */
    CodeRetriever(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    // ==================== 语义检索 ====================

    /**
     * 语义检索：用自然语言查询最相关的代码块。
     * <p>
     * 将查询文本向量化后，在 VectorStore 中计算余弦相似度，返回 TopK。
     *
     * @param query 自然语言查询
     * @param topK  返回数量
     */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    // ==================== 关键词检索 ====================

    /**
     * 关键词检索：按类名/方法名/内容精确匹配。
     */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    // ==================== 混合检索 ====================

    /**
     * 混合检索：同时进行语义检索和关键词检索，合并去重 + 加权排序。
     * <p>
     * 这是 RAG 系统对外暴露的核心方法，被 {@code /search} 命令和
     * {@code search_code} Agent 工具调用。
     *
     * @param query 自然语言查询
     * @param topK  最终返回的最大结果数
     * @return 按加权相似度降序排列的检索结果
     */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        Map<String, VectorStore.SearchResult> merged = new LinkedHashMap<>();
        Set<String> dualMatchBonused = new HashSet<>();

        // ── ① 语义检索（取 topK×2 候选，给后续合并留余量）──
        int semanticLimit = Math.max(topK * 2, 10);
        for (VectorStore.SearchResult result : semanticSearch(query, semanticLimit)) {
            mergeResult(merged, result, dualMatchBonused);
        }

        // ── ② 关键词检索（每个提取出的关键词分别搜索）──
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (VectorStore.SearchResult result : keywordSearch(keyword)) {
                mergeResult(merged, boostKeywordMatch(result, keyword), dualMatchBonused);
            }
        }

        // ── ③ 类型加权：method > class > file ──
        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (VectorStore.SearchResult r : merged.values()) {
            double typeBoost = switch (r.chunkType()) {
                case "method" -> 0.15;
                case "class" -> 0.10;
                default -> 0.0;
            };
            ranked.add(typeBoost == 0.0 ? r : new VectorStore.SearchResult(
                    r.filePath(), r.chunkType(), r.name(), r.content(),
                    r.similarity() + typeBoost));
        }

        // ── ④ 排序 + 限流 ──
        ranked.sort(Comparator.comparingDouble(
                VectorStore.SearchResult::similarity).reversed());
        return limitPerFile(ranked, topK, 2);
    }

    // ==================== 合并逻辑 ====================

    /**
     * 将候选结果合并到 map 中。
     * <p>
     * key = filePath + "#" + name（唯一标识一个代码块）。
     * 如果同一条结果被语义和关键词两种检索都命中，给予 +0.1 双重命中奖励（只给一次）。
     */
    private void mergeResult(Map<String, VectorStore.SearchResult> merged,
                             VectorStore.SearchResult candidate,
                             Set<String> dualMatchBonused) {
        String key = candidate.filePath() + "#" + candidate.name();
        VectorStore.SearchResult existing = merged.get(key);
        if (existing == null) {
            merged.put(key, candidate);
        } else {
            double best = Math.max(existing.similarity(), candidate.similarity());
            // 双重命中奖励只给一次，不重复叠加
            if (!dualMatchBonused.contains(key)) {
                best += 0.1;
                dualMatchBonused.add(key);
            }
            merged.put(key, new VectorStore.SearchResult(
                    candidate.filePath(), candidate.chunkType(),
                    candidate.name(), candidate.content(), best));
        }
    }

    /**
     * 对关键词匹配结果做加权增强。
     * <p>
     * 加分幅度控制在 0.1~0.5，确保关键词结果（基准 0.3）最高到 ~0.8，
     * 不会压过语义高分结果（最高 1.0），两者自然融合。
     * <ul>
     *   <li>类名/方法名命中 → +0.3（最强信号）</li>
     *   <li>文件路径命中 → +0.1</li>
     *   <li>代码内容命中 → +0.1</li>
     * </ul>
     */
    private VectorStore.SearchResult boostKeywordMatch(
            VectorStore.SearchResult result, String keyword) {
        String nameLower = result.name().toLowerCase();
        String fileLower = result.filePath().toLowerCase();
        String contentLower = result.content().toLowerCase();
        String keywordLower = keyword.toLowerCase();

        double bonus = 0.0;
        if (nameLower.contains(keywordLower)) bonus += 0.3;
        if (fileLower.contains(keywordLower)) bonus += 0.1;
        if (contentLower.contains(keywordLower)) bonus += 0.1;

        return new VectorStore.SearchResult(
                result.filePath(), result.chunkType(),
                result.name(), result.content(),
                result.similarity() + bonus);
    }

    /**
     * 同一文件最多保留 maxPerFile 个结果，总数不超过 topK。
     * <p>
     * 防止某个大文件（如 Agent.java 有 10 个方法）霸占所有结果位，
     * 保证检索结果的多样性。
     */
    private List<VectorStore.SearchResult> limitPerFile(
            List<VectorStore.SearchResult> sorted, int topK, int maxPerFile) {
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (VectorStore.SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) break;
            }
        }
        return result;
    }

    // ==================== 图谱 + 统计 ====================

    /**
     * 图谱检索：查询指定类/方法的关系图谱。
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计。
     */
    public VectorStore.IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
