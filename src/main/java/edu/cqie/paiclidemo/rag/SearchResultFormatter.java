package edu.cqie.paiclidemo.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索结果格式化器 —— 将原始 SearchResult 转为可读文本。
 * <p>
 * 提供两种输出模式：
 * <pre>
 * ┌────────────────────────────────────────────────────────────────┐
 * │ formatForCli — 给人类看（/search 命令）                         │
 * │                                                                │
 * │  📋 找到 3 个相关代码块:                                        │
 * │                                                                │
 * │  搜索摘要:                                                      │
 * │  - 最相关的入口是 [method:Agent.run]，位于 Agent.java           │
 * │  - 当前结果主要集中在 Agent.java、ToolRegistry.java 这些文件    │
 * │  - 综合参考了 Agent、run 等关键词与语义相似度                    │
 * │                                                                │
 * │  1. [method:Agent.run] (相似度: 1.250) src/Agent.java          │
 * │     public String run(String input) {                          │
 * │         return memory.process(input);                          │
 * │     }                                                          │
 * └────────────────────────────────────────────────────────────────┘
 *
 * ┌────────────────────────────────────────────────────────────────┐
 * │ formatForTool — 给 LLM 看（search_code Agent 工具）             │
 * │                                                                │
 * │  更紧凑的结构，snippet 更长（180 字符 vs CLI 的 120 字符），     │
 * │  让 LLM 获得足够的代码上下文来回答问题。                         │
 * └────────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 不额外调用 LLM，纯字符串操作，毫秒级返回。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public final class SearchResultFormatter {

    private SearchResultFormatter() {} // 工具类，不可实例化

    // ==================== CLI 模式 ====================

    /**
     * 格式化为 CLI 终端展示（/search 命令输出）。
     * <p>
     * 特点：带 emoji 标题、摘要、编号列表、120 字符代码片段。
     *
     * @param query   原始查询文本
     * @param results 检索结果列表
     * @return 格式化后的多行文本
     */
    public static String formatForCli(String query, List<VectorStore.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 找到 ").append(results.size()).append(" 个相关代码块:\n\n");
        sb.append(buildSummary(query, results)).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            sb.append(String.format("%d. [%s:%s] (相似度: %.3f) %s%n",
                    i + 1, r.chunkType(), r.name(),
                    r.similarity(), r.filePath()));
            // 代码片段缩进对齐：换行后加 3 个空格前缀
            sb.append("   ").append(buildSnippet(r.content(), 120)
                    .replace("\n", "\n   "));
            sb.append("\n\n");
        }

        return sb.toString().trim();
    }

    // ==================== Tool 模式 ====================

    /**
     * 格式化为 Agent 工具返回（search_code 工具响应）。
     * <p>
     * 特点：紧凑结构、180 字符代码片段（给 LLM 更多上下文）。
     *
     * @param query   原始查询文本
     * @param results 检索结果列表
     * @return 格式化后的多行文本
     */
    public static String formatForTool(String query, List<VectorStore.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索摘要:\n");
        sb.append(buildSummary(query, results)).append("\n\n");
        sb.append("检索结果:\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            sb.append(String.format("%d. [%s:%s] (相似度: %.3f) %s\n",
                    i + 1, r.chunkType(), r.name(),
                    r.similarity(), r.filePath()));
            sb.append("   ").append(buildSnippet(r.content(), 180)
                    .replace("\n", "\n   ")).append("\n\n");
        }

        return sb.toString().trim();
    }

    // ==================== 摘要构建 ====================

    /**
     * 构建自然语言搜索摘要。
     * <p>
     * 摘要包含三部分：
     * <ol>
     *   <li>最相关的入口（排名第一的结果的 type + name + 文件位置）</li>
     *   <li>结果集中的文件列表（最多 3 个文件名）</li>
     *   <li>排序依据的关键词（从查询中提取的前 3 个 token）</li>
     * </ol>
     */
    static String buildSummary(String query, List<VectorStore.SearchResult> results) {
        if (results.isEmpty()) {
            return "搜索摘要:\n- 没有命中可用代码块。";
        }

        VectorStore.SearchResult top = results.get(0);
        Set<String> fileNames = results.stream()
                .map(r -> Paths.get(r.filePath()).getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> queryTokens = RagQueryTokenizer.tokenize(query).stream()
                .limit(3)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String topFile = shortenPath(top.filePath());
        String relatedFiles = fileNames.stream().limit(3)
                .collect(Collectors.joining("、"));
        String tokenText = queryTokens.isEmpty()
                ? "自然语言语义"
                : String.join("、", queryTokens);

        StringBuilder sb = new StringBuilder("搜索摘要:\n");
        sb.append("- 最相关的入口是 [")
                .append(top.chunkType()).append(":").append(top.name())
                .append("]，位于 ").append(topFile).append("。\n");
        sb.append("- 当前结果主要集中在 ").append(relatedFiles)
                .append(fileNames.size() > 3 ? " 等文件" : " 这些文件")
                .append("。\n");
        sb.append("- 这次排序综合参考了 ").append(tokenText)
                .append(" 等关键词与语义相似度；先看第 1 条，再按文件继续展开最稳妥。");
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * 截取代码片段，超长部分用 "..." 替代。
     *
     * @param content  完整代码内容
     * @param maxChars 最大字符数
     * @return 截断后的片段
     */
    static String buildSnippet(String content, int maxChars) {
        String normalized = content == null ? ""
                : content.trim().replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return "(无内容片段)";
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    /**
     * 缩短文件路径，只保留最后 3 个路径段。
     * <p>
     * 例：{@code /Users/dev/project/src/main/Agent.java} → {@code src/main/Agent.java}
     */
    static String shortenPath(String filePath) {
        Path path = Paths.get(filePath);
        int count = path.getNameCount();
        if (count <= 3) {
            return filePath;
        }
        return path.subpath(count - 3, count).toString();
    }
}
