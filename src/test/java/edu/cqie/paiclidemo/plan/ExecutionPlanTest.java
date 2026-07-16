package edu.cqie.paiclidemo.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutionPlan 单元测试 —— 覆盖 DAG 操作、环检测、两遍扫描解析、批次分组等边界。
 */
class ExecutionPlanTest {

    // ==================== addTask + 双向依赖边 ====================

    @Nested
    @DisplayName("addTask 与双向依赖边")
    class AddTaskTests {

        @Test
        @DisplayName("添加任务后，被依赖的任务自动获得 dependent")
        void autoWireDependents() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            Task t1 = new Task("1", "创建文件", Task.Type.COMMAND, List.of());
            Task t2 = new Task("2", "分析结果", Task.Type.ANALYSIS, List.of("1"));

            plan.addTask(t1);
            plan.addTask(t2);

            assertEquals(List.of("2"), t1.getDependents(), "Task 1 应该知道 Task 2 在等它");
        }

        @Test
        @DisplayName("多依赖：两个任务都依赖同一个 → 该任务有两个 dependents")
        void multipleDependents() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            Task t1 = new Task("1", "基础", Task.Type.COMMAND, List.of());
            Task t2 = new Task("2", "分支A", Task.Type.COMMAND, List.of("1"));
            Task t3 = new Task("3", "分支B", Task.Type.COMMAND, List.of("1"));

            plan.addTask(t1);
            plan.addTask(t2);
            plan.addTask(t3);

            assertEquals(2, t1.getDependents().size());
            assertTrue(t1.getDependents().contains("2"));
            assertTrue(t1.getDependents().contains("3"));
        }

        @Test
        @DisplayName("依赖 ID 不存在的任务 → 不报错，只是没有反向边")
        void dependencyNotYetAdded() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            // Task 2 引用了 Task 1，但 Task 1 还没添加
            Task t2 = new Task("2", "后续", Task.Type.ANALYSIS, List.of("1"));
            plan.addTask(t2);

            // 不应该抛异常，Task 2 正常添加
            assertNotNull(plan.getTask("2"));
        }
    }

    // ==================== getExecutableTasks ====================

    @Nested
    @DisplayName("getExecutableTasks")
    class ExecutableTasksTests {

        @Test
        @DisplayName("初始状态：只有根任务是可执行的")
        void initialExecutable() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            Task t1 = new Task("1", "根", Task.Type.COMMAND, List.of());
            Task t2 = new Task("2", "子", Task.Type.ANALYSIS, List.of("1"));

            plan.addTask(t1);
            plan.addTask(t2);

            List<Task> exec = plan.getExecutableTasks();
            assertEquals(1, exec.size());
            assertEquals("1", exec.get(0).getId());
        }

        @Test
        @DisplayName("根任务完成后：子任务变为可执行")
        void afterRootCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            Task t1 = new Task("1", "根", Task.Type.COMMAND, List.of());
            Task t2 = new Task("2", "子", Task.Type.ANALYSIS, List.of("1"));

            plan.addTask(t1);
            plan.addTask(t2);

            t1.markStarted();
            t1.markCompleted("done");

            List<Task> exec = plan.getExecutableTasks();
            assertEquals(1, exec.size());
            assertEquals("2", exec.get(0).getId());
        }

        @Test
        @DisplayName("全部完成 → 无可执行任务")
        void allCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            Task t1 = new Task("1", "根", Task.Type.COMMAND, List.of());
            plan.addTask(t1);
            t1.markStarted();
            t1.markCompleted("done");

            assertTrue(plan.getExecutableTasks().isEmpty());
        }

        @Test
        @DisplayName("并行任务：两个无依赖任务同时可执行")
        void parallelExecutable() {
            ExecutionPlan plan = new ExecutionPlan("测试");

            Task t1 = new Task("1", "并行A", Task.Type.COMMAND, List.of());
            Task t2 = new Task("2", "并行B", Task.Type.COMMAND, List.of());
            Task t3 = new Task("3", "汇聚", Task.Type.ANALYSIS, List.of("1", "2"));

            plan.addTask(t1);
            plan.addTask(t2);
            plan.addTask(t3);

            List<Task> exec = plan.getExecutableTasks();
            assertEquals(2, exec.size());
        }
    }

    // ==================== 拓扑排序 + 环检测 ====================

    @Nested
    @DisplayName("拓扑排序与环检测")
    class TopologicalSortTests {

        @Test
        @DisplayName("线性链 1→2→3 → 成功")
        void linearChain() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "第一步", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "第二步", Task.Type.COMMAND, List.of("1")));
            plan.addTask(new Task("3", "第三步", Task.Type.COMMAND, List.of("2")));

            assertTrue(plan.computeExecutionOrder());

            List<String> order = plan.getExecutionOrder();
            assertEquals(3, order.size());
            // 拓扑正序：1 必须在 2 前，2 必须在 3 前
            assertTrue(order.indexOf("1") < order.indexOf("2"));
            assertTrue(order.indexOf("2") < order.indexOf("3"));
        }

        @Test
        @DisplayName("钻石 DAG (1→2, 1→3, 2→4, 3→4) → 成功")
        void diamondDAG() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "入口", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "左分支", Task.Type.COMMAND, List.of("1")));
            plan.addTask(new Task("3", "右分支", Task.Type.COMMAND, List.of("1")));
            plan.addTask(new Task("4", "汇聚", Task.Type.ANALYSIS, List.of("2", "3")));

            assertTrue(plan.computeExecutionOrder());

            List<String> order = plan.getExecutionOrder();
            assertEquals(4, order.size());
            assertTrue(order.indexOf("1") < order.indexOf("2"));
            assertTrue(order.indexOf("1") < order.indexOf("3"));
            assertTrue(order.indexOf("2") < order.indexOf("4"));
            assertTrue(order.indexOf("3") < order.indexOf("4"));
        }

        @Test
        @DisplayName("直接循环 A→B→A → 检测到环，返回 false")
        void directCycle() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("A", "节点A", Task.Type.COMMAND, List.of("B")));
            plan.addTask(new Task("B", "节点B", Task.Type.COMMAND, List.of("A")));

            assertFalse(plan.computeExecutionOrder(), "应检测到 A↔B 循环");
        }

        @Test
        @DisplayName("三角循环 A→B→C→A → 检测到环")
        void triangleCycle() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("A", "A", Task.Type.COMMAND, List.of("C")));
            plan.addTask(new Task("B", "B", Task.Type.COMMAND, List.of("A")));
            plan.addTask(new Task("C", "C", Task.Type.COMMAND, List.of("B")));

            assertFalse(plan.computeExecutionOrder());
        }

        @Test
        @DisplayName("自引用 A→A → 检测到环")
        void selfLoop() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("A", "自己依赖自己", Task.Type.COMMAND, List.of("A")));

            assertFalse(plan.computeExecutionOrder());
        }

        @Test
        @DisplayName("空计划 → 成功（无环）")
        void emptyPlan() {
            ExecutionPlan plan = new ExecutionPlan("空计划");
            assertTrue(plan.computeExecutionOrder());
            assertNotNull(plan.getExecutionOrder());
            assertTrue(plan.getExecutionOrder().isEmpty());
        }

        @Test
        @DisplayName("单任务 → 成功")
        void singleTask() {
            ExecutionPlan plan = new ExecutionPlan("单任务");
            plan.addTask(new Task("1", "唯一", Task.Type.COMMAND, List.of()));

            assertTrue(plan.computeExecutionOrder());
            assertEquals(List.of("1"), plan.getExecutionOrder());
        }
    }

    // ==================== getExecutionBatches ====================

    @Nested
    @DisplayName("执行批次分组")
    class BatchTests {

        @Test
        @DisplayName("线性链 → 3 个批次")
        void linearBatches() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "第一步", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "第二步", Task.Type.COMMAND, List.of("1")));
            plan.addTask(new Task("3", "第三步", Task.Type.COMMAND, List.of("2")));

            List<List<Task>> batches = plan.getExecutionBatches();
            assertEquals(3, batches.size());
            assertEquals(1, batches.get(0).size());
            assertEquals("1", batches.get(0).get(0).getId());
        }

        @Test
        @DisplayName("钻石 DAG → 3 个批次：[1], [2,3], [4]")
        void diamondBatches() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "入口", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "左", Task.Type.COMMAND, List.of("1")));
            plan.addTask(new Task("3", "右", Task.Type.COMMAND, List.of("1")));
            plan.addTask(new Task("4", "汇聚", Task.Type.ANALYSIS, List.of("2", "3")));

            List<List<Task>> batches = plan.getExecutionBatches();
            assertEquals(3, batches.size());
            assertEquals(1, batches.get(0).size());  // [1]
            assertEquals(2, batches.get(1).size());  // [2, 3]
            assertEquals(1, batches.get(2).size());  // [4]
        }

        @Test
        @DisplayName("全部无依赖 → 1 个批次")
        void allIndependent() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "A", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "B", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("3", "C", Task.Type.COMMAND, List.of()));

            List<List<Task>> batches = plan.getExecutionBatches();
            assertEquals(1, batches.size());
            assertEquals(3, batches.get(0).size());
        }
    }

    // ==================== 状态查询 ====================

    @Nested
    @DisplayName("状态查询方法")
    class StatusQueryTests {

        @Test
        @DisplayName("isAllCompleted：全部完成 → true")
        void allCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            Task t1 = new Task("1", "任务1", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markCompleted("ok");
            Task t2 = new Task("2", "任务2", Task.Type.COMMAND, List.of());
            t2.markStarted();
            t2.markCompleted("ok");

            plan.addTask(t1);
            plan.addTask(t2);

            assertTrue(plan.isAllCompleted());
        }

        @Test
        @DisplayName("isAllCompleted：一个未完成 → false")
        void notAllCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            Task t1 = new Task("1", "完成", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markCompleted("ok");
            Task t2 = new Task("2", "未完成", Task.Type.COMMAND, List.of());

            plan.addTask(t1);
            plan.addTask(t2);

            assertFalse(plan.isAllCompleted());
        }

        @Test
        @DisplayName("isAllCompleted：有 SKIPPED 的任务 → false（SKIPPED ≠ COMPLETED）")
        void skippedIsNotCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            Task t1 = new Task("1", "完成", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markCompleted("ok");
            Task t2 = new Task("2", "跳过", Task.Type.COMMAND, List.of());
            t2.markSkipped();

            plan.addTask(t1);
            plan.addTask(t2);

            assertFalse(plan.isAllCompleted());
        }

        @Test
        @DisplayName("hasFailed：有失败任务 → true")
        void hasFailed() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            Task t1 = new Task("1", "失败", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markFailed("error");

            plan.addTask(t1);
            assertTrue(plan.hasFailed());
        }

        @Test
        @DisplayName("getProgress：一半完成 → 0.5")
        void halfProgress() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            Task t1 = new Task("1", "完成", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markCompleted("ok");
            Task t2 = new Task("2", "未完成", Task.Type.COMMAND, List.of());

            plan.addTask(t1);
            plan.addTask(t2);

            assertEquals(0.5, plan.getProgress(), 0.001);
        }

        @Test
        @DisplayName("getProgress：空计划 → 1.0")
        void emptyPlanProgress() {
            ExecutionPlan plan = new ExecutionPlan("空");
            assertEquals(1.0, plan.getProgress(), 0.001);
        }

        @Test
        @DisplayName("getProgress：SKIPPED 不算完成")
        void skippedNotCountedAsCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            Task t1 = new Task("1", "完成", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markCompleted("ok");
            Task t2 = new Task("2", "失败", Task.Type.COMMAND, List.of());
            t2.markStarted();
            t2.markFailed("err");
            Task t3 = new Task("3", "跳过", Task.Type.COMMAND, List.of());
            t3.markSkipped();

            plan.addTask(t1);
            plan.addTask(t2);
            plan.addTask(t3);

            // 只有 1/3 完成
            assertEquals(1.0 / 3.0, plan.getProgress(), 0.001);
        }
    }

    // ==================== getRootTasks ====================

    @Test
    @DisplayName("getRootTasks：返回所有无依赖的任务")
    void getRootTasks() {
        ExecutionPlan plan = new ExecutionPlan("测试");
        plan.addTask(new Task("1", "根A", Task.Type.COMMAND, List.of()));
        plan.addTask(new Task("2", "根B", Task.Type.COMMAND, List.of()));
        plan.addTask(new Task("3", "子", Task.Type.ANALYSIS, List.of("1", "2")));

        List<Task> roots = plan.getRootTasks();
        assertEquals(2, roots.size());
    }

    // ==================== 两遍扫描解析 ====================

    @Nested
    @DisplayName("parse 两遍扫描解析")
    class ParseTests {

        @Test
        @DisplayName("正常 JSON → 解析成功")
        void normalParse() throws Exception {
            String json = """
                    {
                      "tasks": [
                        {"id": "1", "description": "创建项目", "type": "COMMAND", "dependencies": []},
                        {"id": "2", "description": "写代码", "type": "FILE_WRITE", "dependencies": ["1"]},
                        {"id": "3", "description": "验证", "type": "VERIFICATION", "dependencies": ["1", "2"]}
                      ]
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("创建项目", json);

            assertEquals(3, plan.getTasks().size());
            assertEquals("创建项目", plan.getGoal());
            assertNotNull(plan.getExecutionOrder());

            // 验证双向边
            Task t1 = plan.getTask("1");
            assertTrue(t1.getDependents().contains("2"));
            assertTrue(t1.getDependents().contains("3"));
        }

        @Test
        @DisplayName("乱序任务：Task 3 排在 Task 1 前面 → 仍能正确解析")
        void outOfOrderTasks() throws Exception {
            String json = """
                    {
                      "tasks": [
                        {"id": "3", "description": "验证", "type": "VERIFICATION", "dependencies": ["1", "2"]},
                        {"id": "1", "description": "创建项目", "type": "COMMAND", "dependencies": []},
                        {"id": "2", "description": "写代码", "type": "FILE_WRITE", "dependencies": ["1"]}
                      ]
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("乱序", json);

            assertEquals(3, plan.getTasks().size());
            // 两遍扫描应该正确处理前向引用
            Task t3 = plan.getTask("3");
            assertEquals(2, t3.getDependencies().size());
        }

        @Test
        @DisplayName("未知依赖 ID → 被忽略（不崩溃）")
        void unknownDependency() throws Exception {
            String json = """
                    {
                      "tasks": [
                        {"id": "1", "description": "任务1", "type": "COMMAND", "dependencies": ["99"]},
                        {"id": "2", "description": "任务2", "type": "COMMAND", "dependencies": ["1"]}
                      ]
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("未知依赖", json);

            // Task 1 的依赖 "99" 不在 JSON 中，应被忽略
            Task t1 = plan.getTask("1");
            assertTrue(t1.getDependencies().isEmpty(), "未知依赖 ID 应被过滤");
        }

        @Test
        @DisplayName("无效 type → 回退为 COMMAND")
        void invalidType() throws Exception {
            String json = """
                    {
                      "tasks": [
                        {"id": "1", "description": "任务", "type": "INVALID_TYPE", "dependencies": []}
                      ]
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("无效类型", json);
            assertEquals(Task.Type.COMMAND, plan.getTask("1").getType());
        }

        @Test
        @DisplayName("缺失 type → 默认为 COMMAND")
        void missingType() throws Exception {
            String json = """
                    {
                      "tasks": [
                        {"id": "1", "description": "任务", "dependencies": []}
                      ]
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("缺失类型", json);
            assertEquals(Task.Type.COMMAND, plan.getTask("1").getType());
        }

        @Test
        @DisplayName("循环依赖 JSON → 抛出 IllegalStateException")
        void cycleDetection() {
            String json = """
                    {
                      "tasks": [
                        {"id": "1", "description": "A", "type": "COMMAND", "dependencies": ["2"]},
                        {"id": "2", "description": "B", "type": "COMMAND", "dependencies": ["1"]}
                      ]
                    }
                    """;

            assertThrows(IllegalStateException.class, () ->
                    ExecutionPlan.parse("循环", json));
        }

        @Test
        @DisplayName("空任务列表 → 空计划")
        void emptyTasks() throws Exception {
            String json = """
                    {
                      "tasks": []
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("空", json);
            assertTrue(plan.getTasks().isEmpty());
        }

        @Test
        @DisplayName("单任务无依赖 → 解析成功")
        void singleTask() throws Exception {
            String json = """
                    {
                      "tasks": [
                        {"id": "1", "description": "唯一任务", "type": "ANALYSIS", "dependencies": []}
                      ]
                    }
                    """;

            ExecutionPlan plan = ExecutionPlan.parse("单任务", json);
            assertEquals(1, plan.getTasks().size());
            assertEquals(Task.Type.ANALYSIS, plan.getTask("1").getType());
        }
    }

    // ==================== visualize ====================

    @Test
    @DisplayName("visualize 输出包含目标和任务信息")
    void visualizeContainsInfo() {
        ExecutionPlan plan = new ExecutionPlan("创建 Spring Boot 项目");
        plan.addTask(new Task("1", "创建目录", Task.Type.COMMAND, List.of()));
        plan.addTask(new Task("2", "写代码", Task.Type.FILE_WRITE, List.of("1")));

        String viz = plan.visualize();

        assertTrue(viz.contains("创建 Spring Boot 项目"));
        assertTrue(viz.contains("创建目录"));
        assertTrue(viz.contains("写代码"));
        assertTrue(viz.contains("Batch 1"));
    }

    // ==================== id 字段与构造函数 ====================

    @Nested
    @DisplayName("id 字段与构造函数")
    class IdAndConstructorTests {

        @Test
        @DisplayName("单参构造 → 自动生成 UUID 作为 ID")
        void singleArgConstructorGeneratesId() {
            ExecutionPlan plan = new ExecutionPlan("测试目标");

            assertNotNull(plan.getId());
            assertFalse(plan.getId().isEmpty());
            assertEquals("测试目标", plan.getGoal());
        }

        @Test
        @DisplayName("双参构造 → 使用指定的 ID")
        void twoArgConstructor() {
            ExecutionPlan plan = new ExecutionPlan("plan-001", "测试目标");

            assertEquals("plan-001", plan.getId());
            assertEquals("测试目标", plan.getGoal());
        }

        @Test
        @DisplayName("两个单参构造的 plan → ID 不同")
        void uniqueAutoIds() {
            ExecutionPlan p1 = new ExecutionPlan("目标A");
            ExecutionPlan p2 = new ExecutionPlan("目标B");

            assertNotEquals(p1.getId(), p2.getId());
        }
    }

    // ==================== CANCELLED 状态 ====================

    @Test
    @DisplayName("CANCELLED 状态可以设置和查询")
    void cancelledStatus() {
        ExecutionPlan plan = new ExecutionPlan("测试");
        plan.setStatus(ExecutionPlan.PlanStatus.CANCELLED);

        assertEquals(ExecutionPlan.PlanStatus.CANCELLED, plan.getStatus());
    }

    // ==================== 计划级生命周期（计时） ====================

    @Nested
    @DisplayName("计划级生命周期")
    class PlanLifecycleTests {

        @Test
        @DisplayName("markStarted → 状态变 RUNNING + startTime 被记录")
        void markStarted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            assertEquals(0, plan.getStartTime());

            plan.markStarted();

            assertEquals(ExecutionPlan.PlanStatus.RUNNING, plan.getStatus());
            assertTrue(plan.getStartTime() > 0);
        }

        @Test
        @DisplayName("markCompleted → 状态变 COMPLETED + endTime 被记录")
        void markCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.markStarted();
            plan.markCompleted();

            assertEquals(ExecutionPlan.PlanStatus.COMPLETED, plan.getStatus());
            assertTrue(plan.getEndTime() > 0);
            assertTrue(plan.getEndTime() >= plan.getStartTime());
        }

        @Test
        @DisplayName("markFailed → 状态变 FAILED + endTime 被记录")
        void markFailed() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.markStarted();
            plan.markFailed();

            assertEquals(ExecutionPlan.PlanStatus.FAILED, plan.getStatus());
            assertTrue(plan.getEndTime() > 0);
        }

        @Test
        @DisplayName("getDuration：未开始 → 返回 0")
        void durationNotStarted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            assertEquals(0, plan.getDuration());
        }

        @Test
        @DisplayName("getDuration：已完成 → 返回正数")
        void durationCompleted() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.markStarted();
            plan.markCompleted();

            assertTrue(plan.getDuration() >= 0);
        }

        @Test
        @DisplayName("getDuration：执行中 → 返回实时耗时")
        void durationWhileRunning() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.markStarted();

            // 执行中，endTime 为 0 → 用当前时间计算
            assertTrue(plan.getDuration() >= 0);
        }
    }

    // ==================== summarize() ====================

    @Nested
    @DisplayName("summarize 折叠摘要")
    class SummarizeTests {

        @Test
        @DisplayName("摘要包含关键统计信息")
        void summarizeContainsStats() {
            ExecutionPlan plan = new ExecutionPlan("创建一个复杂的项目并且编写单元测试");
            plan.addTask(new Task("1", "初始化", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "写代码", Task.Type.FILE_WRITE, List.of("1")));
            plan.addTask(new Task("3", "验证", Task.Type.VERIFICATION, List.of("2")));

            String summary = plan.summarize();

            assertTrue(summary.contains("计划摘要"));
            assertTrue(summary.contains("任务数: 3"));
            assertTrue(summary.contains("首批执行"));
            assertTrue(summary.contains("最终收敛"));
        }

        @Test
        @DisplayName("单批次计划 → 不显示'最终收敛'")
        void singleBatchNoConvergence() {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "任务A", Task.Type.COMMAND, List.of()));
            plan.addTask(new Task("2", "任务B", Task.Type.COMMAND, List.of()));

            String summary = plan.summarize();

            assertTrue(summary.contains("首批执行"));
            assertFalse(summary.contains("最终收敛"));
        }

        @Test
        @DisplayName("摘要包含耗时（已完成计划）")
        void summarizeWithDuration() throws InterruptedException {
            ExecutionPlan plan = new ExecutionPlan("测试");
            plan.addTask(new Task("1", "任务", Task.Type.COMMAND, List.of()));
            plan.markStarted();
            Thread.sleep(10);  // 确保耗时 > 0ms
            plan.markCompleted();

            String summary = plan.summarize();
            assertTrue(summary.contains("耗时"));
        }
    }

    // ==================== getExecutionOrder 懒加载 ====================

    @Test
    @DisplayName("getExecutionOrder 懒加载：不手动调 computeExecutionOrder 也能获取顺序")
    void lazyExecutionOrder() {
        ExecutionPlan plan = new ExecutionPlan("测试");
        plan.addTask(new Task("1", "A", Task.Type.COMMAND, List.of()));
        plan.addTask(new Task("2", "B", Task.Type.COMMAND, List.of("1")));

        // 不主动调 computeExecutionOrder()，直接获取
        List<String> order = plan.getExecutionOrder();

        assertNotNull(order);
        assertEquals(2, order.size());
        assertTrue(order.indexOf("1") < order.indexOf("2"));
    }

    // ==================== toString ====================

    @Test
    @DisplayName("toString 包含 id、goal、任务数、状态")
    void toStringFormat() {
        ExecutionPlan plan = new ExecutionPlan("test-123", "创建项目");
        plan.addTask(new Task("1", "步骤1", Task.Type.COMMAND, List.of()));

        String str = plan.toString();

        assertTrue(str.contains("test-123"));
        assertTrue(str.contains("创建项目"));
        assertTrue(str.contains("1 tasks"));
        assertTrue(str.contains("CREATED"));
    }

    // ==================== compactGoal（通过 visualize 间接测试） ====================

    @Test
    @DisplayName("超长 goal → visualize 自动截断（不撑爆输出）")
    void longGoalTruncated() {
        String longGoal = "A".repeat(200);
        ExecutionPlan plan = new ExecutionPlan(longGoal);
        plan.addTask(new Task("1", "任务", Task.Type.COMMAND, List.of()));

        String viz = plan.visualize();

        // 截断后不应包含完整的 200 个 A
        assertFalse(viz.contains("A".repeat(100)));
        assertTrue(viz.contains("..."));
    }

    @Test
    @DisplayName("多行 goal → visualize 压成单行")
    void multilineGoalCompacted() {
        String multilineGoal = "第一行\n第二行\n第三行";
        ExecutionPlan plan = new ExecutionPlan(multilineGoal);
        plan.addTask(new Task("1", "任务", Task.Type.COMMAND, List.of()));

        String viz = plan.visualize();

        // 应该压成单行，不含换行符
        assertFalse(viz.contains("第一行\n第二行"));
    }

    // ==================== summary 字段 ====================

    @Test
    @DisplayName("summary 字段的 get/set")
    void summaryField() {
        ExecutionPlan plan = new ExecutionPlan("测试");
        assertNull(plan.getSummary());

        plan.setSummary("这是一个测试计划");
        assertEquals("这是一个测试计划", plan.getSummary());
    }
}
