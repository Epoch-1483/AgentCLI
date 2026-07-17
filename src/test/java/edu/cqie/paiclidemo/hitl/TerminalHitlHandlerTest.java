package edu.cqie.paiclidemo.hitl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TerminalHitlHandler} 单元测试。
 * <p>
 * 使用注入的 BufferedReader/PrintStream 模拟终端交互，
 * 避免测试时依赖 System.in。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
@DisplayName("TerminalHitlHandler - 终端 HITL 处理器")
class TerminalHitlHandlerTest {

    private ByteArrayOutputStream outputCapture;
    private PrintStream printStream;

    @BeforeEach
    void setUp() {
        outputCapture = new ByteArrayOutputStream();
        printStream = new PrintStream(outputCapture, true);
    }

    /** 创建带指定输入的 Handler */
    private TerminalHitlHandler handlerWithInput(String... lines) {
        String input = String.join("\n", lines) + "\n";
        BufferedReader reader = new BufferedReader(new StringReader(input));
        return new TerminalHitlHandler(reader, printStream);
    }

    /** 创建一个测试用审批请求 */
    private ApprovalRequest testRequest() {
        return ApprovalRequest.of("execute_command", "{\"command\": \"ls\"}", "测试请求");
    }

    // ==================== 用户决策 ====================

    @Nested
    @DisplayName("requestApproval - 用户决策处理")
    class DecisionTests {

        @Test
        @DisplayName("输入 y → APPROVED")
        void inputYes() {
            TerminalHitlHandler handler = handlerWithInput("y");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        }

        @Test
        @DisplayName("输入回车（空行）→ APPROVED")
        void inputEmpty() {
            TerminalHitlHandler handler = handlerWithInput("");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        }

        @Test
        @DisplayName("输入 a → APPROVED_ALL")
        void inputAll() {
            TerminalHitlHandler handler = handlerWithInput("a");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.APPROVED_ALL, result.decision());
        }

        @Test
        @DisplayName("输入 a 后 isApprovedAll 返回 true")
        void approvedAllCached() {
            TerminalHitlHandler handler = handlerWithInput("a");
            handler.requestApproval(testRequest());
            assertTrue(handler.isApprovedAll("execute_command"));
            assertFalse(handler.isApprovedAll("write_file"));
        }

        @Test
        @DisplayName("输入 n → REJECTED（带原因）")
        void inputNo() {
            TerminalHitlHandler handler = handlerWithInput("n", "太危险了");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
            assertEquals("太危险了", result.reason());
        }

        @Test
        @DisplayName("输入 n 后直接回车 → REJECTED（默认原因）")
        void inputNoEmptyReason() {
            TerminalHitlHandler handler = handlerWithInput("n", "");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
            assertEquals("用户手动拒绝", result.reason());
        }

        @Test
        @DisplayName("输入 s → SKIPPED")
        void inputSkip() {
            TerminalHitlHandler handler = handlerWithInput("s");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.SKIPPED, result.decision());
        }
    }

    // ==================== 无效输入处理 ====================

    @Nested
    @DisplayName("无效输入处理")
    class InvalidInputTests {

        @Test
        @DisplayName("无效输入后重新提示")
        void invalidThenValid() {
            TerminalHitlHandler handler = handlerWithInput("xyz", "y");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
            String output = outputCapture.toString();
            assertTrue(output.contains("无效输入"));
        }

        @Test
        @DisplayName("连续 3 次无效输入 → 自动拒绝")
        void threeInvalidAutoReject() {
            TerminalHitlHandler handler = handlerWithInput("abc", "def", "ghi");
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
            assertTrue(result.reason().contains("无效输入"));
        }

        @Test
        @DisplayName("输入 EOF → 自动拒绝")
        void eofAutoReject() {
            BufferedReader reader = new BufferedReader(new StringReader(""));
            TerminalHitlHandler handler = new TerminalHitlHandler(reader, printStream);
            ApprovalResult result = handler.requestApproval(testRequest());
            assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        }
    }

    // ==================== 启用/关闭 ====================

    @Nested
    @DisplayName("enabled 状态管理")
    class EnabledTests {

        @Test
        @DisplayName("默认启用")
        void defaultEnabled() {
            TerminalHitlHandler handler = handlerWithInput("y");
            assertTrue(handler.isEnabled());
        }

        @Test
        @DisplayName("setEnabled(false) 关闭")
        void disable() {
            TerminalHitlHandler handler = handlerWithInput("y");
            handler.setEnabled(false);
            assertFalse(handler.isEnabled());
        }

        @Test
        @DisplayName("关闭时清除全部批准缓存")
        void disableClearsCache() {
            TerminalHitlHandler handler = handlerWithInput("a");
            handler.requestApproval(testRequest());
            assertTrue(handler.isApprovedAll("execute_command"));

            handler.setEnabled(false);
            assertFalse(handler.isApprovedAll("execute_command"));
        }

        @Test
        @DisplayName("clearApprovedAll 手动清除缓存")
        void clearApprovedAll() {
            TerminalHitlHandler handler = handlerWithInput("a");
            handler.requestApproval(testRequest());
            assertTrue(handler.isApprovedAll("execute_command"));

            handler.clearApprovedAll();
            assertFalse(handler.isApprovedAll("execute_command"));
        }
    }

    // ==================== 展示输出 ====================

    @Test
    @DisplayName("审批卡片包含工具名")
    void displayCardContainsToolName() {
        TerminalHitlHandler handler = handlerWithInput("y");
        handler.requestApproval(testRequest());
        String output = outputCapture.toString();
        assertTrue(output.contains("execute_command"));
    }
}
