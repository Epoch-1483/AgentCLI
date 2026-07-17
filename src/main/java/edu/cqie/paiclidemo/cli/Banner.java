package edu.cqie.paiclidemo.cli;

/**
 * 终端启动横幅 —— 模仿 Claude Code / PaiCLI 的 ASCII 艺术启动画面。
 * <p>
 * 使用 Unicode 块字符 ██ 组成 π 形状（Pai = π），搭配 ANSI 256 色渐变。
 * 设计灵感来源于 Claude Code 的红色水母图标和原版 PaiCLI 的启动界面。
 * <p>
 * 输出效果示例：
 * <pre>
 *  ╭─────────────────────────────────────────────╮
 *  │                                             │
 *  │    ██████████                               │
 *  │      ██  ██     PaiCLI Demo  v0.1           │
 *  │      ██  ██     智谱 AI 代理                │
 *  │      ██  ██     Model: glm-5-flash          │
 *  │      ██  ██     5 tools · Memory ✓ · RAG ✗ │
 *  │                                             │
 *  │  ─────────────────────────────────────────  │
 *  │  输入 quit 退出 · clear 清空 · /plan 规划    │
 *  │  /memory 管理记忆 · /index /search /graph RAG│
 *  ╰─────────────────────────────────────────────╯
 * </pre>
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class Banner {

    // ==================== ANSI 256 色常量 ====================

    /** ANSI 重置所有属性 */
    private static final String RESET = "\033[0m";

    /** 暖橙（π 顶部横杠） */
    private static final String WARM1 = "\033[38;5;214m";

    /** 橙红（π 身体 / 腿部） */
    private static final String WARM2 = "\033[38;5;208m";

    /** 白色（标题文字） */
    private static final String WHITE = "\033[38;5;255m";

    /** 灰色（副标题、提示） */
    private static final String GRAY = "\033[38;5;245m";

    /** 亮绿（状态正常标记） */
    private static final String GREEN = "\033[38;5;114m";

    /** 金色（分隔线） */
    private static final String GOLD = "\033[38;5;220m";

    /** π 符号的 5 行图形（使用 Unicode 块字符 ██） */
    private static final String[] PI_ART = {
            "██████████",   // 顶部横杠
            "  ██  ██",      // 两条腿
            "  ██  ██",
            "  ██  ██",
            "  ██  ██",
    };

    private Banner() {} // 工具类，不可实例化

    /**
     * 打印启动横幅到标准输出。
     *
     * @param modelName 当前使用的模型名称（如 "glm-5-flash"）
     * @param toolCount 已注册工具数量
     * @param hasMemory 是否启用了记忆系统
     * @param ragReady  RAG 索引是否就绪（已有向量数据）
     */
    public static void display(String modelName, int toolCount, boolean hasMemory, boolean ragReady) {
        // 右侧信息面板（与 π 图形的第 1~4 行对齐）
        String[] info = {
                WHITE + " PaiCLI Demo" + RESET + GRAY + "  v0.1" + RESET,
                GRAY + " 智谱 AI 代理" + RESET,
                GRAY + " Model: " + RESET + WHITE + modelName + RESET,
                GRAY + " " + toolCount + " tools" + RESET
                        + GRAY + " · Memory " + RESET
                        + GREEN + (hasMemory ? "✓" : "✗") + RESET
                        + GRAY + " · RAG " + RESET
                        + GREEN + (ragReady ? "✓" : "✗") + RESET,
        };

        // ── 顶部边框 ──
        line("╭" + "─".repeat(50) + "╮");

        // ── 空行 ──
        line("│" + " ".repeat(50) + "│");

        // ── π 图形 + 右侧信息 ──
        for (int i = 0; i < PI_ART.length; i++) {
            String piLine = (i == 0 ? WARM1 : WARM2) + PI_ART[i] + RESET;
            int pad = 10 - PI_ART[i].length();  // π 最大宽度 10，右填充对齐
            String infoStr = (i - 1 >= 0 && i - 1 < info.length) ? info[i - 1] : "";
            int infoVisLen = visibleLength(infoStr);
            int trailPad = 50 - 4 - pad - infoVisLen;
            if (trailPad < 0) trailPad = 1;

            line("│" + " ".repeat(4)
                    + piLine + " ".repeat(pad)
                    + infoStr + " ".repeat(trailPad)
                    + "│");
        }

        // ── 空行 ──
        line("│" + " ".repeat(50) + "│");

        // ── 分隔线 ──
        line("│  " + GOLD + "─".repeat(46) + RESET + "  │");

        // ── 使用提示 ──
        line("│  " + GRAY + "输入 quit 退出 · clear 清空 · /plan 规划模式"
                + RESET + " ".repeat(4) + "│");
        line("│  " + GRAY + "/team 多Agent协作 · /memory 管理记忆"
                + RESET + " ".repeat(12) + "│");
        line("│  " + GRAY + "/index /search /graph RAG 代码检索"
                + RESET + " ".repeat(13) + "│");

        // ── 底部边框 ──
        line("╰" + "─".repeat(50) + "╯");
    }

    /** 输出一行带 RESET 保护 */
    private static void line(String s) {
        System.out.println(s + RESET);
    }

    /**
     * 计算去除 ANSI 转义序列后的可见字符宽度。
     * <p>
     * 用于精确计算右侧内边距，使边框对齐。
     * 注意：此方法按 Java char 计数，CJK 字符会被计为 1 而非 2，
     * 可能导致 1-2 字符的视觉偏差，但不影响整体效果。
     */
    private static int visibleLength(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "").length();
    }
}
