package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentOrchestrator 单元测试。
 * <p>
 * 覆盖计划解析、依赖解析、审查结果解析、完整协作流程等场景。
 * 使用 {@link MockLlmClient} 控制所有 LLM 调用。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
class AgentOrchestratorTest {

    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;
    private ByteArrayOutputStream outputCapture;
    private PrintStream printStream;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        toolRegistry = new ToolRegistry();
        outputCapture = new ByteArrayOutputStream();
        printStream = new PrintStream(outputCapture, true, StandardCharsets.UTF_8);
    }

    // ==================== parsePlan 计划解析 ====================

    @Nested
    @DisplayName("parsePlan — JSON 计划解析")
    class ParsePlanTests {

        @Test
        @DisplayName("正常解析标准 JSON 计划")
        void parseStandardPlan() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String json = """
                    {
                      "steps": [
                        { "id": "1", "description": "搜索信息", "type": "COMMAND", "dependencies": [] },
                        { "id": "2", "description": "分析数据", "type": "ANALYSIS", "dependencies": ["1"] },
                        { "id": "3", "description": "生成报告", "type": "VERIFICATION", "dependencies": ["1", "2"] }
                      ]
                    }
                    """;

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan(json);

            assertEquals(3, steps.size());

            // 验证重编号
            assertEquals("step_1", steps.get(0).id());
            assertEquals("step_2", steps.get(1).id());
            assertEquals("step_3", steps.get(2).id());

            // 验证描述
            assertEquals("搜索信息", steps.get(0).description());
            assertEquals("分析数据", steps.get(1).description());

            // 验证依赖映射
            assertTrue(steps.get(0).dependencies().isEmpty());
            assertEquals(List.of("step_1"), steps.get(1).dependencies());
            assertEquals(List.of("step_1", "step_2"), steps.get(2).dependencies());

            // 验证状态都是 PENDING
            assertTrue(steps.stream().allMatch(s -> s.status() == AgentOrchestrator.StepStatus.PENDING));
        }

        @Test
        @DisplayName("兼容 markdown 代码块包裹的 JSON")
        void parseMarkdownWrappedJson() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String json = """
                    ```json
                    {
                      "steps": [
                        { "id": "1", "description": "步骤一", "type": "COMMAND", "dependencies": [] }
                      ]
                    }
                    ```
                    """;

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan(json);

            assertEquals(1, steps.size());
            assertEquals("步骤一", steps.get(0).description());
        }

        @Test
        @DisplayName("兼容 tasks 字段名")
        void parseTasksFieldName() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String json = """
                    {
                      "tasks": [
                        { "id": "1", "description": "任务一", "type": "ANALYSIS", "dependencies": [] }
                      ]
                    }
                    """;

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan(json);

            assertEquals(1, steps.size());
            assertEquals("任务一", steps.get(0).description());
        }

        @Test
        @DisplayName("无效 JSON 返回空列表")
        void parseInvalidJsonReturnsEmpty() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan("这不是 JSON");

            assertTrue(steps.isEmpty());
        }

        @Test
        @DisplayName("空 steps 数组返回空列表")
        void parseEmptyStepsReturnsEmpty() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan("{\"steps\": []}");

            assertTrue(steps.isEmpty());
        }

        @Test
        @DisplayName("步骤无 dependencies 字段时默认为空列表")
        void missingDependenciesDefaultsToEmpty() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String json = """
                    { "steps": [ { "id": "1", "description": "无依赖", "type": "COMMAND" } ] }
                    """;

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan(json);

            assertEquals(1, steps.size());
            assertTrue(steps.get(0).dependencies().isEmpty());
        }

        @Test
        @DisplayName("步骤无 type 字段时默认为 COMMAND")
        void missingTypeDefaultsToCommand() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String json = """
                    { "steps": [ { "id": "1", "description": "无类型", "dependencies": [] } ] }
                    """;

            List<AgentOrchestrator.ExecutionStep> steps = orch.parsePlan(json);

            assertEquals(1, steps.size());
            assertEquals("COMMAND", steps.get(0).type());
        }
    }

    // ==================== getExecutableSteps 依赖解析 ====================

    @Nested
    @DisplayName("getExecutableSteps — 依赖解析")
    class ExecutableStepsTests {

        @Test
        @DisplayName("无依赖的 PENDING 步骤可执行")
        void noDependenciesAreExecutable() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            List<AgentOrchestrator.ExecutionStep> steps = new java.util.ArrayList<>(List.of(
                    AgentOrchestrator.ExecutionStep.pending("step_1", "步骤一", "COMMAND", List.of()),
                    AgentOrchestrator.ExecutionStep.pending("step_2", "步骤二", "ANALYSIS", List.of())
            ));

            List<AgentOrchestrator.ExecutionStep> executable = orch.getExecutableSteps(steps);

            assertEquals(2, executable.size());
        }

        @Test
        @DisplayName("依赖未完成的步骤不可执行")
        void unmetDependenciesAreNotExecutable() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            List<AgentOrchestrator.ExecutionStep> steps = new java.util.ArrayList<>(List.of(
                    AgentOrchestrator.ExecutionStep.pending("step_1", "步骤一", "COMMAND", List.of()),
                    AgentOrchestrator.ExecutionStep.pending("step_2", "步骤二", "ANALYSIS", List.of("step_1"))
            ));

            List<AgentOrchestrator.ExecutionStep> executable = orch.getExecutableSteps(steps);

            // 只有 step_1 可执行（step_2 依赖 step_1，但 step_1 还没完成）
            assertEquals(1, executable.size());
            assertEquals("step_1", executable.get(0).id());
        }

        @Test
        @DisplayName("依赖全部完成后步骤可执行")
        void metDependenciesAreExecutable() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            List<AgentOrchestrator.ExecutionStep> steps = new java.util.ArrayList<>(List.of(
                    AgentOrchestrator.ExecutionStep.pending("step_1", "步骤一", "COMMAND", List.of())
                            .withResult("结果一"),
                    AgentOrchestrator.ExecutionStep.pending("step_2", "步骤二", "ANALYSIS", List.of("step_1"))
            ));

            List<AgentOrchestrator.ExecutionStep> executable = orch.getExecutableSteps(steps);

            assertEquals(1, executable.size());
            assertEquals("step_2", executable.get(0).id());
        }

        @Test
        @DisplayName("全部完成时返回空列表")
        void allCompletedReturnsEmpty() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            List<AgentOrchestrator.ExecutionStep> steps = new java.util.ArrayList<>(List.of(
                    AgentOrchestrator.ExecutionStep.pending("step_1", "步骤一", "COMMAND", List.of())
                            .withResult("完成"),
                    AgentOrchestrator.ExecutionStep.pending("step_2", "步骤二", "ANALYSIS", List.of("step_1"))
                            .withResult("完成")
            ));

            List<AgentOrchestrator.ExecutionStep> executable = orch.getExecutableSteps(steps);

            assertTrue(executable.isEmpty());
        }

        @Test
        @DisplayName("依赖中有 FAILED 步骤时，下游永远不会被选中")
        void failedDependencyBlocksDownstream() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            List<AgentOrchestrator.ExecutionStep> steps = new java.util.ArrayList<>(List.of(
                    AgentOrchestrator.ExecutionStep.pending("step_1", "步骤一", "COMMAND", List.of())
                            .withFailed("失败了"),
                    AgentOrchestrator.ExecutionStep.pending("step_2", "步骤二", "ANALYSIS", List.of("step_1"))
            ));

            List<AgentOrchestrator.ExecutionStep> executable = orch.getExecutableSteps(steps);

            // step_1 已 FAILED，step_2 依赖它所以永远不会被选中
            assertTrue(executable.isEmpty());
        }
    }

    // ==================== parseReviewApproval 审查解析 ====================

    @Nested
    @DisplayName("parseReviewApproval — 审查通过/拒绝解析")
    class ReviewApprovalTests {

        @Test
        @DisplayName("approved: true → 通过")
        void approvedTrue() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertTrue(orch.parseReviewApproval("""
                    {"approved": true, "summary": "结果正确"}
                    """));
        }

        @Test
        @DisplayName("approved: false → 拒绝")
        void approvedFalse() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertFalse(orch.parseReviewApproval("""
                    {"approved": false, "issues": ["数据不准确"]}
                    """));
        }

        @Test
        @DisplayName("markdown 包裹的 JSON 正确解析")
        void markdownWrappedJson() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertTrue(orch.parseReviewApproval("""
                    ```json
                    {"approved": true, "summary": "很好"}
                    ```
                    """));
        }

        @Test
        @DisplayName("缺少 approved 字段 → 默认拒绝")
        void missingApprovedFieldDefaultsToRejected() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertFalse(orch.parseReviewApproval("{\"summary\": \"还行\"}"));
        }

        @Test
        @DisplayName("空内容 → 默认拒绝")
        void emptyContentDefaultsToRejected() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertFalse(orch.parseReviewApproval(""));
        }

        @Test
        @DisplayName("null 内容 → 默认拒绝")
        void nullContentDefaultsToRejected() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertFalse(orch.parseReviewApproval(null));
        }

        @Test
        @DisplayName("无法解析 JSON 但含否定关键词 → 拒绝")
        void unparseableWithNegativeKeyword() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertFalse(orch.parseReviewApproval("审查结果：不通过，数据有问题"));
        }

        @Test
        @DisplayName("无法解析 JSON 但含肯定关键词 → 通过")
        void unparseableWithPositiveKeyword() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertTrue(orch.parseReviewApproval("审查结论：结果合格，可以接受"));
        }

        @Test
        @DisplayName("无法解析 JSON 且无明确关键词 → 默认拒绝")
        void unparseableNoKeywordsDefaultsToRejected() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            assertFalse(orch.parseReviewApproval("这是一段普通的回复文本"));
        }
    }

    // ==================== parseReviewIssues 问题提取 ====================

    @Nested
    @DisplayName("parseReviewIssues — 审查问题提取")
    class ReviewIssuesTests {

        @Test
        @DisplayName("提取 issues 数组")
        void extractIssues() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            String issues = orch.parseReviewIssues("""
                    {"approved": false, "issues": ["计算错误", "缺少边界检查"]}
                    """);

            assertTrue(issues.contains("计算错误"));
            assertTrue(issues.contains("缺少边界检查"));
        }

        @Test
        @DisplayName("无 issues 时提取 suggestions")
        void extractSuggestionsFallback() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            String issues = orch.parseReviewIssues("""
                    {"approved": false, "suggestions": ["建议使用更精确的算法"]}
                    """);

            assertTrue(issues.contains("建议使用更精确的算法"));
        }

        @Test
        @DisplayName("无 issues 和 suggestions 时提取 summary")
        void extractSummaryFallback() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            String issues = orch.parseReviewIssues("""
                    {"approved": false, "summary": "总体还行但有小问题"}
                    """);

            assertEquals("总体还行但有小问题", issues);
        }

        @Test
        @DisplayName("空内容返回空字符串")
        void emptyContentReturnsEmpty() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            String issues = orch.parseReviewIssues("");

            assertEquals("", issues);
        }

        @Test
        @DisplayName("无法解析时返回默认提示")
        void unparseableReturnsDefault() {
            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);

            String issues = orch.parseReviewIssues("完全不是 JSON");

            assertEquals("审查未通过，请改进执行结果", issues);
        }
    }

    // ==================== run() 完整流程 ====================

    @Nested
    @DisplayName("run — 完整协作流程")
    class RunTests {

        @Test
        @DisplayName("单步骤计划：规划 → 执行 → 审查通过 → 汇总")
        void singleStepSuccess() {
            // ① Planner 输出计划（1 次 LLM 调用）
            mockLlm.enqueueText("""
                    {
                      "steps": [
                        { "id": "1", "description": "计算 2+3", "type": "ANALYSIS", "dependencies": [] }
                      ]
                    }
                    """);

            // ② Worker 执行步骤（1 次 LLM 调用）
            mockLlm.enqueueText("2+3=5");

            // ③ Reviewer 审查（1 次 LLM 调用）
            mockLlm.enqueueText("""
                    {"approved": true, "summary": "计算正确", "issues": []}
                    """);

            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String result = orch.run("计算 2+3");

            // 验证结果包含完成标记
            assertTrue(result.contains("多 Agent 协作任务完成"));
            assertTrue(result.contains("计算 2+3"));

            // 验证 LLM 被调用了 3 次（planner + worker + reviewer）
            assertEquals(3, mockLlm.getCallCount());
        }

        @Test
        @DisplayName("多步骤并行计划：两个独立步骤并行执行")
        void parallelStepsSuccess() {
            // ① Planner 输出计划（2 个独立步骤）
            mockLlm.enqueueText("""
                    {
                      "steps": [
                        { "id": "1", "description": "查询天气", "type": "COMMAND", "dependencies": [] },
                        { "id": "2", "description": "查询股票", "type": "COMMAND", "dependencies": [] },
                        { "id": "3", "description": "汇总报告", "type": "VERIFICATION", "dependencies": ["1", "2"] }
                      ]
                    }
                    """);

            // 并行批次：step_1 和 step_2 各有 Worker + 独立 Reviewer = 4 次 LLM 调用
            // （并行执行时队列消费顺序不确定，所以用通用响应）
            mockLlm.enqueueText("今天晴天，25°C");           // Worker 执行
            mockLlm.enqueueText("""
                    {"approved": true, "summary": "通过"}
                    """);                                     // Reviewer 审查
            mockLlm.enqueueText("A股涨 2%，美股跌 1%");      // Worker 执行
            mockLlm.enqueueText("""
                    {"approved": true, "summary": "通过"}
                    """);                                     // Reviewer 审查

            // 串行路径：step_3（Worker + 共享 Reviewer）= 2 次 LLM 调用
            mockLlm.enqueueText("今日综合：天气晴好 25°C，A股上涨 2%");
            mockLlm.enqueueText("""
                    {"approved": true, "summary": "汇总完整"}
                    """);

            // 总计：1 (planner) + 4 (parallel) + 2 (serial) = 7 次

            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String result = orch.run("查询天气和股票");

            assertTrue(result.contains("多 Agent 协作任务完成"));
        }

        @Test
        @DisplayName("规划失败时返回错误信息")
        void planningFailure() {
            // Planner 返回 ERROR
            mockLlm.enqueue(new LlmClient.ChatResponse(null, null, 0, 0));

            // SubAgent 遇到 null content 时会作为 assistant 消息返回
            // 但由于 content 是 null，planResult.content().isBlank() 会 NPE
            // 实际上 MockLlmClient 出队后 SubAgent 会正常处理
            // 让我重新设计：Planner 输出空内容
            // 需要重新创建 mock 因为上面已经 enqueue 了
            mockLlm = new MockLlmClient();
            mockLlm.enqueueText("");  // Planner 输出空内容

            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String result = orch.run("一个任务");

            assertTrue(result.contains("规划失败"));
        }

        @Test
        @DisplayName("Worker 返回 ERROR 时步骤标记为失败")
        void workerErrorMarksStepFailed() {
            // ① Planner 输出计划
            mockLlm.enqueueText("""
                    {
                      "steps": [
                        { "id": "1", "description": "危险操作", "type": "COMMAND", "dependencies": [] }
                      ]
                    }
                    """);

            // ② Worker 达到最大迭代（5 次 LLM 调用都返回工具调用，但没有工具可用）
            // 简单方案：让 Worker 的 LLM 返回带 tool_calls 的响应 5 次
            for (int i = 0; i < 5; i++) {
                mockLlm.enqueueToolCalls(List.of(
                        MockLlmClient.toolCall("tc_" + i, "nonexistent_tool", "{}")
                ));
            }

            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String result = orch.run("执行危险操作");

            // Worker 达到最大迭代后会返回 ERROR 消息
            // 步骤会被标记为失败
            assertTrue(result.contains("⚠️") || result.contains("失败") || result.contains("未完成"));
        }

        @Test
        @DisplayName("审查拒绝后重试成功")
        void reviewRejectionThenRetrySuccess() {
            // ① Planner 输出计划
            mockLlm.enqueueText("""
                    {
                      "steps": [
                        { "id": "1", "description": "写报告", "type": "ANALYSIS", "dependencies": [] }
                      ]
                    }
                    """);

            // ② Worker 第一次执行
            mockLlm.enqueueText("初步报告");

            // ③ Reviewer 拒绝第一次
            mockLlm.enqueueText("""
                    {"approved": false, "issues": ["内容不够详细"], "summary": "需要补充"}
                    """);

            // ④ Worker 重试执行（带反馈）
            mockLlm.enqueueText("详细报告：包含数据分析和结论");

            // ⑤ Reviewer 通过重试
            mockLlm.enqueueText("""
                    {"approved": true, "summary": "内容详实"}
                    """);

            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String result = orch.run("写一份详细报告");

            assertTrue(result.contains("多 Agent 协作任务完成"));
            // 验证重试后的结果被采用
            assertTrue(result.contains("详细报告"));
        }

        @Test
        @DisplayName("无法解析的计划 JSON 返回错误")
        void unparseablePlanReturnsError() {
            mockLlm.enqueueText("我觉得这个任务很复杂，没法拆解");

            AgentOrchestrator orch = new AgentOrchestrator(mockLlm, toolRegistry, null, printStream);
            String result = orch.run("一个模糊的任务");

            assertTrue(result.contains("规划失败"));
        }
    }

    // ==================== ExecutionStep record ====================

    @Nested
    @DisplayName("ExecutionStep — record 行为")
    class ExecutionStepTests {

        @Test
        @DisplayName("pending 工厂方法创建 PENDING 状态步骤")
        void pendingFactoryMethod() {
            AgentOrchestrator.ExecutionStep step = AgentOrchestrator.ExecutionStep.pending(
                    "step_1", "测试步骤", "COMMAND", List.of("step_0"));

            assertEquals("step_1", step.id());
            assertEquals("测试步骤", step.description());
            assertEquals("COMMAND", step.type());
            assertEquals(List.of("step_0"), step.dependencies());
            assertNull(step.result());
            assertEquals(AgentOrchestrator.StepStatus.PENDING, step.status());
        }

        @Test
        @DisplayName("withResult 返回 COMPLETED 新实例")
        void withResultReturnsCompleted() {
            AgentOrchestrator.ExecutionStep pending = AgentOrchestrator.ExecutionStep.pending(
                    "step_1", "测试", "COMMAND", List.of());

            AgentOrchestrator.ExecutionStep completed = pending.withResult("执行完毕");

            assertEquals(AgentOrchestrator.StepStatus.COMPLETED, completed.status());
            assertEquals("执行完毕", completed.result());
            // 原实例不变
            assertEquals(AgentOrchestrator.StepStatus.PENDING, pending.status());
        }

        @Test
        @DisplayName("withFailed 返回 FAILED 新实例")
        void withFailedReturnsFailed() {
            AgentOrchestrator.ExecutionStep pending = AgentOrchestrator.ExecutionStep.pending(
                    "step_1", "测试", "COMMAND", List.of());

            AgentOrchestrator.ExecutionStep failed = pending.withFailed("执行出错");

            assertEquals(AgentOrchestrator.StepStatus.FAILED, failed.status());
            assertEquals("执行出错", failed.result());
            // 原实例不变
            assertEquals(AgentOrchestrator.StepStatus.PENDING, pending.status());
        }
    }
}
