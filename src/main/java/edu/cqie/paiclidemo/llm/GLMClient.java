package edu.cqie.paiclidemo.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 智谱 GLM 大模型客户端 —— 实现 OpenAI 兼容协议。
 * <p>
 * 职责：
 * 1. 将 Message 列表 + Tool 列表序列化为 HTTP 请求体
 * 2. 通过 OkHttp 发送请求并解析 SSE 流式响应
 * 3. 将流式 delta 累积为完整的 ChatResponse
 * <p>
 *
 * @author Fonzo
 * @date 2026/07/03
 */
public class GLMClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GLMClient.class);

    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String MODEL = "glm-4.5-air";
    private static final ObjectMapper mapper = new ObjectMapper(); // 序列化和反序列化

    private final String apiKey;
    private final OkHttpClient httpClient;

    public GLMClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // 模型推理可能较慢
                .callTimeout(600, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getModelName() {
        return MODEL;
    }

    // ==================== 核心方法：发送对话请求 ====================

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        // 1. 构建请求体 JSON
        RequestBody body = buildRequestBody(messages, tools);

        // 2. 构建 HTTP 请求
        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        // 3. 发送请求并解析 SSE 流式响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "unknown";
                throw new IOException("GLM API 请求失败: HTTP " + response.code() + " - " + errorBody);
            }

            return parseSseStream(response);
        }
    }

    // ==================== 请求体构建 ====================

    /**
     * 构建发送给 GLM API 的 JSON 请求体。
     * <p>
     * 格式遵循 OpenAI 兼容协议：
     * <pre>
     * {
     *   "model": "glm-5.1",
     *   "stream": true,
     *   "messages": [ ... ],
     *   "tools": [ ... ]    // 可选
     * }
     * </pre>
     */
    private RequestBody buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.put("stream", true);

        // --- 序列化消息列表 ---
        ArrayNode msgArray = root.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = msgArray.addObject();
            msgNode.put("role", msg.role());

            if (msg.content() != null) {
                msgNode.put("content", msg.content());
            }

            // assistant 消息中的工具调用请求
            if (msg.hasToolCalls()) {
                ArrayNode tcArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = tcArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fnNode = tcNode.putObject("function");
                    fnNode.put("name", tc.function().name());
                    fnNode.put("arguments", tc.function().arguments());
                }
            }

            // tool 消息中的调用 ID
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // --- 序列化工具定义（JSON Schema 格式）---
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode fnDef = toolNode.putObject("function");
                fnDef.put("name", tool.name());
                fnDef.put("description", tool.description());
                fnDef.set("parameters", tool.parameters());
            }
        }

        String json = root.toString();
        log.debug("请求体: {}", json);

        return RequestBody.create(json, MediaType.parse("application/json"));
    }

    // ==================== SSE 流式响应解析 ====================

    /**
     * 解析 SSE（Server-Sent Events）流式响应。
     * <p>
     * 每行格式：data: {"choices":[{"delta":{...}}], "usage":{...}}
     * 流结束标志：data: [DONE]
     * <p>
     * 需要将多个 delta 片段累积拼接成完整内容。
     */
    private ChatResponse parseSseStream(Response response) throws IOException {
        StringBuilder contentBuilder = new StringBuilder(); // 收集文本内容的碎片，拼成完整回复
        List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>(); // 收集工具调用的碎片，拼成完整的 ToolCall
        int inputTokens = 0, outputTokens = 0;

        try (ResponseBody respBody = response.body()) {
            if (respBody == null) {
                throw new IOException("响应体为空");
            }

            String line;
            while ((line = respBody.source().readUtf8Line()) != null) {
                // 只处理 "data:" 开头的行
                if (!line.startsWith("data:")) {
                    continue;
                }

                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }

                try {
                    JsonNode chunk = mapper.readTree(data); // 解析JSON -- 读的时候通常不关心具体子类

                    // 解析 delta（增量片段）
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    if (!delta.isMissingNode()) {
                        // 累积文本内容
                        if (delta.has("content") && !delta.get("content").isNull()) {
                            contentBuilder.append(delta.get("content").asText());
                        }
                        // 累积工具调用（可能是多个 delta 片段拼成一个完整调用）
                        if (delta.has("tool_calls")) {
                            mergeToolCallDeltas(toolCallAccumulators, delta.get("tool_calls"));
                        }
                    }

                    // 解析 token 用量（部分 API 在最后一个 chunk 返回）
                    if (chunk.has("usage")) {
                        JsonNode usage = chunk.get("usage");
                        inputTokens = usage.path("prompt_tokens").asInt(0);
                        outputTokens = usage.path("completion_tokens").asInt(0);
                    }
                } catch (Exception e) {
                    log.warn("解析 SSE 数据失败: {}", data, e);
                }
            }
        }

        // 将累积的片段组装为最终的 ToolCall 列表
        List<ToolCall> toolCalls = buildToolCalls(toolCallAccumulators);

        log.info("GLM 响应 | content={} chars, toolCalls={}, tokens: in={}, out={}",
                contentBuilder.length(), toolCalls.size(), inputTokens, outputTokens);

        return new ChatResponse(
                contentBuilder.toString(),
                toolCalls,
                inputTokens,
                outputTokens
        );
    }

    /**
     * 将流式返回的 tool_call delta 片段合并到累积器中。
     * <p>
     * 模型可能分多个 chunk 返回一个工具调用：
     * - 第一个 delta 包含 id 和 function.name
     * - 后续 delta 只包含 function.arguments 的片段
     */
    private void mergeToolCallDeltas(List<ToolCallAccumulator> accs, JsonNode toolCallDeltas) {
        for (JsonNode delta : toolCallDeltas) {
            int index = delta.path("index").asInt(0);

            // 确保累积器列表足够长
            while (accs.size() <= index) {
                accs.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accs.get(index);

            if (delta.has("id") && !delta.get("id").isNull()) {
                acc.id = delta.get("id").asText();
            }
            if (delta.has("function")) {
                JsonNode fn = delta.get("function");
                if (fn.has("name") && !fn.get("name").isNull()) {
                    acc.name = fn.get("name").asText();
                }
                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                    acc.arguments.append(fn.get("arguments").asText());
                }
            }
        }
    }

    /** 将累积器转换为正式的 ToolCall record 列表 */
    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accs) {
        List<ToolCall> result = new ArrayList<>();
        for (ToolCallAccumulator acc : accs) {
            if (acc.id != null && acc.name != null) {
                result.add(new ToolCall(
                        acc.id,
                        new ToolCall.Function(acc.name, acc.arguments.toString())
                ));
            }
        }
        return result;
    }

    // ==================== 内部辅助类 ====================

    /**
     * 工具调用累积器 —— 用于在 SSE 流式传输中逐步拼接完整的工具调用。
     * <p>
     * 因为模型可能分多个 chunk 返回一个 tool_call，
     * 所以需要一个可变对象来累积各个片段。
     */
    private static final class ToolCallAccumulator {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }
}
