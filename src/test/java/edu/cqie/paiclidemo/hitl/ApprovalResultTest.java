package edu.cqie.paiclidemo.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ApprovalResult} 单元测试。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
@DisplayName("ApprovalResult - 审批结果")
class ApprovalResultTest {

    // ==================== 工厂方法 ====================

    @Nested
    @DisplayName("工厂方法")
    class FactoryTests {

        @Test
        @DisplayName("approve() 创建 APPROVED 决策")
        void approve() {
            ApprovalResult r = ApprovalResult.approve();
            assertEquals(ApprovalResult.Decision.APPROVED, r.decision());
            assertNull(r.reason());
        }

        @Test
        @DisplayName("approveAll() 创建 APPROVED_ALL 决策")
        void approveAll() {
            ApprovalResult r = ApprovalResult.approveAll();
            assertEquals(ApprovalResult.Decision.APPROVED_ALL, r.decision());
            assertNull(r.reason());
        }

        @Test
        @DisplayName("reject() 创建 REJECTED 决策并携带原因")
        void reject() {
            ApprovalResult r = ApprovalResult.reject("太危险了");
            assertEquals(ApprovalResult.Decision.REJECTED, r.decision());
            assertEquals("太危险了", r.reason());
        }

        @Test
        @DisplayName("skip() 创建 SKIPPED 决策")
        void skip() {
            ApprovalResult r = ApprovalResult.skip();
            assertEquals(ApprovalResult.Decision.SKIPPED, r.decision());
            assertNull(r.reason());
        }
    }

    // ==================== 便捷查询 ====================

    @Nested
    @DisplayName("isApproved - 批准判断")
    class IsApprovedTests {

        @Test
        @DisplayName("APPROVED 返回 true")
        void approved() {
            assertTrue(ApprovalResult.approve().isApproved());
        }

        @Test
        @DisplayName("APPROVED_ALL 也返回 true")
        void approvedAll() {
            assertTrue(ApprovalResult.approveAll().isApproved());
        }

        @Test
        @DisplayName("REJECTED 返回 false")
        void rejected() {
            assertFalse(ApprovalResult.reject("no").isApproved());
        }

        @Test
        @DisplayName("SKIPPED 返回 false")
        void skipped() {
            assertFalse(ApprovalResult.skip().isApproved());
        }
    }

    @Nested
    @DisplayName("其他查询方法")
    class OtherQueryTests {

        @Test
        @DisplayName("isApprovedAll 只对 APPROVED_ALL 返回 true")
        void isApprovedAll() {
            assertTrue(ApprovalResult.approveAll().isApprovedAll());
            assertFalse(ApprovalResult.approve().isApprovedAll());
            assertFalse(ApprovalResult.reject("x").isApprovedAll());
        }

        @Test
        @DisplayName("isRejected 只对 REJECTED 返回 true")
        void isRejected() {
            assertTrue(ApprovalResult.reject("x").isRejected());
            assertFalse(ApprovalResult.approve().isRejected());
            assertFalse(ApprovalResult.skip().isRejected());
        }

        @Test
        @DisplayName("isSkipped 只对 SKIPPED 返回 true")
        void isSkipped() {
            assertTrue(ApprovalResult.skip().isSkipped());
            assertFalse(ApprovalResult.approve().isSkipped());
            assertFalse(ApprovalResult.reject("x").isSkipped());
        }
    }

    // ==================== Record 特性 ====================

    @Test
    @DisplayName("record 相等性：相同工厂创建的结果相等")
    void recordEquality() {
        assertEquals(ApprovalResult.approve(), ApprovalResult.approve());
        assertEquals(ApprovalResult.reject("same"), ApprovalResult.reject("same"));
        assertNotEquals(ApprovalResult.reject("a"), ApprovalResult.reject("b"));
    }
}
