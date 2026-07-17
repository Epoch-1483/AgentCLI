package edu.cqie.paiclidemo.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentMessage 单元测试。
 * <p>
 * 覆盖 6 个静态工厂方法和 record 访问器。
 */
class AgentMessageTest {

    // ==================== 工厂方法测试 ====================

    @Nested
    @DisplayName("静态工厂方法")
    class FactoryTests {

        @Test
        @DisplayName("task() 创建 TASK 消息，fromRole 为 null")
        void taskFactory() {
            AgentMessage msg = AgentMessage.task("orchestrator", "请拆解任务");

            assertEquals("orchestrator", msg.fromAgent());
            assertNull(msg.fromRole());
            assertEquals("请拆解任务", msg.content());
            assertEquals(AgentMessage.Type.TASK, msg.type());
        }

        @Test
        @DisplayName("result() 创建 RESULT 消息，携带发送者角色")
        void resultFactory() {
            AgentMessage msg = AgentMessage.result("worker-1", AgentRole.WORKER, "计算完成：42");

            assertEquals("worker-1", msg.fromAgent());
            assertEquals(AgentRole.WORKER, msg.fromRole());
            assertEquals("计算完成：42", msg.content());
            assertEquals(AgentMessage.Type.RESULT, msg.type());
        }

        @Test
        @DisplayName("feedback() 创建 FEEDBACK 消息，角色固定为 REVIEWER")
        void feedbackFactory() {
            AgentMessage msg = AgentMessage.feedback("reviewer", "结果需要补充边界条件");

            assertEquals("reviewer", msg.fromAgent());
            assertEquals(AgentRole.REVIEWER, msg.fromRole());
            assertEquals("结果需要补充边界条件", msg.content());
            assertEquals(AgentMessage.Type.FEEDBACK, msg.type());
        }

        @Test
        @DisplayName("approval() 创建 APPROVAL 消息")
        void approvalFactory() {
            AgentMessage msg = AgentMessage.approval("reviewer", "结果正确");

            assertEquals("reviewer", msg.fromAgent());
            assertEquals(AgentRole.REVIEWER, msg.fromRole());
            assertEquals(AgentMessage.Type.APPROVAL, msg.type());
        }

        @Test
        @DisplayName("rejection() 创建 REJECTION 消息")
        void rejectionFactory() {
            AgentMessage msg = AgentMessage.rejection("reviewer", "计算结果有误");

            assertEquals("reviewer", msg.fromAgent());
            assertEquals(AgentRole.REVIEWER, msg.fromRole());
            assertEquals("计算结果有误", msg.content());
            assertEquals(AgentMessage.Type.REJECTION, msg.type());
        }

        @Test
        @DisplayName("error() 创建 ERROR 消息，携带发送者角色")
        void errorFactory() {
            AgentMessage msg = AgentMessage.error("worker-2", AgentRole.WORKER, "LLM 调用失败");

            assertEquals("worker-2", msg.fromAgent());
            assertEquals(AgentRole.WORKER, msg.fromRole());
            assertEquals("LLM 调用失败", msg.content());
            assertEquals(AgentMessage.Type.ERROR, msg.type());
        }
    }

    // ==================== Type 枚举测试 ====================

    @Nested
    @DisplayName("Type 枚举")
    class TypeTests {

        @Test
        @DisplayName("6 种消息类型都存在")
        void sixTypesExist() {
            AgentMessage.Type[] types = AgentMessage.Type.values();
            assertEquals(6, types.length);
        }

        @Test
        @DisplayName("valueOf 能正确反序列化")
        void valueOfWorks() {
            assertEquals(AgentMessage.Type.TASK, AgentMessage.Type.valueOf("TASK"));
            assertEquals(AgentMessage.Type.RESULT, AgentMessage.Type.valueOf("RESULT"));
            assertEquals(AgentMessage.Type.FEEDBACK, AgentMessage.Type.valueOf("FEEDBACK"));
            assertEquals(AgentMessage.Type.APPROVAL, AgentMessage.Type.valueOf("APPROVAL"));
            assertEquals(AgentMessage.Type.REJECTION, AgentMessage.Type.valueOf("REJECTION"));
            assertEquals(AgentMessage.Type.ERROR, AgentMessage.Type.valueOf("ERROR"));
        }
    }

    // ==================== Record 特性测试 ====================

    @Nested
    @DisplayName("Record 特性")
    class RecordTests {

        @Test
        @DisplayName("相同字段的两个消息 equals 为 true")
        void equalMessages() {
            AgentMessage a = AgentMessage.task("orch", "任务");
            AgentMessage b = AgentMessage.task("orch", "任务");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("不同内容的两个消息 equals 为 false")
        void unequalMessages() {
            AgentMessage a = AgentMessage.task("orch", "任务 A");
            AgentMessage b = AgentMessage.task("orch", "任务 B");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("toString 包含所有字段")
        void toStringContainsFields() {
            AgentMessage msg = AgentMessage.result("w1", AgentRole.WORKER, "done");
            String str = msg.toString();
            assertTrue(str.contains("w1"));
            assertTrue(str.contains("WORKER"));
            assertTrue(str.contains("done"));
            assertTrue(str.contains("RESULT"));
        }
    }
}
