package edu.cqie.paiclidemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.rag.CodeRetriever;
import edu.cqie.paiclidemo.rag.SearchResultFormatter;
import edu.cqie.paiclidemo.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 —— 管理所有可供 LLM 调用的外部工具。
 * <p>
 * 职责：
 * 1. 注册工具（名称 + 描述 + JSON Schema 参数 + 执行逻辑）
 * 2. 将工具列表转换为 LLM API 所需的格式
 * 3. 根据 LLM 返回的 tool_call 查找并执行对应工具
 * <p>
 *
 * @author Fonzo
 * @date 2026/07/03
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 已注册的工具表：name → Tool */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 当前索引的项目路径（供 RAG search_code 工具使用）。
     * <p>
     * 当用户执行 /index 命令后，Main 会调用 setProjectPath() 同步路径，
     * 确保 search_code 工具始终在正确的项目目录下检索。
     */
    private volatile String projectPath = ".";

    // ==================== 工具注册 ====================

    /**
     * 注册一个工具。
     *
     * @param name        工具名称（LLM 通过此名称调用）
     * @param description 工具描述（告诉 LLM 这个工具能做什么）
     * @param parameters  JSON Schema 格式的参数定义
     * @param executor    实际执行逻辑
     */
    public void register(String name, String description, JsonNode parameters, ToolExecutor executor) {
        tools.put(name, new Tool(name, description, parameters, executor));
        log.info("注册工具: {}", name);
    }

    /** 是否已注册指定工具 */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /** 设置当前项目路径（/index 命令执行后同步） */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        log.info("RAG 项目路径已同步: {}", projectPath);
    }

    /** 获取当前项目路径 */
    public String getProjectPath() {
        return projectPath;
    }

    // ==================== RAG 工具注册 ====================

    /**
     * 注册 RAG 相关工具（search_code）。
     * <p>
     * 在 createToolRegistry() 中调用，让 Agent 可以调用 search_code 工具
     * 对已索引的代码库进行语义检索。
     */
    public void registerRagTools() {
        register(
                "search_code",
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块。"
                        + "精确符号/字符串定位请优先用其他工具；"
                        + "默认 top_k=5，可显式指定（上限 30）",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如 'Agent 的 run 方法怎么实现'"),
                        new Param("top_k", "string", "返回结果数量，默认 5，最大 30", false)
                ),
                args -> {
                    String query = args.get("query");
                    if (query == null || query.isBlank()) {
                        return "错误：请提供查询内容（query 参数）";
                    }
                    int topK = 5;
                    String topKStr = args.get("top_k");
                    if (topKStr != null && !topKStr.isBlank()) {
                        try {
                            topK = Math.min(Integer.parseInt(topKStr), 30);
                        } catch (NumberFormatException ignored) {
                            // 解析失败则使用默认值 5
                        }
                    }
                    try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
                        VectorStore.IndexStats stats = retriever.getStats();
                        if (stats.chunkCount() == 0) {
                            return "代码库尚未索引，请先执行 /index 命令建立索引。";
                        }
                        var results = retriever.hybridSearch(query, topK);
                        if (results.isEmpty()) {
                            return "未找到与 \"" + query + "\" 相关的代码块。";
                        }
                        return SearchResultFormatter.formatForTool(query, results);
                    } catch (Exception e) {
                        log.error("search_code 执行失败", e);
                        return "检索出错: " + e.getMessage();
                    }
                }
        );
    }

    // ==================== 工具执行 ====================

    /**
     * 执行工具调用。
     * <p>
     * 流程：
     * 1. 根据 toolCall 中的 name 查找已注册工具
     * 2. 将 JSON 格式的参数解析为 Map
     * 3. 调用 executor 执行具体逻辑
     * 4. 返回执行结果字符串（会作为 tool 消息回传给 LLM）
     */
    public String executeTool(LlmClient.ToolCall toolCall) {
        String name = toolCall.function().name();
        String argsJson = toolCall.function().arguments();

        Tool tool = tools.get(name);
        if (tool == null) {
            log.warn("未找到工具: {}", name);
            return "错误：未找到工具 '" + name + "'";
        }

        try {
            // 将 JSON 字符串解析为 Map<String, String>
            Map<String, String> args = parseArguments(argsJson);
            log.info("执行工具: {} | 参数: {}", name, args);

            long start = System.currentTimeMillis();
            String result = tool.executor().execute(args);
            long elapsed = System.currentTimeMillis() - start;

            log.info("工具 {} 执行完成 | 耗时 {}ms | 结果长度 {} chars", name, elapsed, result.length());
            return result;

        } catch (Exception e) {
            log.error("工具 {} 执行异常", name, e);
            return "工具执行出错: " + e.getMessage();
        }
    }

    /** 将 JSON 参数字符串解析为 Map */
    private Map<String, String> parseArguments(String argsJson) throws Exception {
        if (argsJson == null || argsJson.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        JsonNode node = mapper.readTree(argsJson);
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }

    // ==================== 转换为 LLM API 格式 ====================

    /**
     * 将所有已注册工具转换为 LlmClient.Tool 列表，
     * 用于发送给 LLM API，让模型知道有哪些工具可用。
     */
    public List<LlmClient.Tool> getToolDefinitions() {
        List<LlmClient.Tool> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            defs.add(new LlmClient.Tool(tool.name(), tool.description(), tool.parameters()));
        }
        return defs;
    }

    // ==================== JSON Schema 构建辅助 ====================

    /**
     * 便捷方法：构建 JSON Schema 格式的 tool parameters。
     * <p>
     * 生成的格式示例：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "expression": { "type": "string", "description": "数学表达式" }
     *   },
     *   "required": ["expression"]
     * }
     * </pre>
     */
    public static JsonNode createParameters(Param... params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        for (Param p : params) {
            ObjectNode prop = properties.putObject(p.name);
            prop.put("type", p.type);
            prop.put("description", p.description);
            if (p.required) {
                required.add(p.name);
            }
        }
        return root;
    }

    /** 参数定义记录 */
    public record Param(String name, String type, String description, boolean required) {
        public Param(String name, String type, String description) {
            this(name, type, description, true);
        }
    }

    // ==================== 内部类型定义 ====================

    /** 内部工具记录：包含名称、描述、参数定义和执行器 */
    public record Tool(
            String name,
            String description,
            JsonNode parameters,
            ToolExecutor executor
    ) {}

    /** 工具执行器函数式接口 */
    public interface ToolExecutor {
        /**
         * 执行工具逻辑。
         *
         * @param args 参数 Map（key=参数名, value=参数值）
         * @return 执行结果字符串（会作为 tool 消息回传给 LLM）
         */
        String execute(Map<String, String> args);
    }
}
