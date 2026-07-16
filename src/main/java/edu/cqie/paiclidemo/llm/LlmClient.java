package edu.cqie.paiclidemo.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/**
 * LLM 客户端接口 —— 定义与大模型交互的统一抽象。
 * <p>
 * 核心类型全部使用 Java 17 record，不可变、线程安全，
 * 适合作为多轮对话历史的载体。
 */
public interface LlmClient {

    // ==================== 核心交互方法 ====================

    /**
     * 发送对话消息 + 工具定义，获取模型回复。
     */
    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    /** 返回当前使用的模型名称 */
    String getModelName();

    // ==================== 数据载体（record） ====================

    /**
     * 对话消息 —— 对应 OpenAI 兼容协议中的 message 对象。
     * <p>
     * role 取值：system / user / assistant / tool
     */
    record Message(
            String role,
            String content,
            List<ToolCall> toolCalls,   // assistant 消息中模型请求调用的工具列表
            String toolCallId           // tool 消息中对应的调用 ID
    ) {
        // ---------- 便捷工厂方法 ----------
        public static Message system(String content) {

            return new Message("system", content, null, null);
        }

        public static Message user(String content) {

            return new Message("user", content, null, null);
        }

        public static Message assistant(String content) {
            // 纯回复
            return new Message("assistant", content, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            // 要调用工具
            return new Message("assistant", content, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            // 工具结果
            return new Message("tool", content, null, toolCallId);
        }

        /** 判断该消息是否包含工具调用请求 */
        public boolean hasToolCalls() {

            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 工具调用请求 —— 模型在 assistant 消息中返回。
     * <p>
     * id:       本次调用的唯一标识，回传结果时需要原样带回
     * name:     要调用的工具名称
     * arguments: JSON 字符串格式的参数（由模型生成）
     */
    record ToolCall(String id, Function function) {
        /** 嵌套的 function 对象 */
        public record Function(String name, String arguments) {}
    }

    /**
     * 工具定义 —— 发送给模型，让它知道有哪些工具可用。
     * <p>
     * parameters 使用 Jackson JsonNode 承载 JSON Schema，
     * 描述工具参数的类型、必填项等信息。
     */
    record Tool(String name, String description, JsonNode parameters) {}

    /**
     * 模型回复 —— 一次 chat() 调用的完整响应。
     * <p>
     * 当 toolCalls 非空时，表示模型希望调用工具（ReAct 中的 Act 阶段）；
     * 当 toolCalls 为空时，content 就是最终回复（ReAct 循环结束）。
     */
    record ChatResponse(
            String content,
            List<ToolCall> toolCalls,
            int inputTokens,
            int outputTokens
    ) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
