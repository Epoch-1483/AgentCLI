package edu.cqie.paiclidemo.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 审批请求 —— 描述一次待确认的工具调用。
 * <p>
 * 包含工具调用的完整信息，用于向用户展示"即将执行什么操作"。
 * <p>
 * {@code callerContext} 是为 Multi-Agent 预留的扩展字段，
 * 记录具体是哪个子智能体或执行步骤发起了危险调用。
 * 单 Agent 模式下默认传 null。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription,
        String suggestion,
        String callerContext
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_VALUE_PREVIEW = 80;

    /**
     * 最简工厂：只需工具名、参数和执行理由。
     * dangerLevel 和 riskDescription 自动从 {@link ApprovalPolicy} 获取。
     */
    public static ApprovalRequest of(String toolName, String arguments, String suggestion) {
        return of(toolName, arguments, suggestion, null);
    }

    /**
     * 带调用方上下文的工厂（Multi-Agent 场景）。
     */
    public static ApprovalRequest of(String toolName, String arguments,
                                     String suggestion, String callerContext) {
        return new ApprovalRequest(
                toolName,
                arguments,
                ApprovalPolicy.getDangerLevel(toolName),
                ApprovalPolicy.getRiskDescription(toolName),
                suggestion,
                callerContext
        );
    }

    /**
     * 格式化为可读的展示文本（终端审批卡片）。
     * <p>
     * 效果示例：
     * <pre>
     * ╔══════════════════════════════════════════╗
     * ║  ⚠️  需要人工审批                         ║
     * ╠══════════════════════════════════════════╣
     * ║  工具: execute_command                    ║
     * ║  等级: 🔴 高危                            ║
     * ║  风险: 将在系统上执行 Shell 命令...        ║
     * ╠══════════════════════════════════════════╣
     * ║  参数:                                    ║
     * ║    command: "rm -rf /tmp/test"            ║
     * ╠══════════════════════════════════════════╣
     * ║  执行理由:                                ║
     * ║    用户要求清理临时文件                    ║
     * ╚══════════════════════════════════════════╝
     * </pre>
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        int width = 50;
        String border = "═".repeat(width);

        sb.append("╔").append(border).append("╗\n");
        sb.append(padRightByDisplayWidth("║  ⚠️  需要人工审批", width)).append("║\n");
        sb.append("╠").append(border).append("╣\n");

        sb.append(formatField("工具", toolName, width)).append("\n");

        String mcpServer = ApprovalPolicy.mcpServerName(toolName);
        if (mcpServer != null) {
            sb.append(formatField("MCP", mcpServer, width)).append("\n");
        }

        sb.append(formatField("等级", dangerLevel, width)).append("\n");
        sb.append(formatField("风险", riskDescription, width)).append("\n");

        if (callerContext != null && !callerContext.isBlank()) {
            sb.append(formatField("来源", callerContext, width)).append("\n");
        }

        sb.append("╠").append(border).append("╣\n");
        sb.append(padRightByDisplayWidth("║  参数:", width)).append("║\n");

        List<String> argLines = formatArgs(arguments);
        for (String line : argLines) {
            sb.append("║    ").append(padRightByDisplayWidth(line, width - 4)).append("║\n");
        }

        // 执行理由（suggestion 非空时展示）
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append("╠").append(border).append("╣\n");
            sb.append(padRightByDisplayWidth("║  执行理由:", width)).append("║\n");
            for (String line : wrapText(suggestion, width - 6)) {
                sb.append("║    ").append(padRightByDisplayWidth(line, width - 4)).append("║\n");
            }
        }

        sb.append("╠").append(border).append("╣\n");
        sb.append(padRightByDisplayWidth("║  操作: [y]批准  [a]全部批准  [n]拒绝  [s]跳过", width)).append("║\n");
        sb.append("╚").append(border).append("╝");

        return sb.toString();
    }

    /** 格式化 "║  {key}: {value}{padding}║"（基于显示列宽对齐） */
    private String formatField(String key, String value, int width) {
        String label = key + ": ";
        String safeValue = value == null ? "" : value;
        String content = "  " + label + safeValue;
        int contentW = displayWidth(content);
        if (contentW >= width) {
            content = truncateByDisplayWidth(content, width);
            contentW = displayWidth(content);
        }
        return "║" + content + " ".repeat(Math.max(0, width - contentW)) + "║";
    }

    /** 基于显示列宽拆行（CJK 友好） */
    private static List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        for (String paragraph : text.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            while (displayWidth(paragraph) > maxWidth) {
                // 找到不超过 maxWidth 列的最大子串
                String chunk = truncateByDisplayWidth(paragraph, maxWidth);
                lines.add(chunk);
                paragraph = paragraph.substring(chunk.length());
            }
            lines.add(paragraph);
        }
        return lines;
    }

    /** JSON-aware 参数展示：逐字段展示 key: value */
    private List<String> formatArgs(String args) {
        List<String> lines = new ArrayList<>();
        if (args == null || args.isBlank()) {
            lines.add("(无参数)");
            return lines;
        }
        try {
            JsonNode root = MAPPER.readTree(args);
            if (root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String k = entry.getKey();
                    JsonNode v = entry.getValue();
                    if (v.isTextual()) {
                        String text = v.asText();
                        if (text.length() > MAX_VALUE_PREVIEW) {
                            text = text.substring(0, MAX_VALUE_PREVIEW) + "...(" + v.asText().length() + "字符)";
                        }
                        lines.add(k + ": \"" + text.replace("\n", "\\n") + "\"");
                    } else {
                        lines.add(k + ": " + v);
                    }
                }
                if (lines.isEmpty()) {
                    lines.add("(空对象)");
                }
                return lines;
            }
        } catch (Exception ignored) {
        }
        // 非 JSON 原样展示
        String trimmed = args.trim();
        if (trimmed.length() > MAX_VALUE_PREVIEW) {
            trimmed = trimmed.substring(0, MAX_VALUE_PREVIEW) + "...";
        }
        lines.add(trimmed);
        return lines;
    }

    // ==================== 终端显示列宽工具 ====================

    /**
     * 计算字符串在终端中的显示列宽。
     * <p>
     * CJK 字符、全角字符、Emoji 等占 2 列，ASCII 可打印字符占 1 列，
     * 控制字符（{@code < 0x20} 和 {@code 0x7F}）占 0 列。
     * 使用 {@link String#codePointAt(int)} + {@link Character#charCount(int)}
     * 正确处理代理对（surrogate pair）。
     *
     * @param s 待计算字符串
     * @return 终端显示列宽
     */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) continue;
            if (isWideCodePoint(cp)) w += 2;
            else w += 1;
        }
        return w;
    }

    /**
     * 判断 Unicode 码点是否为宽字符（终端占 2 列）。
     * <p>
     * 覆盖 10 个区间：Hangul Jamo、CJK 部首/统一汉字、彝文音节、
     * 韩文音节、CJK 兼容汉字、CJK 兼容符号、全角形式、
     * 杂项符号 + Dingbats、Emoji 区块。
     */
    private static boolean isWideCodePoint(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo
                || (cp >= 0x2E80 && cp <= 0x9FFF)  // CJK Radicals / 统一汉字
                || (cp >= 0xA000 && cp <= 0xA4CF)  // Yi Syllables
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // Hangul Syllables
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK Compatibility Ideographs
                || (cp >= 0xFE30 && cp <= 0xFE4F)  // CJK Compatibility Forms
                || (cp >= 0xFF00 && cp <= 0xFF60)  // Fullwidth Forms
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x2600 && cp <= 0x27BF)  // Misc Symbols + Dingbats
                || (cp >= 0x1F300 && cp <= 0x1FAFF); // Emoji block
    }

    /**
     * 按终端显示列宽截断字符串。
     * <p>
     * 返回不超过 {@code targetCols} 列的最大前缀子串。
     * 不会在宽字符中间截断——如果加入下一个宽字符会超限，则停止。
     *
     * @param s          原字符串
     * @param targetCols 目标最大列数
     * @return 截断后的子串
     */
    private static String truncateByDisplayWidth(String s, int targetCols) {
        if (s == null) return "";
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int charW = (cp < 0x20 || cp == 0x7F) ? 0 : (isWideCodePoint(cp) ? 2 : 1);
            if (w + charW > targetCols) break;
            w += charW;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    /**
     * 按终端显示列宽右补空格至目标列数。
     * <p>
     * 如果字符串已超出目标列数则截断。
     *
     * @param s          原字符串
     * @param targetCols 目标列数
     * @return 补空格后的字符串
     */
    private static String padRightByDisplayWidth(String s, int targetCols) {
        if (s == null) return " ".repeat(targetCols);
        int w = displayWidth(s);
        if (w >= targetCols) return truncateByDisplayWidth(s, targetCols);
        return s + " ".repeat(targetCols - w);
    }
}
