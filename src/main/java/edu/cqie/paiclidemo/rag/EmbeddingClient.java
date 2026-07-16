package edu.cqie.paiclidemo.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cqie.paiclidemo.config.DotEnv;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端 —— 将文本转换为 float[] 向量表示。
 * <p>
 * <b>核心职责</b>：调用 Embedding 模型 API，把一段文本映射为一个固定维度的浮点数数组。
 * 这个向量在语义空间中编码了文本的含义，后续用于余弦相似度检索。
 * <p>
 * <b>支持的后端</b>（通过适配器模式统一为 {@code float[]} 输出）：
 * <pre>
 * ┌────────────────────────────────────────────────────────────────┐
 * │  Ollama（本地）                                                │
 * │  POST http://localhost:11434/api/embeddings                    │
 * │  请求: { "model": "nomic-embed-text", "prompt": "文本" }       │
 * │  响应: { "embedding": [0.12, -0.34, ...] }                    │
 * │                                                                │
 * │  OpenAI 兼容（zhipu / glm / openai）                           │
 * │  POST https://open.bigmodel.cn/api/paas/v4/embeddings          │
 * │  请求: { "model": "embedding-3", "input": "文本" }             │
 * │  响应: { "data": [{ "embedding": [0.12, -0.34, ...] }] }      │
 * └────────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>配置方式</b>：通过环境变量（优先）或 JVM 系统属性。
 * <ul>
 *   <li>{@code EMBEDDING_PROVIDER} — 后端选择：ollama / zhipu / glm / openai（默认 ollama）</li>
 *   <li>{@code EMBEDDING_MODEL} — 模型名称（默认 nomic-embed-text:latest）</li>
 *   <li>{@code EMBEDDING_BASE_URL} — API 基地址（自动推断默认值）</li>
 *   <li>{@code EMBEDDING_API_KEY} — API 密钥（仅 OpenAI 兼容模式需要）</li>
 * </ul>
 * <p>
 * <b>输入截断</b>：文本超过 2000 字符时自动截断，防止超出 Embedding 模型的上下文窗口。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class EmbeddingClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 共享的 HTTP 客户端（连接池 + 超时配置） */
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // Embedding 推理可能较慢
            .build();

    /**
     * 安全截断长度。
     * <p>
     * 中文密集文本 2000 字符 ≈ 4000~6000 token，
     * 安全适配常见的 8192 token 上下文 Embedding 模型。
     */
    static final int MAX_INPUT_CHARS = 2000;

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;

    // ==================== 构造方法 ====================

    /**
     * 从 .env 文件 / 环境变量 / JVM 系统属性自动配置。
     * <p>
     * 优先级：JVM 系统属性 → OS 环境变量 → .env 文件 → 默认值。
     * 典型用法：{@code new EmbeddingClient()} 即可，无需传参。
     * <p>
     * 特殊逻辑：{@code EMBEDDING_API_KEY} 未设置时自动回退到 {@code GLM_API_KEY}，
     * 因为大多数场景下 Embedding 和 LLM 对话使用同一个智谱 API 密钥。
     */
    public EmbeddingClient() {
        this.provider = DotEnv.get("EMBEDDING_PROVIDER", "ollama");
        this.model = DotEnv.get("EMBEDDING_MODEL", "nomic-embed-text:latest");
        this.baseUrl = DotEnv.get("EMBEDDING_BASE_URL", inferDefaultUrl(provider));
        // EMBEDDING_API_KEY 未配置时自动回退到 GLM_API_KEY（同一个智谱密钥）
        this.apiKey = DotEnv.get("EMBEDDING_API_KEY",
                DotEnv.get("GLM_API_KEY", ""));
    }

    /**
     * 显式指定所有参数（用于测试或自定义配置）。
     *
     * @param provider 后端类型：ollama / zhipu / glm / openai
     * @param model    模型名称
     * @param baseUrl  API 基地址
     * @param apiKey   API 密钥（Ollama 可传空字符串）
     */
    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    // ==================== 核心 API ====================

    /**
     * 获取文本的向量表示。
     * <p>
     * 根据 provider 自动路由到对应的 API 后端：
     * <ul>
     *   <li>"ollama" → {@link #embedOllama(String)}</li>
     *   <li>"openai" / "zhipu" / "glm" → {@link #embedOpenAICompatible(String)}</li>
     * </ul>
     *
     * @param text 要向量化的文本（null 或空 → 返回空数组）
     * @return float 数组，维度取决于模型（Ollama nomic-embed-text = 768 维，GLM embedding-3 = 2048 维）
     * @throws IOException API 调用失败、响应格式异常
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }

        // 截断过长文本，防止超出模型上下文窗口导致 API 报错
        String input = text.length() > MAX_INPUT_CHARS
                ? text.substring(0, MAX_INPUT_CHARS)
                : text;

        return switch (provider.toLowerCase()) {
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu", "glm" -> embedOpenAICompatible(input);
            default -> embedOllama(input);  // 未知 provider 默认走 Ollama
        };
    }

    // ==================== Ollama 后端 ====================

    /**
     * 调用 Ollama 本地 Embedding API。
     * <p>
     * Ollama 的 Embedding 接口是非标准的，格式为：
     * <pre>
     * POST /api/embeddings
     * { "model": "nomic-embed-text:latest", "prompt": "文本" }
     * → { "embedding": [0.12, -0.34, ...] }
     * </pre>
     * <p>
     * 注意 Ollama 不需要认证（本地服务），也不走 OpenAI 兼容协议。
     */
    private float[] embedOllama(String text) throws IOException {
        String url = baseUrl + "/api/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", text);  // Ollama 用 "prompt" 字段

        String responseBody = postJson(url, requestBody.toString(), false);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode embeddingNode = root.path("embedding");

        if (!embeddingNode.isArray()) {
            throw new IOException("Ollama 返回的 embedding 格式不正确: " + responseBody);
        }

        return jsonArrayToFloatArray(embeddingNode);
    }

    // ==================== OpenAI 兼容后端 ====================

    /**
     * 调用 OpenAI 兼容的 Embedding API（智谱 GLM / OpenAI / 其他兼容服务）。
     * <p>
     * 遵循 OpenAI Embedding 标准协议：
     * <pre>
     * POST /embeddings
     * Authorization: Bearer sk-xxx
     * { "model": "embedding-3", "input": "文本" }
     * → { "data": [{ "embedding": [0.12, -0.34, ...], "index": 0 }] }
     * </pre>
     * <p>
     * 与 Ollama 的关键区别：
     * <ul>
     *   <li>字段名是 "input" 而非 "prompt"</li>
     *   <li>需要 Bearer Token 认证</li>
     *   <li>响应嵌套在 data[0].embedding 中（支持批量，但我们只发单条）</li>
     * </ul>
     */
    private float[] embedOpenAICompatible(String text) throws IOException {
        String url = baseUrl + "/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("input", text);  // OpenAI 兼容用 "input" 字段

        String responseBody = postJson(url, requestBody.toString(), true);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("API 返回的 embedding 格式不正确: " + responseBody);
        }

        // 取第一个结果（我们只发了一条 input）
        JsonNode embeddingNode = data.get(0).path("embedding");
        if (!embeddingNode.isArray()) {
            throw new IOException("API 返回的 embedding 数据缺失: " + responseBody);
        }

        return jsonArrayToFloatArray(embeddingNode);
    }

    // ==================== HTTP 工具方法 ====================

    /**
     * 发送 JSON POST 请求并返回响应体字符串。
     *
     * @param url      完整的 API URL
     * @param jsonBody 请求体 JSON 字符串
     * @param useAuth  是否附加 Bearer Token 认证头
     * @return 响应体文本
     * @throws IOException 网络错误、HTTP 状态码非 2xx、响应体为空
     */
    private String postJson(String url, String jsonBody, boolean useAuth) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body);

        if (useAuth && apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "无响应";
                throw new IOException("Embedding API 请求失败 [" + response.code() + "]: " + error);
            }
            if (responseBody == null) {
                throw new IOException("Embedding API 返回空响应体");
            }
            return responseBody.string();
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 将 Jackson JsonNode 数组转换为 float[]。
     */
    private static float[] jsonArrayToFloatArray(JsonNode arrayNode) {
        float[] result = new float[arrayNode.size()];
        for (int i = 0; i < arrayNode.size(); i++) {
            result[i] = (float) arrayNode.get(i).asDouble();
        }
        return result;
    }

    /**
     * 根据 provider 推断默认的 API 基地址。
     */
    private static String inferDefaultUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "http://localhost:11434";
            case "zhipu", "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            default -> "http://localhost:11434";
        };
    }

    /**
     * 读取配置值，委托给 DotEnv.get()。
     * <p>
     * 同时支持 JVM 系统属性、OS 环境变量、.env 文件三种来源。
     * 保留此方法供测试和外部调用使用。
     */
    static String getEnv(String key, String defaultValue) {
        return DotEnv.get(key, defaultValue);
    }

    // ==================== Getters ====================

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
