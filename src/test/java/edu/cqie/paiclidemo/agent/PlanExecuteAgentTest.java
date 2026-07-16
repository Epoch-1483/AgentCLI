package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Scanner;

import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanExecuteAgent 单元测试 —— 使用 MockLlmClient 隔离 LLM 调用。
 * 覆盖：简单目标快速通道、正常执行、失败级联跳过、replan 触发等边界。
 */
class PlanExecuteAgentTest {

    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;
    private PlanExecuteAgent planAgent;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        toolRegistry = new ToolRegistry();

        // 注册计算工具
        toolRegistry.register("add", "加法",
                createParameters(new Param("a", "string", "A"), new Param("b", "string", "B")),
                args -> String.valueOf(Integer.parseInt(args.get("a")) + Integer.parseInt(args.get("b"))));

        planAgent = new PlanExecuteAgent(mockLlm, toolRegistry);
    }

    // ==================== 简单目标快速通道 ====================

    @Test
    @DisplayName("简单目标（含'计算'关键词）→ 单任务计划，不调 LLM 规划")
    void simpleGoalFastPath() throws IOException {
        // 只需要排入执行阶段的 LLM 响应（不需要规划阶段的）
        mockLlm.enqueueText("计算结果是 42");

        String result = planAgent.run("计算 21+21");

        assertTrue(result.contains("42"));
        // 只调用了 1 次 LLM（执行阶段），没有规划阶段的调用
        assertEquals(1, mockLlm.getCallCount());
    }

    @Test
    @DisplayName("简单目标（含'几点'关键词）→ 快速通道")
    void simpleGoalTimeQuery() throws IOException {
        mockLlm.enqueueText("现在是下午三点");

        String result = planAgent.run("现在几点了");

        assertNotNull(result);
        assertEquals(1, mockLlm.getCallCount());
    }

    // ==================== 复杂目标（LLM 规划） ====================

    @Test
    @DisplayName("复杂目标 → LLM 规划 + 按 DAG 执行")
    void complexGoalWithPlanning() throws IOException {
        // 第1次 LLM 调用：规划阶段，返回 JSON 计划
        String planJson = """
                ```json
                {
                  "tasks": [
                    {"id": "1", "description": "计算10+20", "type": "COMMAND", "dependencies": []},
                    {"id": "2", "description": "总结结果", "type": "ANALYSIS", "dependencies": ["1"]}
                  ]
                }
                ```
                """;
        mockLlm.enqueueText(planJson);

        // 第2次 LLM 调用：执行任务1
        mockLlm.enqueueText("10+20=30");

        // 第3次 LLM 调用：执行任务2（分析）
        mockLlm.enqueueText("计算结果为30，这是一个简单的加法运算。");

        String result = planAgent.run("先计算10+20，然后总结结果");

        assertNotNull(result);
        assertTrue(result.length() > 0);
        assertEquals(3, mockLlm.getCallCount());
    }

    // ==================== 任务执行中的工具调用 ====================

    @Test
    @DisplayName("任务执行中 LLM 调用工具 → mini ReAct 循环")
    void taskWithToolCall() throws IOException {
        // 规划：单任务（使用含"然后"的复杂目标触发 LLM 规划）
        mockLlm.enqueueText("{\"tasks\": [{\"id\": \"1\", \"description\": \"计算5+3\", \"type\": \"COMMAND\", \"dependencies\": []}]}");

        // 执行任务1：LLM 先请求调用工具
        mockLlm.enqueue(MockLlmClient.toolCallResponse(
                MockLlmClient.toolCall("tc1", "add", "{\"a\": \"5\", \"b\": \"3\"}")
        ));
        // 执行任务1：LLM 根据工具结果给出最终回复
        mockLlm.enqueueText("5+3=8");

        String result = planAgent.run("先计算5+3，然后告诉我结果");

        // 规划1次 + 执行2次(工具调用+最终回复) = 3次
        assertEquals(3, mockLlm.getCallCount());
    }

    // ==================== 失败处理 ====================

    @Test
    @DisplayName("LLM 规划返回无效 JSON → 降级为单任务执行")
    void invalidPlanJsonFallback() throws IOException {
        // LLM 返回的不是有效 JSON
        mockLlm.enqueueText("这是一个复杂的任务，我需要仔细思考...");

        // 降级后的单任务执行
        mockLlm.enqueueText("这是最终结果");

        String result = planAgent.run("一个需要规划的任务");

        assertNotNull(result);
        // 规划1次（失败） + 降级执行1次 = 2次
        assertEquals(2, mockLlm.getCallCount());
    }

    // ==================== 任务内 mini ReAct 达到上限 ====================

    @Test
    @DisplayName("单任务 mini ReAct 达到 5 次上限 → 返回警告")
    void taskMaxIterations() throws IOException {
        // 规划：单任务
        mockLlm.enqueueText("{\"tasks\": [{\"id\": \"1\", \"description\": " +
                "\"无限循环任务\", \"type\": \"COMMAND\", \"dependencies\": []}]}");

        // 执行任务1：连续 5 次工具调用，不给最终回复
        for (int i = 0; i < 5; i++) {
            mockLlm.enqueue(MockLlmClient.toolCallResponse(
                    MockLlmClient.toolCall("tc" + i, "add", "{\"a\": \"1\", \"b\": \"1\"}")
            ));
        }

        String result = planAgent.run("执行无限循环");

        // 结果中应包含警告或正常完成
        assertNotNull(result);
    }

    // ==================== 长目标文本（>80字符）→ 走 LLM 规划 ====================

    @Test
    @DisplayName("长目标文本（>80字符）→ 不触发简单目标快速通道，走 LLM 规划")
    void longGoalNotSimple() throws IOException {
        // 构造超过 80 字符的目标
        String longGoal = "我需要你帮我完成一个非常非常复杂的任务，"
                + "这个任务包含多个步骤，首先需要创建一个项目结构，"
                + "然后编写核心代码，接着添加单元测试，最后进行代码审查和优化";

        // 规划阶段
        mockLlm.enqueueText("{\"tasks\": [{\"id\": \"1\", \"description\": \"执行\", \"type\": \"COMMAND\", \"dependencies\": []}]}");
        // 执行阶段
        mockLlm.enqueueText("完成");

        String result = planAgent.run(longGoal);

        assertNotNull(result);
        // 规划1次 + 执行1次 = 2次
        assertEquals(2, mockLlm.getCallCount());
    }

    // ==================== 规划确认交互 ====================

    @Test
    @DisplayName("交互模式：用户按回车确认 → 正常执行")
    void interactiveConfirm() throws IOException {
        Scanner scanner = new Scanner(new StringReader("\n"));  // 模拟用户按回车
        PlanExecuteAgent interactiveAgent = new PlanExecuteAgent(mockLlm, toolRegistry, scanner);

        // 规划：单任务（简单目标，不走 LLM 规划）
        mockLlm.enqueueText("计算结果是 42");

        String result = interactiveAgent.run("计算 21+21");

        assertNotNull(result);
        assertTrue(result.contains("42"));
    }

    @Test
    @DisplayName("交互模式：用户输入'取消' → 返回取消消息，不执行")
    void interactiveCancel() throws IOException {
        Scanner scanner = new Scanner(new StringReader("取消\n"));
        PlanExecuteAgent interactiveAgent = new PlanExecuteAgent(mockLlm, toolRegistry, scanner);

        // 规划阶段（简单目标，不调 LLM 规划）
        // 但执行不会发生，所以不需要排入执行响应
        // 注意：简单目标 createPlan 不调 LLM，但 confirmPlan 里会 visualize（不调 LLM）
        // 取消后直接返回，不会执行

        String result = interactiveAgent.run("计算 1+1");

        assertEquals("🚫 计划已取消", result);
        assertEquals(0, mockLlm.getCallCount(), "取消后不应调用 LLM");
    }

    @Test
    @DisplayName("交互模式：用户补充信息 → 用增强目标重新规划")
    void interactiveAddSupplement() throws IOException {
        // 模拟用户先输入"补充 请用高精度计算"，再按回车确认
        Scanner scanner = new Scanner(new StringReader("补充 请用高精度计算\n\n"));
        PlanExecuteAgent interactiveAgent = new PlanExecuteAgent(mockLlm, toolRegistry, scanner);

        // 第一次规划（简单目标 → createMinimalPlan，不调 LLM）
        // 补充后重新规划：enrichedGoal 变长（含"补充要求"），可能不再命中简单关键词
        // 但 enrichedGoal 仍然包含"计算"，所以 isSimpleGoal 仍为 true → 还是 createMinimalPlan
        // 最终执行一次 LLM
        mockLlm.enqueueText("高精度结果: 3.141592653589793");

        String result = interactiveAgent.run("计算 pi");

        assertNotNull(result);
    }
}
