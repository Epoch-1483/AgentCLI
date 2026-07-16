package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent (ReAct) 单元测试 —— 使用 MockLlmClient 隔离 LLM 调用。
 * 覆盖：立即返回、多轮工具调用、达到迭代上限、对话历史管理等边界。
 */
class AgentTest {

    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;
    private Agent agent;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        toolRegistry = new ToolRegistry();

        // 注册一个简单的加法工具
        toolRegistry.register("add", "加法",
                createParameters(
                        new Param("a", "string", "数字A"),
                        new Param("b", "string", "数字B")),
                args -> {
                    int a = Integer.parseInt(args.get("a"));
                    int b = Integer.parseInt(args.get("b"));
                    return String.valueOf(a + b);
                });

        agent = new Agent(mockLlm, toolRegistry);
    }

    // ==================== ReAct 循环 ====================

    @Test
    @DisplayName("LLM 直接返回文本（无工具调用）→ 一次返回")
    void immediateResponse() throws IOException {
        mockLlm.enqueueText("你好！我是助手。");

        String result = agent.run("你好");

        assertEquals("你好！我是助手。", result);
        assertEquals(1, mockLlm.getCallCount(), "应只调用 LLM 一次");
    }

    @Test
    @DisplayName("一轮工具调用后返回最终回复 → 调用 LLM 两次")
    void oneToolCallThenResponse() throws IOException {
        // 第一次：LLM 请求调用工具
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc1", "add", "{\"a\": \"3\", \"b\": \"5\"}")
        ));
        // 第二次：LLM 根据工具结果给出最终回复
        mockLlm.enqueueText("3 + 5 = 8");

        String result = agent.run("3 加 5 等于多少？");

        assertEquals("3 + 5 = 8", result);
        assertEquals(2, mockLlm.getCallCount());
    }

    @Test
    @DisplayName("多轮工具调用（3轮）→ 调用 LLM 4次")
    void multipleToolCalls() throws IOException {
        // 第1次：调用 add(1, 2)
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc1", "add", "{\"a\": \"1\", \"b\": \"2\"}")
        ));
        // 第2次：调用 add(3, 4)
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc2", "add", "{\"a\": \"3\", \"b\": \"4\"}")
        ));
        // 第3次：调用 add(5, 6)
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc3", "add", "{\"a\": \"5\", \"b\": \"6\"}")
        ));
        // 第4次：最终回复
        mockLlm.enqueueText("全部计算完成！");

        String result = agent.run("计算三组加法");

        assertEquals("全部计算完成！", result);
        assertEquals(4, mockLlm.getCallCount());
    }

    @Test
    @DisplayName("一次响应中包含多个工具调用 → 全部执行")
    void multipleToolCallsInOneResponse() throws IOException {
        // LLM 一次请求调用两个工具
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc1", "add", "{\"a\": \"10\", \"b\": \"20\"}"),
                MockLlmClient.toolCall("tc2", "add", "{\"a\": \"30\", \"b\": \"40\"}")
        ));
        // 最终回复
        mockLlm.enqueueText("10+20=30, 30+40=70");

        String result = agent.run("计算两组加法");

        assertEquals("10+20=30, 30+40=70", result);
        assertEquals(2, mockLlm.getCallCount());
    }

    @Test
    @DisplayName("达到最大迭代次数（10次工具调用无最终回复）→ 返回警告")
    void maxIterationsReached() throws IOException {
        // 排入 10 次工具调用响应，始终不给出最终回复
        for (int i = 0; i < 10; i++) {
            mockLlm.enqueue(MockLlmClient.toolCallResponse(
                    MockLlmClient.toolCall("tc" + i, "add", "{\"a\": \"1\", \"b\": \"1\"}")
            ));
        }

        String result = agent.run("无限循环测试");

        assertTrue(result.contains("最大迭代次数"));
        assertEquals(10, mockLlm.getCallCount());
    }

    @Test
    @DisplayName("调用不存在的工具 → 不崩溃，继续 ReAct 循环")
    void callNonexistentTool() throws IOException {
        // LLM 请求调用一个不存在的工具
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc1", "nonexistent_tool", "{}")
        ));
        // LLM 看到工具不存在的结果后，给出最终回复
        mockLlm.enqueueText("抱歉，该工具不存在。");

        String result = agent.run("调用不存在的工具");

        assertEquals("抱歉，该工具不存在。", result);
        assertEquals(2, mockLlm.getCallCount());
    }

    // ==================== 对话历史管理 ====================

    @Test
    @DisplayName("clearHistory 后重新对话 → 历史被重置")
    void clearHistory() throws IOException {
        // 第一次对话
        mockLlm.enqueueText("回复1");
        agent.run("问题1");

        // 清空历史
        agent.clearHistory();

        // 历史应只包含系统提示
        List<LlmClient.Message> history = agent.getConversationHistory();
        assertEquals(1, history.size(), "清空后应只有系统提示");
        assertEquals("system", history.get(0).role());
    }

    @Test
    @DisplayName("多轮对话 → 历史持续累积")
    void historyAccumulates() throws IOException {
        mockLlm.enqueueText("回复1");
        agent.run("问题1");

        mockLlm.enqueueText("回复2");
        agent.run("问题2");

        List<LlmClient.Message> history = agent.getConversationHistory();
        // system(1) + user(1) + assistant(1) + user(2) + assistant(1) = 5
        assertEquals(5, history.size());
    }

    @Test
    @DisplayName("getConversationHistory 返回防御性副本 → 修改不影响内部状态")
    void defensiveCopyOfHistory() throws IOException {
        mockLlm.enqueueText("回复");
        agent.run("问题");

        List<LlmClient.Message> history = agent.getConversationHistory();
        int originalSize = history.size();

        // 尝试修改返回的列表
        assertThrows(UnsupportedOperationException.class, () ->
                history.add(LlmClient.Message.user("hack")));

        // 内部历史不受影响
        assertEquals(originalSize, agent.getConversationHistory().size());
    }
}
