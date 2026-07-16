package edu.cqie.paiclidemo.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 单元测试 —— 覆盖状态机、isExecutable 判断、dependents 反向边等边界。
 */
class TaskTest {

    // ==================== 状态机生命周期 ====================

    @Nested
    @DisplayName("状态机生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("正常生命周期：PENDING → RUNNING → COMPLETED")
        void normalLifecycle() {
            Task task = new Task("1", "测试任务", Task.Type.COMMAND, List.of());

            assertEquals(Task.Status.PENDING, task.getStatus());
            assertNull(task.getResult());
            assertNull(task.getError());

            task.markStarted();
            assertEquals(Task.Status.RUNNING, task.getStatus());

            task.markCompleted("执行成功");
            assertEquals(Task.Status.COMPLETED, task.getStatus());
            assertEquals("执行成功", task.getResult());
            assertNull(task.getError());
        }

        @Test
        @DisplayName("失败生命周期：PENDING → RUNNING → FAILED")
        void failedLifecycle() {
            Task task = new Task("1", "测试任务", Task.Type.COMMAND, List.of());

            task.markStarted();
            task.markFailed("连接超时");

            assertEquals(Task.Status.FAILED, task.getStatus());
            assertNull(task.getResult());
            assertEquals("连接超时", task.getError());
        }

        @Test
        @DisplayName("跳过：PENDING → SKIPPED（无需经过 RUNNING）")
        void skippedLifecycle() {
            Task task = new Task("1", "测试任务", Task.Type.COMMAND, List.of());

            task.markSkipped();
            assertEquals(Task.Status.SKIPPED, task.getStatus());
            assertNull(task.getResult());
            assertNull(task.getError());
        }

        @Test
        @DisplayName("isTerminated：COMPLETED、FAILED、SKIPPED 都是终态")
        void isTerminated() {
            Task t1 = new Task("1", "完成", Task.Type.COMMAND, List.of());
            t1.markStarted();
            t1.markCompleted("done");

            Task t2 = new Task("2", "失败", Task.Type.COMMAND, List.of());
            t2.markStarted();
            t2.markFailed("err");

            Task t3 = new Task("3", "跳过", Task.Type.COMMAND, List.of());
            t3.markSkipped();

            Task t4 = new Task("4", "等待", Task.Type.COMMAND, List.of());
            Task t5 = new Task("5", "执行中", Task.Type.COMMAND, List.of());
            t5.markStarted();

            assertTrue(t1.isTerminated(), "COMPLETED 应为终态");
            assertTrue(t2.isTerminated(), "FAILED 应为终态");
            assertTrue(t3.isTerminated(), "SKIPPED 应为终态");
            assertFalse(t4.isTerminated(), "PENDING 不应为终态");
            assertFalse(t5.isTerminated(), "RUNNING 不应为终态");
        }
    }

    // ==================== isExecutable 边界测试 ====================

    @Nested
    @DisplayName("isExecutable 判断逻辑")
    class ExecutableTests {

        @Test
        @DisplayName("无依赖 + PENDING → 可执行")
        void noDeps_pending() {
            Task task = new Task("1", "根任务", Task.Type.COMMAND, List.of());
            Map<String, Task> map = Map.of("1", task);

            assertTrue(task.isExecutable(map));
        }

        @Test
        @DisplayName("无依赖 + RUNNING → 不可执行（非 PENDING）")
        void noDeps_running() {
            Task task = new Task("1", "根任务", Task.Type.COMMAND, List.of());
            task.markStarted();
            Map<String, Task> map = Map.of("1", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("无依赖 + COMPLETED → 不可执行（已完成）")
        void noDeps_completed() {
            Task task = new Task("1", "根任务", Task.Type.COMMAND, List.of());
            task.markStarted();
            task.markCompleted("done");
            Map<String, Task> map = Map.of("1", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("依赖已完成 → 可执行")
        void depCompleted() {
            Task dep = new Task("1", "前置任务", Task.Type.COMMAND, List.of());
            dep.markStarted();
            dep.markCompleted("done");

            Task task = new Task("2", "后续任务", Task.Type.ANALYSIS, List.of("1"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", dep);
            map.put("2", task);

            assertTrue(task.isExecutable(map));
        }

        @Test
        @DisplayName("依赖仍在 PENDING → 不可执行")
        void depPending() {
            Task dep = new Task("1", "前置任务", Task.Type.COMMAND, List.of());
            Task task = new Task("2", "后续任务", Task.Type.ANALYSIS, List.of("1"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", dep);
            map.put("2", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("依赖正在 RUNNING → 不可执行")
        void depRunning() {
            Task dep = new Task("1", "前置任务", Task.Type.COMMAND, List.of());
            dep.markStarted();

            Task task = new Task("2", "后续任务", Task.Type.ANALYSIS, List.of("1"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", dep);
            map.put("2", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("依赖 FAILED → 不可执行")
        void depFailed() {
            Task dep = new Task("1", "前置任务", Task.Type.COMMAND, List.of());
            dep.markStarted();
            dep.markFailed("出错");

            Task task = new Task("2", "后续任务", Task.Type.ANALYSIS, List.of("1"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", dep);
            map.put("2", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("依赖 SKIPPED → 不可执行")
        void depSkipped() {
            Task dep = new Task("1", "前置任务", Task.Type.COMMAND, List.of());
            dep.markSkipped();

            Task task = new Task("2", "后续任务", Task.Type.ANALYSIS, List.of("1"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", dep);
            map.put("2", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("多依赖：全部 COMPLETED → 可执行")
        void multiDepsAllCompleted() {
            Task d1 = new Task("1", "依赖1", Task.Type.COMMAND, List.of());
            d1.markStarted();
            d1.markCompleted("ok");

            Task d2 = new Task("2", "依赖2", Task.Type.COMMAND, List.of());
            d2.markStarted();
            d2.markCompleted("ok");

            Task task = new Task("3", "汇聚", Task.Type.ANALYSIS, List.of("1", "2"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", d1);
            map.put("2", d2);
            map.put("3", task);

            assertTrue(task.isExecutable(map));
        }

        @Test
        @DisplayName("多依赖：一个未完成 → 不可执行")
        void multiDepsOneIncomplete() {
            Task d1 = new Task("1", "依赖1", Task.Type.COMMAND, List.of());
            d1.markStarted();
            d1.markCompleted("ok");

            Task d2 = new Task("2", "依赖2", Task.Type.COMMAND, List.of());
            // d2 仍然 PENDING

            Task task = new Task("3", "汇聚", Task.Type.ANALYSIS, List.of("1", "2"));

            Map<String, Task> map = new HashMap<>();
            map.put("1", d1);
            map.put("2", d2);
            map.put("3", task);

            assertFalse(task.isExecutable(map));
        }

        @Test
        @DisplayName("边界：依赖 ID 不在 taskMap 中 → 不可执行（防御性检查）")
        void depNotInMap() {
            Task task = new Task("2", "后续", Task.Type.ANALYSIS, List.of("99"));
            Map<String, Task> map = new HashMap<>();
            map.put("2", task);

            assertFalse(task.isExecutable(map), "未知依赖 ID 应视为不可执行");
        }
    }

    // ==================== dependents 反向边 ====================

    @Nested
    @DisplayName("dependents 反向边")
    class DependentTests {

        @Test
        @DisplayName("添加 dependent")
        void addDependent() {
            Task task = new Task("1", "根任务", Task.Type.COMMAND, List.of());

            task.addDependent("2");
            task.addDependent("3");

            assertEquals(List.of("2", "3"), task.getDependents());
        }

        @Test
        @DisplayName("添加重复 dependent 应去重")
        void addDependentDedup() {
            Task task = new Task("1", "根任务", Task.Type.COMMAND, List.of());

            task.addDependent("2");
            task.addDependent("2");  // 重复
            task.addDependent("2");  // 再重复

            assertEquals(1, task.getDependents().size());
            assertEquals(List.of("2"), task.getDependents());
        }
    }

    // ==================== 构造函数边界 ====================

    @Nested
    @DisplayName("构造函数边界")
    class ConstructorTests {

        @Test
        @DisplayName("dependencies 为 null → 应处理为空列表")
        void nullDependencies() {
            Task task = new Task("1", "测试", Task.Type.COMMAND, null);

            assertNotNull(task.getDependencies());
            assertTrue(task.getDependencies().isEmpty());
        }

        @Test
        @DisplayName("初始状态检查")
        void initialState() {
            Task task = new Task("1", "测试任务", Task.Type.PLANNING, List.of("a", "b"));

            assertEquals("1", task.getId());
            assertEquals("测试任务", task.getDescription());
            assertEquals(Task.Type.PLANNING, task.getType());
            assertEquals(Task.Status.PENDING, task.getStatus());
            assertNull(task.getResult());
            assertNull(task.getError());
            assertEquals(List.of("a", "b"), task.getDependencies());
            assertTrue(task.getDependents().isEmpty());
        }
    }

    // ==================== elapsedMillis ====================

    @Nested
    @DisplayName("耗时计算")
    class ElapsedTests {

        @Test
        @DisplayName("未开始 → 返回 -1")
        void notStarted() {
            Task task = new Task("1", "测试", Task.Type.COMMAND, List.of());
            assertEquals(-1, task.elapsedMillis());
        }

        @Test
        @DisplayName("执行中 → 返回正值")
        void whileRunning() {
            Task task = new Task("1", "测试", Task.Type.COMMAND, List.of());
            task.markStarted();
            // markStarted 设了 startTime，endTime 为 null → 用 Instant.now() 计算
            assertTrue(task.elapsedMillis() >= 0);
        }

        @Test
        @DisplayName("已完成 → 返回正值")
        void afterCompleted() {
            Task task = new Task("1", "测试", Task.Type.COMMAND, List.of());
            task.markStarted();
            task.markCompleted("done");
            assertTrue(task.elapsedMillis() >= 0);
        }
    }

    // ==================== toString ====================

    @Test
    @DisplayName("toString 包含关键信息")
    void toStringContainsInfo() {
        Task task = new Task("42", "创建项目", Task.Type.COMMAND, List.of());
        String str = task.toString();

        assertTrue(str.contains("42"));
        assertTrue(str.contains("创建项目"));
        assertTrue(str.contains("COMMAND"));
        assertTrue(str.contains("PENDING"));
    }
}
