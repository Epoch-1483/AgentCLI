package edu.cqie.paiclidemo.cli;

import edu.cqie.paiclidemo.agent.Agent;
import edu.cqie.paiclidemo.agent.AgentOrchestrator;
import edu.cqie.paiclidemo.agent.PlanExecuteAgent;
import edu.cqie.paiclidemo.config.DotEnv;
import edu.cqie.paiclidemo.llm.GLMClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.memory.MemoryManager;
import edu.cqie.paiclidemo.rag.CodeIndex;
import edu.cqie.paiclidemo.rag.CodeRelation;
import edu.cqie.paiclidemo.rag.CodeRetriever;
import edu.cqie.paiclidemo.rag.SearchResultFormatter;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
        // ① 读取 API 密钥（优先 .env 文件，也支持环境变量 / JVM 参数）
        String apiKey = DotEnv.get("GLM_API_KEY", null);
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：未找到 GLM_API_KEY 配置");
            System.err.println("  方式一：在项目根目录创建 .env 文件，写入 GLM_API_KEY=your-key");
            System.err.println("  方式二：设置环境变量 GLM_API_KEY=your-key");
            System.err.println("  参考 .env.example 文件");
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

        // ⑥ 创建 Agent（三种模式，都接入 Memory）
        Agent agent = new Agent(llmClient, toolRegistry, memoryManager);           // ReAct 模式（含记忆）
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, scanner, memoryManager);  // Plan-and-Execute 模式（含记忆）
        AgentOrchestrator orchestrator = new AgentOrchestrator(llmClient, toolRegistry, memoryManager);  // Multi-Agent 模式

        // ⑦ 检测 RAG 索引状态
        boolean ragReady = checkRagReady();

        // ⑧ 显示启动横幅 + 进入 REPL 交互循环
        Banner.display(
                llmClient.getModelName(),
                toolRegistry.getToolDefinitions().size(),
                true,
                ragReady
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

            // /index 命令 → 索引代码库（RAG）
            if (input.startsWith("/index")) {
                handleIndexCommand(input, toolRegistry);
                continue;
            }

            // /search 命令 → 语义检索代码（RAG）
            if (input.startsWith("/search")) {
                handleSearchCommand(input, toolRegistry);
                continue;
            }

            // /graph 命令 → 查询代码关系图谱（RAG）
            if (input.startsWith("/graph")) {
                handleGraphCommand(input, toolRegistry);
                continue;
            }

            // /team 命令 → Multi-Agent 协作模式
            if (input.startsWith("/team")) {
                String task = input.substring(5).trim();
                if (task.isEmpty()) {
                    System.out.println("用法: /team <任务描述>");
                    System.out.println("示例: /team 帮我分析 2 的 10 次方是多少，然后验证结果");
                    System.out.println("Multi-Agent 团队：1 个规划者 + 2 个执行者 + 1 个检查者");
                    continue;
                }
                try {
                    String response = orchestrator.run(task);
                    System.out.println("\n" + response);
                } catch (Exception e) {
                    System.err.println("\nMulti-Agent 执行出错: " + e.getMessage());
                    log.error("AgentOrchestrator 异常", e);
                }
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

    // ==================== RAG 命令 ====================

    /**
     * /index [path] → 索引代码库，建立 RAG 向量索引。
     * <p>
     * 流程：
     * 1. 解析路径参数（默认当前目录 "."）
     * 2. 创建 CodeIndex，进度输出到控制台
     * 3. 执行索引：遍历文件 → 分块 → 向量化 → 存入 SQLite
     * 4. 将索引路径同步到 ToolRegistry（确保 search_code 工具使用正确路径）
     */
    private static void handleIndexCommand(String input, ToolRegistry toolRegistry) {
        String indexPath = input.substring(6).trim();
        if (indexPath.isEmpty()) {
            indexPath = ".";
        }

        String absPath = Paths.get(indexPath).toAbsolutePath().normalize().toString();
        System.out.println("开始索引: " + absPath);
        System.out.println("─".repeat(50));

        CodeIndex indexer = new CodeIndex(System.out::println);
        CodeIndex.IndexResult result = indexer.index(indexPath);

        System.out.println("─".repeat(50));
        System.out.println("结果: " + result.message());

        // 同步路径到 ToolRegistry，让 search_code 工具知道索引的是哪个项目
        toolRegistry.setProjectPath(absPath);
        System.out.println("search_code 工具已绑定路径: " + absPath);
    }

    /**
     * /search <query> → 语义检索代码库（绕过 Agent，直接查询 RAG）。
     * <p>
     * 使用 CodeRetriever.hybridSearch + SearchResultFormatter.formatForCli 输出。
     */
    private static void handleSearchCommand(String input, ToolRegistry toolRegistry) {
        String query = input.substring(7).trim();
        if (query.isEmpty()) {
            System.out.println("用法: /search <查询内容>");
            System.out.println("示例: /search Agent 的 run 方法是怎么实现的");
            return;
        }

        try (CodeRetriever retriever = new CodeRetriever(toolRegistry.getProjectPath())) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                System.out.println("代码库尚未索引，请先执行 /index 命令。");
                return;
            }

            var results = retriever.hybridSearch(query, 5);
            if (results.isEmpty()) {
                System.out.println("未找到与 \"" + query + "\" 相关的代码块。");
                return;
            }

            System.out.println("\n" + SearchResultFormatter.formatForCli(query, results));
        } catch (Exception e) {
            System.err.println("检索出错: " + e.getMessage());
            log.error("/search 命令执行失败", e);
        }
    }

    /**
     * /graph <className> → 查询代码关系图谱。
     * <p>
     * 从 VectorStore 中查询指定类/方法的关系（extends、implements、contains 等），
     * 以箭头形式展示。
     */
    private static void handleGraphCommand(String input, ToolRegistry toolRegistry) {
        String className = input.substring(6).trim();
        if (className.isEmpty()) {
            System.out.println("用法: /graph <类名或方法名>");
            System.out.println("示例: /graph Agent");
            return;
        }

        try (CodeRetriever retriever = new CodeRetriever(toolRegistry.getProjectPath())) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                System.out.println("代码库尚未索引，请先执行 /index 命令。");
                return;
            }

            List<CodeRelation> relations = retriever.getRelationGraph(className);
            if (relations.isEmpty()) {
                System.out.println("未找到与 \"" + className + "\" 相关的代码关系。");
                return;
            }

            System.out.println("\n📊 代码关系图谱: " + className);
            System.out.println("─".repeat(50));
            for (CodeRelation rel : relations) {
                String arrow = switch (rel.relationType()) {
                    case "extends" -> "──extends──▶";
                    case "implements" -> "──implements──▶";
                    case "contains" -> "──contains──▶";
                    case "calls" -> "──calls──▶";
                    case "imports" -> "──imports──▶";
                    default -> "──" + rel.relationType() + "──▶";
                };
                System.out.printf("  %s %s %s%n", rel.fromName(), arrow, rel.toName());
            }
            System.out.println("─".repeat(50));
            System.out.println("共 " + relations.size() + " 条关系");
        } catch (Exception e) {
            System.err.println("图谱查询出错: " + e.getMessage());
            log.error("/graph 命令执行失败", e);
        }
    }

    // ==================== RAG 状态检测 ====================

    /**
     * 启动时检测 RAG 索引是否就绪。
     * <p>
     * 尝试打开默认路径的 VectorStore，查询 chunkCount。
     * 如果数据库不存在或 chunkCount=0，返回 false。
     */
    private static boolean checkRagReady() {
        try (var retriever = new CodeRetriever(".")) {
            var stats = retriever.getStats();
            return stats.chunkCount() > 0;
        } catch (Exception e) {
            return false;
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

        // 工具 3：RAG 语义检索（search_code）
        registry.registerRagTools();

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
