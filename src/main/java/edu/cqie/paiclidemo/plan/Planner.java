package edu.cqie.paiclidemo.plan;

import edu.cqie.paiclidemo.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 规划器 —— 调用 LLM 将复杂目标分解为 DAG 执行计划。
 * <p>
 * 工作流程：
 * 1. 判断目标是否简单（isSimpleGoal）
 *    - 简单目标 → 直接生成单任务计划（不调 LLM，省钱省时）
 *    - 复杂目标 → 调用 LLM 生成多任务 DAG 计划
 * 2. LLM 返回 JSON 格式的任务列表
 * 3. 两遍扫描解析为 ExecutionPlan
 * <p>
 * 也支持 replan：执行失败时，基于当前进度重新规划。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final LlmClient llmClient;

    public Planner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // ==================== 核心方法：创建计划 ====================

    /**
     * 根据目标创建执行计划。
     * <p>
     * 简单目标（如"现在几点"）不需要调用 LLM，直接生成单任务计划。
     * 复杂目标（如"创建一个项目并写测试"）需要 LLM 分解为 DAG。
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        System.out.println("\n🧠 正在规划...");

        // 简单目标快速通道
        if (isSimpleGoal(goal)) {
            log.info("检测到简单目标，跳过 LLM 规划");
            return createMinimalPlan(goal);
        }

        // 构建规划提示词
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(buildPlanningPrompt()));
        messages.add(LlmClient.Message.user(goal));

        // 调用 LLM 生成计划（不传工具定义，因为规划阶段不需要调用工具）
        LlmClient.ChatResponse response = llmClient.chat(messages, List.of());
        String planJson = response.content();

        log.info("LLM 规划完成 | tokens: in={}, out={}", response.inputTokens(), response.outputTokens());

        // 解析 JSON 为 ExecutionPlan
        try {
            // 尝试提取 JSON 部分（LLM 可能在 JSON 外面包了 markdown 代码块）
            String json = extractJson(planJson);
            return ExecutionPlan.parse(goal, json);
        } catch (Exception e) {
            log.error("解析计划 JSON 失败: {}", planJson, e);
            // 解析失败时降级为单任务计划
            System.out.println("⚠ 计划解析失败，降级为单任务执行");
            return createMinimalPlan(goal);
        }
    }

    /**
     * 重新规划 —— 执行失败时，基于当前进度重新生成计划。
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        StringBuilder context = new StringBuilder();
        context.append("原始目标: ").append(failedPlan.getGoal()).append("\n\n");
        context.append("失败原因: ").append(failureReason).append("\n\n");
        context.append("已完成的任务:\n");
        for (Task task : failedPlan.getTasks().values()) {
            if (task.getStatus() == Task.Status.COMPLETED) {
                context.append("  ✅ [").append(task.getId()).append("] ")
                        .append(task.getDescription()).append("\n");
                if (task.getResult() != null) {
                    context.append("     结果: ").append(truncate(task.getResult(), 200)).append("\n");
                }
            }
        }
        context.append("\n请基于以上进度，重新规划剩余步骤。");

        return createPlan(context.toString());
    }

    // ==================== 简单目标判断 ====================

    /**
     * 启发式判断：目标是否足够简单，不需要 DAG 规划？
     * <p>
     * 简单目标特征：短文本 + 单一动作关键词
     * 复杂目标特征：包含"然后"、"接着"、"最后"等顺序词，或"并且"、"同时"等并行词
     */
    private boolean isSimpleGoal(String goal) {
        if (goal.length() > 80) {
            return false;
        }

        String lower = goal.toLowerCase();
        // 包含多步骤提示词 → 复杂
        String[] multiStepWords = {"然后", "接着", "之后", "最后", "并且", "同时", "再",
                "then", "after", "finally", "and then", "step"};
        for (String word : multiStepWords) {
            if (lower.contains(word)) {
                return false;
            }
        }

        // 包含简单动作词 → 简单
        String[] simpleWords = {"多少", "什么", "几点", "日期", "计算",
                "how", "what", "when", "calculate"};
        for (String word : simpleWords) {
            if (lower.contains(word)) {
                return true;
            }
        }

        return false;  // 默认走 LLM 规划
    }

    /**
     * 为简单目标创建最小计划 —— 简单任务用 ReAct，复杂任务用 Plan-and-Execute
     */
    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(goal);
        Task.Type type = inferSimpleTaskType(goal);
        Task task = new Task("1", goal, type, List.of());
        plan.addTask(task);
        plan.computeExecutionOrder();
        System.out.println("📋 简单计划: [1] " + goal + " (" + type + ")");
        return plan;
    }

    /**
     * 根据关键词推断简单任务的类型。
     */
    private Task.Type inferSimpleTaskType(String goal) {
        String lower = goal.toLowerCase();
        if (lower.contains("计算") || lower.contains("calculate") || lower.matches(".*[\\d+\\-*/].*")) {
            return Task.Type.COMMAND;
        }
        if (lower.contains("读") || lower.contains("read") || lower.contains("查看")) {
            return Task.Type.FILE_READ;
        }
        if (lower.contains("写") || lower.contains("write") || lower.contains("创建")) {
            return Task.Type.FILE_WRITE;
        }
        return Task.Type.ANALYSIS;  // 默认走分析
    }

    // ==================== 提示词构建 ====================

    /**
     * 构建规划用的系统提示词。
     * <p>
     * 告诉 LLM：你是一个规划专家，请把目标分解为 JSON 格式的任务列表。
     */
    private String buildPlanningPrompt() {
        return """
                你是一个任务规划专家。你的职责是将用户的目标分解为可执行的任务计划。
                
                请严格按照以下 JSON 格式输出计划，不要输出任何其他内容：
                ```json
                {
                  "tasks": [
                    {
                      "id": "1",
                      "description": "任务描述",
                      "type": "任务类型",
                      "dependencies": []
                    },
                    {
                      "id": "2", 
                      "description": "任务描述",
                      "type": "任务类型",
                      "dependencies": ["1"]
                    }
                  ]
                }
                ```
                
                任务类型（type）可选值：
                - PLANNING: 需要思考和决策的任务
                - FILE_READ: 读取文件
                - FILE_WRITE: 创建或修改文件
                - COMMAND: 执行命令或计算
                - ANALYSIS: 分析前序任务的结果
                - VERIFICATION: 验证结果是否正确
                
                规则：
                1. 每个任务应该是一个独立的、可执行的步骤
                2. dependencies 填写本任务依赖的任务 ID 列表（这些任务必须先完成）
                3. 没有依赖的任务放在最前面
                4. 最后通常加一个 ANALYSIS 或 VERIFICATION 任务来总结/验证
                5. 只输出 JSON，不要解释
                """;
    }

    // ==================== JSON 提取辅助 ====================

    /**
     * 从 LLM 响应中提取 JSON 部分。
     * <p>
     * LLM 可能在 JSON 外面包了 markdown 代码块：
     * ```json
     * { ... }
     * ```
     * 这个方法把 JSON 提取出来。
     */
    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        String trimmed = text.trim();

        // 尝试提取 ```json ... ``` 中的内容
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }

        // 直接找 { ... }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
