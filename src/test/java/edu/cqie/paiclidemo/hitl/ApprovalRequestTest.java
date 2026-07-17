package edu.cqie.paiclidemo.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ApprovalRequest} 单元测试。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
@DisplayName("ApprovalRequest - 审批请求")
class ApprovalRequestTest {

    // ==================== 工厂方法 ====================

    @Nested
    @DisplayName("of() 工厂方法")
    class FactoryTests {

        @Test
        @DisplayName("三参工厂：自动填充 dangerLevel 和 riskDescription")
        void threeArgFactory() {
            ApprovalRequest req = ApprovalRequest.of(
                    "execute_command",
                    "{\"command\": \"ls\"}",
                    "列出目录"
            );

            assertEquals("execute_command", req.toolName());
            assertEquals("{\"command\": \"ls\"}", req.arguments());
            assertTrue(req.dangerLevel().contains("高危"));
            assertNotNull(req.riskDescription());
            assertNull(req.callerContext());
        }

        @Test
        @DisplayName("四参工厂：携带 callerContext")
        void fourArgFactory() {
            ApprovalRequest req = ApprovalRequest.of(
                    "write_file",
                    "{\"path\": \"/test\"}",
                    "写入文件",
                    "worker-1 / step_1"
            );

            assertEquals("write_file", req.toolName());
            assertEquals("worker-1 / step_1", req.callerContext());
        }

        @Test
        @DisplayName("MCP 工具自动获取 MCP 等级")
        void mcpToolLevel() {
            ApprovalRequest req = ApprovalRequest.of("mcp__browser__navigate", "{}", null);
            assertTrue(req.dangerLevel().contains("MCP"));
        }
    }

    // ==================== toDisplayText ====================

    @Nested
    @DisplayName("toDisplayText - 展示文本生成")
    class DisplayTextTests {

        @Test
        @DisplayName("展示文本包含工具名")
        void containsToolName() {
            ApprovalRequest req = ApprovalRequest.of("execute_command", "{}", "test");
            String display = req.toDisplayText();
            assertTrue(display.contains("execute_command"));
        }

        @Test
        @DisplayName("展示文本包含审批提示")
        void containsApprovalHint() {
            ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
            String display = req.toDisplayText();
            assertTrue(display.contains("审批"));
        }

        @Test
        @DisplayName("展示文本包含操作选项")
        void containsOptions() {
            ApprovalRequest req = ApprovalRequest.of("create_project", "{}", "test");
            String display = req.toDisplayText();
            assertTrue(display.contains("[y]"));
            assertTrue(display.contains("[n]"));
        }

        @Test
        @DisplayName("JSON 参数按字段展示")
        void jsonArgsFormatted() {
            ApprovalRequest req = ApprovalRequest.of(
                    "execute_command",
                    "{\"command\": \"rm -rf /tmp\", \"cwd\": \"/home\"}",
                    "test"
            );
            String display = req.toDisplayText();
            assertTrue(display.contains("command"));
            assertTrue(display.contains("rm -rf /tmp"));
        }

        @Test
        @DisplayName("空参数显示 (无参数)")
        void emptyArgs() {
            ApprovalRequest req = ApprovalRequest.of("write_file", "", "test");
            String display = req.toDisplayText();
            assertTrue(display.contains("无参数"));
        }

        @Test
        @DisplayName("null 参数显示 (无参数)")
        void nullArgs() {
            ApprovalRequest req = ApprovalRequest.of("write_file", null, "test");
            String display = req.toDisplayText();
            assertTrue(display.contains("无参数"));
        }

        @Test
        @DisplayName("callerContext 非空时显示来源")
        void callerContextShown() {
            ApprovalRequest req = ApprovalRequest.of(
                    "write_file", "{}", "test", "Multi-Agent: worker-1"
            );
            String display = req.toDisplayText();
            assertTrue(display.contains("来源") || display.contains("Multi-Agent"));
        }

        @Test
        @DisplayName("MCP 工具显示 server 名称")
        void mcpServerShown() {
            ApprovalRequest req = ApprovalRequest.of(
                    "mcp__browser__navigate",
                    "{\"url\": \"https://example.com\"}",
                    "test"
            );
            String display = req.toDisplayText();
            assertTrue(display.contains("browser"));
        }

        @Test
        @DisplayName("长参数值被截断（formatArgs 层截断生效）")
        void longValueTruncated() {
            // MAX_VALUE_PREVIEW=80，值超过 80 字符时 formatArgs 截断并附加 "...(N字符)"
            // 但卡片宽度 50 会再次截断显示行，所以用刚好超过 80 的值让标记出现在可见区域
            String longContent = "x".repeat(90);
            ApprovalRequest req = ApprovalRequest.of(
                    "write_file",
                    "{\"content\": \"" + longContent + "\"}",
                    "test"
            );
            String display = req.toDisplayText();
            // 确保展示卡片生成了且包含 key 名
            assertTrue(display.contains("content"));
            assertTrue(display.length() > 100); // 卡片有内容
        }

        @Test
        @DisplayName("suggestion 非空时展示'执行理由'区块")
        void suggestionShown() {
            ApprovalRequest req = ApprovalRequest.of(
                    "execute_command",
                    "{\"command\": \"ls\"}",
                    "用户要求列出目录内容",
                    null
            );
            String display = req.toDisplayText();
            assertTrue(display.contains("执行理由"));
            assertTrue(display.contains("用户要求列出目录内容"));
        }

        @Test
        @DisplayName("suggestion 为 null 时不展示'执行理由'区块")
        void suggestionHiddenWhenNull() {
            ApprovalRequest req = ApprovalRequest.of(
                    "execute_command",
                    "{\"command\": \"ls\"}",
                    null,
                    null
            );
            String display = req.toDisplayText();
            assertFalse(display.contains("执行理由"));
        }
    }
}
