package edu.cqie.paiclidemo;

import edu.cqie.paiclidemo.llm.LlmClient;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * LlmClient 的 Mock 实现 —— 用于单元测试。
 * <p>
 * 设计思路：预先排队 ChatResponse，每次 chat() 调用按顺序出队。
 * 这样可以精确控制 LLM 的每次返回，模拟各种边界场景。
 * <p>
 * 用法示例：
 * <pre>
 *   MockLlmClient mock = new MockLlmClient();
 *   mock.enqueue(response1);  // 第一次 chat() 返回这个
 *   mock.enqueue(response2);  // 第二次 chat() 返回这个
 *   // ... 运行被测代码 ...
 *   assertEquals(2, mock.getCallCount());  // 验证调用次数
 * </pre>
 */
public class MockLlmClient implements LlmClient {

    private final Queue<ChatResponse> responses = new LinkedList<>();
    private int callCount = 0;

    /** 排入一个响应，chat() 按 FIFO 顺序消费 */
    public void enqueue(ChatResponse response) {
        responses.add(response);
    }

    /** 便捷方法：排入一个纯文本响应（无工具调用） */
    public void enqueueText(String content) {
        responses.add(new ChatResponse(content, List.of(), 10, 10));
    }

    /** 便捷方法：排入一个带工具调用的响应 */
    public void enqueueToolCalls(List<ToolCall> toolCalls) {
        responses.add(new ChatResponse("", toolCalls, 10, 10));
    }

    /** 便捷方法：排入一个带文本+工具调用的响应 */
    public void enqueueToolCalls(String content, List<ToolCall> toolCalls) {
        responses.add(new ChatResponse(content, toolCalls, 10, 10));
    }

    /** 获取 chat() 被调用的次数 */
    public int getCallCount() {
        return callCount;
    }

    /** 重置计数器（不清空队列） */
    public void resetCallCount() {
        callCount = 0;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) {
        callCount++;
        ChatResponse response = responses.poll();
        if (response == null) {
            throw new IllegalStateException(
                    "MockLlmClient: 队列已空！chat() 被调用了 " + callCount
                            + " 次，但只排入了 " + (callCount - 1) + " 个响应。"
                            + "请检查测试用例是否少排了 enqueue。");
        }
        return response;
    }

    @Override
    public String getModelName() {
        return "mock-model";
    }

    // ==================== 便捷工厂方法 ====================

    /** 创建一个 ToolCall */
    public static ToolCall toolCall(String id, String functionName, String arguments) {
        return new ToolCall(id, new ToolCall.Function(functionName, arguments));
    }

    /** 创建一个无工具调用的 ChatResponse */
    public static ChatResponse textResponse(String content) {
        return new ChatResponse(content, List.of(), 10, 10);
    }

    /** 创建一个带工具调用的 ChatResponse */
    public static ChatResponse toolCallResponse(ToolCall... toolCalls) {
        return new ChatResponse("", List.of(toolCalls), 10, 10);
    }
}
