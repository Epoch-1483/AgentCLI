package edu.cqie.paiclidemo.cli;

import edu.cqie.paiclidemo.agent.Agent;
import edu.cqie.paiclidemo.agent.PlanExecuteAgent;
import edu.cqie.paiclidemo.llm.GLMClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.memory.MemoryManager;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;

/**
 * PaiCLI-Demo 入口 —— 一个极简的 AI Agent 命令行交互界面。
 * <p>
 * 启动流程：
 * 1. 从环境变量 GLM_API_KEY 读取 API 密钥
 * 2. 创建 GLMClient（连接智谱大模型）
 * 3. 创建 ToolRegistry 并注册示例工具
 * 4. 创建 Agent（ReAct 循环引擎）
 * 5. 进入 REPL 交互循环：读取用户输入 → Agent 处理 → 输出结果
 * <p>
 *
 * @author Fonzo
 * @date 2026/07/15
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // ① 读取 API 密钥（从环境变量，避免硬编码）
        String apiKey = System.getenv("GLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：请设置环境变量 GLM_API_KEY");
            System.err.println("  Windows:  set GLM_API_KEY=your-key");
            System.err.println("  Linux/Mac: export GLM_API_KEY=your-key");
            return;
        }

        // ② 创建 LLM 客户端
        LlmClient llmClient = new GLMClient(apiKey);

        // ③ 创建工具注册中心，注册示例工具
        ToolRegistry toolRegistry = createToolRegistry();

        // ④ 创建 MemoryManager（记忆系统）
        MemoryManager memoryManager = new MemoryManager(llmClient);

        // ⑤ 创建 Scanner（供 REPL 和 PlanExecuteAgent 共用）
        Scanner scanner = new Scanner(System.in);

        // ⑥ 创建 Agent（两种模式，都接入 Memory）
        Agent agent = new Agent(llmClient, toolRegistry, memoryManager);           // ReAct 模式（含记忆）
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, scanner, memoryManager);  // Plan-and-Execute 模式（含记忆）

        // ⑦ 显示启动横幅 + 进入 REPL 交互循环
        Banner.display(
                llmClient.getModelName(),
                toolRegistry.getToolDefinitions().size(),
                true
        );

        while (true) {
            System.out.print("\n> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            // 用户输入
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                // 退出前提取事实到长期记忆
                try {
                    memoryManager.extractFactsFromConversation();
                } catch (Exception e) {
                    log.warn("退出时事实提取失败: {}", e.getMessage());
                }
                System.out.println("再见！");
                break;
            }
            if ("clear".equalsIgnoreCase(input)) {
                agent.clearHistory();  // 会先提取事实到长期记忆再清空
                System.out.println("[对话历史已清空，长期记忆已保留]");
                continue;
            }

            // /plan 命令 → Plan-and-Execute 模式
            if (input.startsWith("/plan")) {
                String goal = input.substring(5).trim();
                if (goal.isEmpty()) {
                    System.out.println("用法: /plan <目标描述>");
                    System.out.println("示例: /plan 帮我计算 2 的 10 次方然后加上 100");
                    continue;
                }
                try {
                    String response = planAgent.run(goal);
                    System.out.println("\n" + response);
                } catch (Exception e) {
                    System.err.println("\n规划执行出错: " + e.getMessage());
                    log.error("PlanExecuteAgent 异常", e);
                }
                continue;
            }

            // /memory 命令 → 记忆管理
            if (input.startsWith("/memory")) {
                handleMemoryCommand(input, memoryManager);
                continue;
            }

            try {
                String response = agent.run(input); // Agent 处理用户输入
                System.out.println("\n" + response);
            } catch (IOException e) {
                System.err.println("\n请求出错: " + e.getMessage());
                log.error("Agent 执行异常", e);
            }
        }
    }

    // ==================== 记忆管理命令 ====================

    /**
     * 处理 /memory 子命令。
     * <p>
     * 支持的命令：
     * - /memory status → 查看记忆系统状态
     * - /memory facts → 查看所有长期记忆
     * - /memory clear short → 清空短期记忆
     * - /memory clear long → 清空长期记忆
     * - /memory extract → 手动从当前对话中提取事实
     */
    private static void handleMemoryCommand(String input, MemoryManager memoryManager) {
        String args = input.substring(7).trim();

        if (args.isEmpty() || "status".equalsIgnoreCase(args)) {
            System.out.println("\n📊 记忆系统状态:");
            System.out.println(memoryManager.getSystemStatus());
            return;
        }

        if ("facts".equalsIgnoreCase(args)) {
            var facts = memoryManager.listLongTerm();
            if (facts.isEmpty()) {
                System.out.println("长期记忆为空。");
            } else {
                System.out.println("\n📝 长期记忆 (" + facts.size() + " 条):");
                for (var fact : facts) {
                    System.out.println("  " + fact);
                }
            }
            return;
        }

        if ("extract".equalsIgnoreCase(args)) {
            try {
                var extracted = memoryManager.extractFactsFromConversation();
                if (extracted.isEmpty()) {
                    System.out.println("未从当前对话中提取到关键事实。");
                } else {
                    System.out.println("\n🧠 提取了 " + extracted.size() + " 条事实:");
                    for (String fact : extracted) {
                        System.out.println("  - " + fact);
                    }
                }
            } catch (Exception e) {
                System.err.println("事实提取失败: " + e.getMessage());
            }
            return;
        }

        if (args.startsWith("clear")) {
            String target = args.substring(5).trim();
            if ("short".equalsIgnoreCase(target)) {
                memoryManager.clearShortTerm();
                System.out.println("短期记忆已清空。");
            } else if ("long".equalsIgnoreCase(target)) {
                memoryManager.clearLongTerm();
                System.out.println("长期记忆已清空。");
            } else {
                System.out.println("用法: /memory clear short | /memory clear long");
            }
            return;
        }

        if (args.startsWith("search")) {
            String query = args.substring(6).trim();
            if (query.isEmpty()) {
                System.out.println("用法: /memory search <查询关键词>");
                return;
            }
            var scored = memoryManager.retrieveScored(query, 10);
            if (scored.isEmpty()) {
                System.out.println("未找到与 \"" + query + "\" 相关的记忆。");
            } else {
                System.out.printf("%n🔍 检索 \"%s\"，命中 %d 条记忆:%n", query, scored.size());
                System.out.println("─".repeat(60));
                for (int i = 0; i < scored.size(); i++) {
                    var se = scored.get(i);
                    String source = se.fromShortTerm() ? "短期" : "长期";
                    String content = se.entry().getContent();
                    if (content.length() > 60) content = content.substring(0, 60) + "...";
                    System.out.printf("  #%d [%.2f] %s (%s)%n",
                            i + 1, se.score(), source, se.entry().getType());
                    System.out.printf("       关键词=%.2f  时间衰减=%.2f  来源加权=%.1f%n",
                            se.keywordScore(), se.timeDecay(), se.sourceWeight());
                    System.out.printf("       %s%n", content);
                }
            }
            return;
        }

        System.out.println("未知命令。可用: /memory status, /memory facts, /memory search <关键词>, /memory extract, /memory clear short|long");
    }

    // ==================== 工具注册 ====================

    /**
     * 创建并配置工具注册中心。
     * <p>
     * 注册了 2 个示例工具，展示如何定义工具的 JSON Schema 和执行逻辑。
     * 你可以通过 toolRegistry.register() 添加更多工具。
     */
    private static ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry();

        // 工具 1：计算器 —— 安全地计算数学表达式
        registry.register(
                "calculator",
                "计算数学表达式。支持加减乘除、括号、幂运算。"
                        + "例如：'2 + 3 * 4'、'(100 - 20) / 4'、'2 ** 10'",
                createParameters(new Param("expression", "string", "要计算的数学表达式")),
                args -> {
                    String expr = args.get("expression");
                    if (expr == null || expr.isBlank()) {
                        return "错误：请提供数学表达式";
                    }
                    try {
                        // 安全计算：只允许数字和基本运算符
                        double result = evaluateExpression(expr);
                        // 如果结果是整数，去掉小数点
                        if (result == Math.floor(result) && !Double.isInfinite(result)) {
                            return expr + " = " + (long) result;
                        }
                        return expr + " = " + result;
                    } catch (Exception e) {
                        return "计算出错: " + e.getMessage();
                    }
                }
        );

        // 工具 2：获取当前时间
        registry.register(
                "get_current_time",
                "获取当前系统日期和时间。当用户询问现在几点、今天日期等问题时使用。",
                createParameters(),  // 无参数
                args -> {
                    LocalDateTime now = LocalDateTime.now();
                    return "当前时间: " + now.format(
                            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
                }
        );

        return registry;
    }

    // ==================== 简易表达式计算器 ====================

    /**
     * 安全的数学表达式求值（不依赖 ScriptEngine）。
     * 支持：+、-、*、/、**（幂）、括号。
     */
    private static double evaluateExpression(String expr) {
        // 只允许安全字符
        if (!expr.matches("[\\d+\\-*/().\\s^]+")) {
            throw new IllegalArgumentException("包含不允许的字符");
        }
        // 将 ^ 替换为 ** 统一处理
        expr = expr.replace("^", "**");
        return new ExpressionParser(expr).parse();
    }

    /** 递归下降表达式解析器（包级可见，便于单元测试） */
    static class ExpressionParser {
        private final String input;
        private int pos = 0;

        ExpressionParser(String input) {
            this.input = input.replaceAll("\\s+", "");
        }

        double parse() {
            double result = parseExpression();
            if (pos < input.length()) {
                throw new IllegalArgumentException("意外字符: " + input.charAt(pos));
            }
            return result;
        }

        // expression = term (('+' | '-') term)*
        private double parseExpression() {
            double result = parseTerm();
            while (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                char op = input.charAt(pos++);
                double term = parseTerm();
                result = op == '+' ? result + term : result - term;
            }
            return result;
        }

        // term = power (('*' | '/') power)*
        private double parseTerm() {
            double result = parsePower();
            while (pos < input.length() && (input.charAt(pos) == '*' || input.charAt(pos) == '/')) {
                char op = input.charAt(pos++);
                double power = parsePower();
                result = op == '*' ? result * power : result / power;
            }
            return result;
        }

        // power = unary ('**' unary)*
        private double parsePower() {
            double result = parseUnary();
            if (pos + 1 < input.length() && input.charAt(pos) == '*' && input.charAt(pos + 1) == '*') {
                pos += 2;
                double exponent = parseUnary();
                result = Math.pow(result, exponent);
            }
            return result;
        }

        // unary = '-' unary | primary
        private double parseUnary() {
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                return -parseUnary();
            }
            return parsePrimary();
        }

        // primary = number | '(' expression ')'
        private double parsePrimary() {
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++; // skip '('
                double result = parseExpression();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                pos++; // skip ')'
                return result;
            }

            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("期望数字，位置 " + pos);
            }
            return Double.parseDouble(input.substring(start, pos));
        }
    }
}
