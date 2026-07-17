package edu.cqie.paiclidemo.hitl;

import edu.cqie.paiclidemo.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.Map;

import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HitlToolRegistry} 单元测试。
 * <p>
 * 通过注入自定义 HitlHandler 验证工具执行拦截逻辑。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
@DisplayName("HitlToolRegistry - HITL 工具注册表")
class HitlToolRegistryTest {

    private HitlToolRegistry registry;
    private StubHitlHandler stubHandler;

    @BeforeEach
    void setUp() {
        stubHandler = new StubHitlHandler();
        registry = new HitlToolRegistry(stubHandler);

        // 注册一个安全工具
        registry.register(
                "calculator",
                "计算器",
                createParameters(new Param("expression", "string", "表达式")),
                args -> "结果: " + args.get("expression")
        );

        // 注册一个危险工具（模拟 write_file）
        registry.register(
                "write_file",
                "写文件",
                createParameters(new Param("path", "string", "路径")),
                args -> "写入成功: " + args.get("path")
        );

        // 注册一个模拟 execute_command
        registry.register(
                "execute_command",
                "执行命令",
                createParameters(new Param("command", "string", "命令")),
                args -> "执行完成: " + args.get("command")
        );
    }

    /** 创建 ToolCall */
    private LlmClient.ToolCall toolCall(String name, String argsJson) {
        return new LlmClient.ToolCall("tc_1",
                new LlmClient.ToolCall.Function(name, argsJson));
    }

    // ==================== HITL 关闭时直接执行 ====================

    @Nested
    @DisplayName("HITL 关闭时")
    class DisabledTests {

        @BeforeEach
        void disableHitl() {
            stubHandler.setEnabled(false);
        }

        @Test
        @DisplayName("危险工具直接执行")
        void dangerousToolExecutes() {
            String result = registry.executeTool(toolCall("write_file", "{\"path\": \"/test\"}"));
            assertTrue(result.contains("写入成功"));
        }

        @Test
        @DisplayName("安全工具直接执行")
        void safeToolExecutes() {
            String result = registry.executeTool(toolCall("calculator", "{\"expression\": \"1+1\"}"));
            assertTrue(result.contains("结果: 1+1"));
        }
    }

    // ==================== HITL 启用时 ====================

    @Nested
    @DisplayName("HITL 启用时")
    class EnabledTests {

        @BeforeEach
        void enableHitl() {
            stubHandler.setEnabled(true);
        }

        @Test
        @DisplayName("安全工具不触发审批，直接执行")
        void safeToolNoApproval() {
            String result = registry.executeTool(toolCall("calculator", "{\"expression\": \"2+2\"}"));
            assertTrue(result.contains("结果: 2+2"));
            assertEquals(0, stubHandler.approvalCount);
        }

        @Test
        @DisplayName("危险工具触发审批 → APPROVED → 执行")
        void dangerousToolApproved() {
            stubHandler.nextResult = ApprovalResult.approve();
            String result = registry.executeTool(toolCall("write_file", "{\"path\": \"/tmp/test\"}"));
            assertTrue(result.contains("写入成功"));
            assertEquals(1, stubHandler.approvalCount);
        }

        @Test
        @DisplayName("危险工具触发审批 → REJECTED → 返回拒绝消息")
        void dangerousToolRejected() {
            stubHandler.nextResult = ApprovalResult.reject("不安全");
            String result = registry.executeTool(toolCall("execute_command", "{\"command\": \"rm -rf /\"}"));
            assertTrue(result.contains("拒绝"));
            assertTrue(result.contains("不安全"));
        }

        @Test
        @DisplayName("危险工具触发审批 → SKIPPED → 返回跳过消息")
        void dangerousToolSkipped() {
            stubHandler.nextResult = ApprovalResult.skip();
            String result = registry.executeTool(toolCall("write_file", "{\"path\": \"/test\"}"));
            assertTrue(result.contains("跳过"));
        }

        @Test
        @DisplayName("危险工具触发审批 → APPROVED_ALL → 执行 + 后续同类不再弹审批")
        void approvedAllCaches() {
            stubHandler.nextResult = ApprovalResult.approveAll();

            // 第一次：弹审批
            String result1 = registry.executeTool(toolCall("write_file", "{\"path\": \"/a\"}"));
            assertTrue(result1.contains("写入成功"));
            assertEquals(1, stubHandler.approvalCount);

            // 第二次：缓存命中，不再弹审批
            String result2 = registry.executeTool(toolCall("write_file", "{\"path\": \"/b\"}"));
            assertTrue(result2.contains("写入成功"));
            assertEquals(1, stubHandler.approvalCount); // 仍然 1
        }

        @Test
        @DisplayName("APPROVED_ALL 不影响其他工具的审批")
        void approvedAllToolSpecific() {
            stubHandler.nextResult = ApprovalResult.approveAll();

            // write_file 获得全部批准
            registry.executeTool(toolCall("write_file", "{\"path\": \"/a\"}"));
            assertEquals(1, stubHandler.approvalCount);

            // execute_command 仍需审批
            stubHandler.nextResult = ApprovalResult.approve();
            registry.executeTool(toolCall("execute_command", "{\"command\": \"ls\"}"));
            assertEquals(2, stubHandler.approvalCount);
        }

        @Test
        @DisplayName("未注册工具返回未找到提示")
        void unknownTool() {
            stubHandler.nextResult = ApprovalResult.approve();
            String result = registry.executeTool(toolCall("unknown_tool", "{}"));
            assertTrue(result.contains("未找到"));
        }
    }

    // ==================== MCP 工具 ====================

    @Nested
    @DisplayName("MCP 工具处理")
    class McpTests {

        @Test
        @DisplayName("MCP 工具触发审批")
        void mcpToolTriggersApproval() {
            stubHandler.setEnabled(true);
            stubHandler.nextResult = ApprovalResult.reject("MCP 不允许");

            // MCP 工具未在 registry 注册，但审批检查在注册检查之前
            // 实际上 HitlToolRegistry.executeTool 先检查 HITL 状态和审批策略，
            // 然后调用 super.executeTool() 处理注册逻辑
            String result = registry.executeTool(
                    toolCall("mcp__browser__navigate", "{\"url\": \"https://example.com\"}"));
            // MCP 工具不在 registry 中，super.executeTool 返回"未找到"
            // 但由于被拒绝，实际返回拒绝消息
            assertTrue(result.contains("拒绝"));
        }
    }

    // ==================== StubHitlHandler ====================

    /** 可控的 HitlHandler 测试桩 */
    private static class StubHitlHandler implements HitlHandler {
        volatile boolean enabled = true;
        ApprovalResult nextResult = ApprovalResult.approve();
        int approvalCount = 0;
        private final java.util.Set<String> approvedAllTools =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            approvalCount++;
            if (nextResult.isApprovedAll()) {
                approvedAllTools.add(request.toolName());
            }
            return nextResult;
        }

        @Override
        public boolean isEnabled() { return enabled; }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (!enabled) clearApprovedAll();
        }

        @Override
        public boolean isApprovedAll(String toolName) {
            return approvedAllTools.contains(toolName);
        }

        @Override
        public void clearApprovedAll() {
            approvedAllTools.clear();
        }
    }
}
