package edu.cqie.paiclidemo.hitl;

import java.util.Set;

/**
 * 危险操作识别策略 —— 基于静态规则判断哪些工具调用需要人工确认。
 * <p>
 * <b>设计原则</b>（与原版 paiCLI 一致）：
 * <ul>
 *   <li>读取类操作（calculator、get_current_time、search_code）不需要确认，无副作用</li>
 *   <li>写入/执行类操作（write_file、execute_command）需要确认，有潜在破坏性</li>
 *   <li>create_project 属于写入操作，默认需要确认</li>
 *   <li>MCP 工具来自外部 server，默认都需要确认</li>
 * </ul>
 * <p>
 * 采用静态规则而非 LLM 动态评估，因为"这不仅慢，而且不可靠"。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public class ApprovalPolicy {

    /** 需要人工确认的工具集合 */
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project"
    );

    private ApprovalPolicy() {}

    /**
     * 判断该工具调用是否需要人工确认。
     * <p>
     * 规则：工具在危险名单中，或者以 {@code mcp__} 开头（外部 MCP 工具）。
     *
     * @param toolName 工具名称
     * @return true = 需要人工审批，false = 安全放行
     */
    public static boolean requiresApproval(String toolName) {
        if (toolName == null) {
            return false;
        }
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    /**
     * 获取危险等级描述（带 emoji 标记）。
     *
     * @param toolName 工具名称
     * @return 等级文本：🔴 高危 / 🟠 中危 / 🔵 MCP / ✅ 安全
     */
    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "write_file" -> "🟠 中危";
            case "create_project" -> "🟠 中危";
            default -> isMcpTool(toolName) ? "🔵 MCP" : "✅ 安全";
        };
    }

    /**
     * 获取危险操作的风险说明。
     *
     * @param toolName 工具名称
     * @return 中文风险描述
     */
    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> isMcpTool(toolName)
                    ? "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务"
                    : "安全的只读操作";
        };
    }

    /**
     * 获取所有需要审批的工具名集合（用于测试和展示）。
     */
    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    /**
     * 判断是否为 MCP 工具（通过命名约定 {@code mcp__server__tool}）。
     */
    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    /**
     * 从 MCP 工具名中提取 server 名称。
     * <p>
     * 例如 {@code mcp__browser__navigate} → {@code "browser"}。
     * 非 MCP 工具返回 null。
     */
    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}
