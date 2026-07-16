package edu.cqie.paiclidemo.cli;

import edu.cqie.paiclidemo.agent.Agent;
import edu.cqie.paiclidemo.llm.GLMClient;
import edu.cqie.paiclidemo.llm.LlmClient;
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
        System.out.println("PaiCLI-Demo v0.1 | 模型: " + llmClient.getModelName());

        // ③ 创建工具注册中心，注册示例工具
        ToolRegistry toolRegistry = createToolRegistry();
        System.out.println("已注册 " + toolRegistry.getToolDefinitions().size() + " 个工具: "
                + toolRegistry.getToolDefinitions().stream()
                .map(LlmClient.Tool::name)
                .toList());

        // ④ 创建 Agent（ReAct 循环引擎）
        Agent agent = new Agent(llmClient, toolRegistry);

        // ⑤ 进入 REPL 交互循环
        System.out.println("\n输入你的问题（输入 quit 退出，clear 清空历史）：");
        System.out.println("─".repeat(50));

        Scanner scanner = new Scanner(System.in);
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
                System.out.println("再见！");
                break;
            }
            if ("clear".equalsIgnoreCase(input)) {
                agent.clearHistory();
                System.out.println("[对话历史已清空]");
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

    /** 递归下降表达式解析器 */
    private static class ExpressionParser {
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
