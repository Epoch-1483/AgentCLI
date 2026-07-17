package edu.cqie.paiclidemo.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ApprovalPolicy} 单元测试。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
@DisplayName("ApprovalPolicy - 危险操作识别策略")
class ApprovalPolicyTest {

    // ==================== requiresApproval ====================

    @Nested
    @DisplayName("requiresApproval - 审批判断")
    class RequiresApprovalTests {

        @Test
        @DisplayName("write_file 需要审批")
        void writeFileRequiresApproval() {
            assertTrue(ApprovalPolicy.requiresApproval("write_file"));
        }

        @Test
        @DisplayName("execute_command 需要审批")
        void executeCommandRequiresApproval() {
            assertTrue(ApprovalPolicy.requiresApproval("execute_command"));
        }

        @Test
        @DisplayName("create_project 需要审批")
        void createProjectRequiresApproval() {
            assertTrue(ApprovalPolicy.requiresApproval("create_project"));
        }

        @Test
        @DisplayName("MCP 工具需要审批")
        void mcpToolRequiresApproval() {
            assertTrue(ApprovalPolicy.requiresApproval("mcp__browser__navigate"));
            assertTrue(ApprovalPolicy.requiresApproval("mcp__slack__send_message"));
        }

        @Test
        @DisplayName("安全工具不需要审批")
        void safeToolsNoApproval() {
            assertFalse(ApprovalPolicy.requiresApproval("calculator"));
            assertFalse(ApprovalPolicy.requiresApproval("get_current_time"));
            assertFalse(ApprovalPolicy.requiresApproval("read_file"));
            assertFalse(ApprovalPolicy.requiresApproval("search_code"));
        }

        @Test
        @DisplayName("null 工具名不需要审批")
        void nullToolName() {
            assertFalse(ApprovalPolicy.requiresApproval(null));
        }
    }

    // ==================== isMcpTool ====================

    @Nested
    @DisplayName("isMcpTool - MCP 工具检测")
    class IsMcpToolTests {

        @Test
        @DisplayName("mcp__ 前缀识别为 MCP 工具")
        void mcpPrefix() {
            assertTrue(ApprovalPolicy.isMcpTool("mcp__server__tool"));
            assertTrue(ApprovalPolicy.isMcpTool("mcp__a__b"));
        }

        @Test
        @DisplayName("非 MCP 工具")
        void nonMcp() {
            assertFalse(ApprovalPolicy.isMcpTool("write_file"));
            assertFalse(ApprovalPolicy.isMcpTool("calculator"));
            assertFalse(ApprovalPolicy.isMcpTool(null));
        }
    }

    // ==================== mcpServerName ====================

    @Nested
    @DisplayName("mcpServerName - MCP server 名提取")
    class McpServerNameTests {

        @Test
        @DisplayName("正确提取 server 名")
        void extractServerName() {
            assertEquals("browser", ApprovalPolicy.mcpServerName("mcp__browser__navigate"));
            assertEquals("slack", ApprovalPolicy.mcpServerName("mcp__slack__send_message"));
        }

        @Test
        @DisplayName("非 MCP 工具返回 null")
        void nonMcpReturnsNull() {
            assertNull(ApprovalPolicy.mcpServerName("write_file"));
            assertNull(ApprovalPolicy.mcpServerName(null));
        }
    }

    // ==================== getDangerLevel ====================

    @Nested
    @DisplayName("getDangerLevel - 危险等级")
    class DangerLevelTests {

        @Test
        @DisplayName("execute_command 为高危")
        void executeCommandHighRisk() {
            assertTrue(ApprovalPolicy.getDangerLevel("execute_command").contains("高危"));
        }

        @Test
        @DisplayName("write_file 为中危")
        void writeFileMediumRisk() {
            assertTrue(ApprovalPolicy.getDangerLevel("write_file").contains("中危"));
        }

        @Test
        @DisplayName("MCP 工具等级包含 MCP")
        void mcpToolLevel() {
            assertTrue(ApprovalPolicy.getDangerLevel("mcp__a__b").contains("MCP"));
        }

        @Test
        @DisplayName("安全工具等级为安全")
        void safeToolLevel() {
            assertTrue(ApprovalPolicy.getDangerLevel("calculator").contains("安全"));
        }
    }

    // ==================== getRiskDescription ====================

    @Nested
    @DisplayName("getRiskDescription - 风险描述")
    class RiskDescriptionTests {

        @Test
        @DisplayName("execute_command 风险描述包含 Shell")
        void executeCommandRisk() {
            assertTrue(ApprovalPolicy.getRiskDescription("execute_command").contains("Shell"));
        }

        @Test
        @DisplayName("write_file 风险描述包含写入")
        void writeFileRisk() {
            assertTrue(ApprovalPolicy.getRiskDescription("write_file").contains("写入"));
        }

        @Test
        @DisplayName("MCP 工具风险描述包含外部")
        void mcpRisk() {
            assertTrue(ApprovalPolicy.getRiskDescription("mcp__x__y").contains("外部"));
        }

        @Test
        @DisplayName("安全工具描述为只读")
        void safeToolRisk() {
            assertTrue(ApprovalPolicy.getRiskDescription("calculator").contains("只读"));
        }
    }

    // ==================== getDangerousTools ====================

    @Test
    @DisplayName("getDangerousTools 返回 3 个工具")
    void dangerousToolsCount() {
        assertEquals(3, ApprovalPolicy.getDangerousTools().size());
        assertTrue(ApprovalPolicy.getDangerousTools().contains("write_file"));
        assertTrue(ApprovalPolicy.getDangerousTools().contains("execute_command"));
        assertTrue(ApprovalPolicy.getDangerousTools().contains("create_project"));
    }
}
