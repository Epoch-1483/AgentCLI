package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SubAgent 单元测试。
 * <p>
 * 使用 MockLlmClient 模拟 LLM 响应，覆盖：
 * - 三种角色的系统提示词
 * - WORKER 的工具调用 ReAct 循环
 * - PLANNER / REVIEWER 不使用工具
 * - executeWithContext 上下文注入
 * - clearHistory 历史重置
 * - review 审查方法
 * - 达到最大迭代次数的兜底
 */
class SubAgentTest {

    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;
    private ByteArrayOutputStream outputBuffer;
    private PrintStream out;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        toolRegistry = new ToolRegistry();
        outputBuffer = new ByteArrayOutputStream();
        out = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);

        // 注册一个测试工具
        toolRegistry.register("echo", "回显输入内容",
                createParameters(new Param("text", "string", "要回显的文本")),
                args -> "echo: " + args.getOrDefault("text", ""));
    }

    // ==================== 角色与提示词 ====================

    @Nested
    @DisplayName("角色提示词")
    class PromptTests {

        @Test
        @DisplayName("PLANNER 提示词包含 JSON 计划格式")
        void plannerPromptContainsJsonFormat() {
            SubAgent planner = new SubAgent("planner", AgentRole.PLANNER, mockLlm, toolRegistry);
            String prompt = planner.getSystemPrompt();
            assertTrue(prompt.contains("steps"));
            assertTrue(prompt.contains("dependencies"));
            assertTrue(prompt.contains("JSON"));
        }

        @Test
        @DisplayName("WORKER 提示词包含工具使用指导")
        void workerPromptContainsToolGuidance() {
            SubAgent worker = new SubAgent("worker", AgentRole.WORKER, mockLlm, toolRegistry);
            String prompt = worker.getSystemPrompt();
            assertTrue(prompt.contains("工具"));
        }

        @Test
        @DisplayName("REVIEWER 提示词包含 approved 字段")
        void reviewerPromptContainsApprovedField() {
            SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, mockLlm, toolRegistry);
            String prompt = reviewer.getSystemPrompt();
            assertTrue(prompt.contains("approved"));
            assertTrue(prompt.contains("JSON"));
        }
    }

    // ==================== PLANNER 执行 ====================

    @Nested
    @DisplayName("PLANNER 执行")
    class PlannerTests {

        @Test
        @DisplayName("PLANNER 返回 JSON 计划（不调用工具）")
        void plannerReturnsJsonPlan() {
            String planJson = """
                    {"steps": [{"id": "1", "description": "分析需求", "type": "ANALYSIS", "dependencies": []}]}""";
            mockLlm.enqueueText(planJson);

            SubAgent planner = new SubAgent("planner", AgentRole.PLANNER, mockLlm, toolRegistry);
            AgentMessage result = planner.execute(AgentMessage.task("orch", "帮我分析"), out);

            assertEquals(AgentMessage.Type.RESULT, result.type());
            assertEquals(AgentRole.PLANNER, result.fromRole());
            assertTrue(result.content().contains("steps"));
            assertEquals(1, mockLlm.getCallCount());
        }
    }

    // ==================== WORKER 执行 ====================

    @Nested
    @DisplayName("WORKER 执行")
    class WorkerTests {

        @Test
        @DisplayName("WORKER 直接返回文本（无工具调用）")
        void workerReturnsTextDirectly() {
            mockLlm.enqueueText("计算结果是 42");

            SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, mockLlm, toolRegistry);
            AgentMessage result = worker.execute(AgentMessage.task("orch", "计算 6×7"), out);

            assertEquals(AgentMessage.Type.RESULT, result.type());
            assertEquals("计算结果是 42", result.content());
            assertEquals("worker-1", result.fromAgent());
        }

        @Test
        @DisplayName("WORKER 调用工具后继续推理")
        void workerCallsToolThenContinues() {
            // 第一次 LLM 调用：请求调用 echo 工具
            LlmClient.ToolCall toolCall = MockLlmClient.toolCall(
                    "tc1", "echo", "{\"text\": \"hello\"}");
            mockLlm.enqueueToolCalls(List.of(toolCall));
            // 第二次 LLM 调用：根据工具结果输出最终结果
            mockLlm.enqueueText("根据工具返回，结果是 echo: hello");

            SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, mockLlm, toolRegistry);
            AgentMessage result = worker.execute(AgentMessage.task("orch", "回显 hello"), out);

            assertEquals(AgentMessage.Type.RESULT, result.type());
            assertTrue(result.content().contains("echo: hello"));
            assertEquals(2, mockLlm.getCallCount());
            // 输出中应包含工具调用提示
            String output = outputBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("echo"));
        }

        @Test
        @DisplayName("WORKER 达到最大迭代次数返回 ERROR")
        void workerMaxIterationsReturnsError() {
            // 每次 LLM 都请求工具调用，永远不返回最终结果
            for (int i = 0; i < 10; i++) {
                LlmClient.ToolCall tc = MockLlmClient.toolCall("tc" + i, "echo", "{\"text\": \"x\"}");
                mockLlm.enqueueToolCalls(List.of(tc));
            }

            SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, mockLlm, toolRegistry);
            AgentMessage result = worker.execute(AgentMessage.task("orch", "循环任务"), out);

            assertEquals(AgentMessage.Type.ERROR, result.type());
            assertTrue(result.content().contains("最大迭代"));
        }
    }

    // ==================== REVIEWER 执行 ====================

    @Nested
    @DisplayName("REVIEWER 执行")
    class ReviewerTests {

        @Test
        @DisplayName("REVIEWER 返回审查结论（不调用工具）")
        void reviewerReturnsReviewResult() {
            String reviewJson = """
                    {"approved": true, "summary": "结果正确", "issues": [], "suggestions": []}""";
            mockLlm.enqueueText(reviewJson);

            SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, mockLlm, toolRegistry);
            AgentMessage result = reviewer.review("计算 6×7", "42", out);

            assertEquals(AgentMessage.Type.RESULT, result.type());
            assertTrue(result.content().contains("approved"));
            assertEquals(1, mockLlm.getCallCount());
        }
    }

    // ==================== executeWithContext ====================

    @Nested
    @DisplayName("executeWithContext")
    class ContextTests {

        @Test
        @DisplayName("注入前序上下文到任务内容中")
        void contextInjected() {
            mockLlm.enqueueText("根据上下文完成");

            SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, mockLlm, toolRegistry);
            AgentMessage result = worker.executeWithContext(
                    AgentMessage.task("orch", "完成步骤 2"),
                    "步骤 1 的结果：数据已准备好",
                    out);

            assertEquals(AgentMessage.Type.RESULT, result.type());
            // 验证 LLM 收到的消息包含上下文（通过检查调用次数）
            assertEquals(1, mockLlm.getCallCount());
        }

        @Test
        @DisplayName("空上下文不改变任务内容")
        void emptyContextNoChange() {
            mockLlm.enqueueText("完成");

            SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, mockLlm, toolRegistry);
            AgentMessage result = worker.executeWithContext(
                    AgentMessage.task("orch", "执行任务"),
                    "",
                    out);

            assertEquals("完成", result.content());
        }
    }

    // ==================== clearHistory ====================

    @Nested
    @DisplayName("clearHistory")
    class HistoryTests {

        @Test
        @DisplayName("clearHistory 保留系统提示词，清空对话")
        void clearHistoryKeepsSystemPrompt() {
            mockLlm.enqueueText("第一次任务结果");
            mockLlm.enqueueText("第二次任务结果");

            SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, mockLlm, toolRegistry);

            // 执行第一个任务
            worker.execute(AgentMessage.task("orch", "任务 A"), out);
            assertEquals(1, mockLlm.getCallCount());

            // 清空历史
            worker.clearHistory();

            // 执行第二个任务（不受第一个任务的上下文影响）
            AgentMessage result = worker.execute(AgentMessage.task("orch", "任务 B"), out);
            assertEquals("第二次任务结果", result.content());
            assertEquals(2, mockLlm.getCallCount());
        }
    }

    // ==================== LLM 调用失败 ====================

    @Test
    @DisplayName("LLM 调用抛出 IOException 时返回 ERROR")
    void llmErrorReturnsErrorMessage() throws Exception {
        // 使用一个总是抛异常的 LlmClient
        LlmClient failingClient = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws java.io.IOException {
                throw new java.io.IOException("网络超时");
            }
            @Override
            public String getModelName() { return "failing-model"; }
        };

        SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, failingClient, toolRegistry);
        AgentMessage result = worker.execute(AgentMessage.task("orch", "测试"), out);

        assertEquals(AgentMessage.Type.ERROR, result.type());
        assertTrue(result.content().contains("LLM 调用失败"));
    }
}
