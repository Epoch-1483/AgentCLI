package edu.cqie.paiclidemo.agent;

import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.plan.ExecutionPlan;
import edu.cqie.paiclidemo.plan.Planner;
import edu.cqie.paiclidemo.plan.Task;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Plan-and-Execute Agent —— 先规划后执行的智能体。
 * <p>
 * 与 ReAct Agent 的区别：
 * <ul>
 *   <li>ReAct：每一步都让 LLM 推理 → 工具调用 → 观察 → 再推理（高频 LLM 调用）</li>
 *   <li>Plan-and-Execute：LLM 一次性规划 → 按 DAG 逐步执行（低频 LLM 调用）</li>
 * </ul>
 * <p>
 * 执行流程：
 * <pre>
 *   用户目标
 *     → Planner 生成 DAG 计划
 *     → 展示计划给用户
 *     → 循环：获取可执行任务 → 执行（mini ReAct）→ 标记状态
 *     → 全部完成 → 汇总结果
 *     → 如果失败且进度 < 50% → replan 重新规划
 * </pre>
 * <p>
 * 关键优势：
 * - 减少 LLM 调用次数（规划一次，执行多步）
 * - 可预测性强（用户可以看到完整计划）
 * - 支持并行执行（DAG 中无依赖的任务可以并行）
 * - 失败可重试（replan）
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class PlanExecuteAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);

    /** 每个任务内部的最大 LLM 迭代次数（mini ReAct 循环） */
    private static final int MAX_TASK_ITERATIONS = 5;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final Scanner scanner;  // 用于规划后与用户确认（null = 跳过确认，直接执行）

    /**
     * 交互式构造：传入 Scanner 用于规划后确认。
     * 适用于 CLI 场景，用户可以看到计划并决定是否修改。
     */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Scanner scanner) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
        this.scanner = scanner;
    }

    /**
     * 非交互式构造：跳过规划确认，直接执行（向后兼容，适用于测试）。
     */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null);
    }

    // ==================== 核心方法：规划 + 执行 ====================

    /**
     * 处理用户输入：先规划，后执行。
     * <p>
     * 流程：规划 → 展示 → 用户确认 → 执行。
     * 用户可以在确认阶段补充信息、要求重新规划或取消。
     *
     * @param goal 用户的目标描述
     * @return 最终执行结果
     */
    public String run(String goal) throws IOException {
        // ① 规划阶段：让 LLM 生成 DAG 计划
        ExecutionPlan plan = planner.createPlan(goal);

        // ② 展示计划 + 用户确认（可能有多个来回）
        plan = confirmPlan(plan, goal);
        if (plan == null) {
            return "🚫 计划已取消";
        }

        // ③ 执行阶段
        plan.markStarted();
        String result = executePlan(plan);

        return result;
    }

    // ==================== 规划确认交互 ====================

    /**
     * 展示计划并与用户确认。
     * <p>
     * 用户可以：
     * - 直接回车 / y → 确认执行
     * - "补充 xxx" → 把补充信息加入目标，重新规划
     * - "重新规划" → 重新生成计划
     * - "取消" / "cancel" → 放弃执行，返回 null
     *
     * @param plan 当前计划
     * @param originalGoal 原始目标（重新规划时需要）
     * @return 确认后的计划，或 null（用户取消）
     */
    private ExecutionPlan confirmPlan(ExecutionPlan plan, String originalGoal) throws IOException {
        // 无 Scanner（测试场景）→ 跳过确认
        if (scanner == null) {
            System.out.println(plan.visualize());
            return plan;
        }

        while (true) {
            System.out.println(plan.visualize());
            System.out.println("请确认计划（直接回车执行 / 补充 <内容> / 重新规划 / 取消）：");
            System.out.print("  plan> ");

            if (!scanner.hasNextLine()) {
                return plan;  // EOF，默认执行
            }

            String input = scanner.nextLine().trim();

            // 直接回车 或 y → 确认执行
            if (input.isEmpty() || "y".equalsIgnoreCase(input) || "yes".equalsIgnoreCase(input)) {
                System.out.println("✅ 计划已确认，开始执行...\n");
                return plan;
            }

            // 补充信息 → 把补充内容追加到目标，重新规划
            if (input.startsWith("补充") || input.startsWith("add")) {
                String extra = input.length() > 2
                        ? input.substring(input.startsWith("补充") ? 2 : 3).trim()
                        : "";
                if (extra.isEmpty()) {
                    System.out.println("⚠ 请输入要补充的内容，例如：补充 请使用 Maven 构建");
                    continue;
                }
                String enrichedGoal = originalGoal + "。补充要求：" + extra;
                System.out.println("🧠 根据补充信息重新规划...\n");
                plan = planner.createPlan(enrichedGoal);
                continue;
            }

            // 重新规划 → 从头生成
            if ("重新规划".equals(input) || "regen".equalsIgnoreCase(input)
                    || "regenerate".equalsIgnoreCase(input)) {
                System.out.println("🧠 重新规划中...\n");
                plan = planner.createPlan(originalGoal);
                continue;
            }

            // 取消
            if ("取消".equals(input) || "cancel".equalsIgnoreCase(input)
                    || "n".equalsIgnoreCase(input) || "no".equalsIgnoreCase(input)) {
                plan.setStatus(ExecutionPlan.PlanStatus.CANCELLED);
                System.out.println("🚫 计划已取消");
                return null;
            }

            // 未识别的输入
            System.out.println("⚠ 未识别的操作。请回车确认执行、输入\"补充 <内容>\"、\"重新规划\"或\"取消\"。");
        }
    }

    // ==================== 执行引擎 ====================

    /**
     * 按计划执行任务。
     * <p>
     * 核心循环：
     * 1. 获取当前可执行的任务（依赖都已完成的 PENDING 任务）
     * 2. 逐个执行
     * 3. 成功 → COMPLETED；失败 → FAILED + 下游 SKIPPED
     * 4. 重复直到全部完成或失败
     */
    private String executePlan(ExecutionPlan plan) throws IOException {
        int maxRounds = plan.getTasks().size() + 2;  // 安全上限

        for (int round = 1; round <= maxRounds; round++) {
            // 检查是否全部完成
            if (plan.isAllCompleted()) {
                plan.markCompleted();
                System.out.println("\n✅ 所有任务执行完成！");
                return buildFinalResult(plan);
            }

            // 获取当前可执行的任务
            List<Task> executable = plan.getExecutableTasks();

            if (executable.isEmpty()) {
                // 没有可执行的任务，但也没有全部完成
                if (plan.hasFailed()) {
                    return handleFailure(plan);
                }
                // 卡住了（可能是循环依赖，但拓扑排序应该已检测到）
                log.warn("执行卡住：无可执行任务但未全部完成");
                plan.markFailed();
                return buildFinalResult(plan);
            }

            // 执行本批次任务
            for (Task task : executable) {
                System.out.printf("\n🔄 执行 [%s] %s (%s)%n",
                        task.getId(), task.getDescription(), task.getType());

                try {
                    task.markStarted();
                    String result = executeTask(task, plan);
                    task.markCompleted(result);

                    System.out.printf("  ✅ 完成 | 耗时 %dms | 结果: %s%n",
                            task.elapsedMillis(),
                            truncate(result, 100));

                } catch (Exception e) {
                    task.markFailed(e.getMessage());
                    log.error("任务 {} 执行失败", task.getId(), e);
                    System.out.printf("  ❌ 失败: %s%n", e.getMessage());

                    // 跳过所有下游任务
                    skipDownstream(task, plan);
                }
            }
        }

        plan.markFailed();
        return "[警告] 执行轮数超出上限";
    }

    // ==================== 单任务执行（mini ReAct） ====================

    /**
     * 执行单个任务 —— 内部是一个 mini ReAct 循环。
     * <p>
     * 对于 ANALYSIS / VERIFICATION / PLANNING 类型的任务，需要调用 LLM。
     * 对于 COMMAND / FILE_READ / FILE_WRITE，可以本地直接执行。
     * <p>
     * 但为了简化 demo，所有任务都走 LLM（LLM 可以用工具辅助执行）。
     */
    private String executeTask(Task task, ExecutionPlan plan) throws IOException {
        // 构建任务上下文：告诉 LLM 这个任务是什么、前序任务的结果
        List<LlmClient.Message> messages = new ArrayList<>();

        // 系统提示：你是一个执行者
        messages.add(LlmClient.Message.system(buildTaskPrompt(task, plan)));

        // 用户消息：具体要做什么
        messages.add(LlmClient.Message.user(task.getDescription()));

        // Mini ReAct 循环（最多 5 轮工具调用）
        List<LlmClient.Tool> toolDefs = toolRegistry.getToolDefinitions();

        for (int iteration = 1; iteration <= MAX_TASK_ITERATIONS; iteration++) {
            LlmClient.ChatResponse response = llmClient.chat(messages, toolDefs);

            if (response.hasToolCalls()) {
                // 记录 assistant 消息（含 tool_calls）
                messages.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));

                // 执行工具，收集结果
                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    log.info("  任务 {} 调用工具: {}({})",
                            task.getId(),
                            toolCall.function().name(),
                            toolCall.function().arguments());

                    String result = toolRegistry.executeTool(toolCall);
                    messages.add(LlmClient.Message.tool(toolCall.id(), result));
                }
                // 继续循环，让 LLM 根据工具结果继续

            } else {
                // LLM 认为任务完成
                return response.content();
            }
        }

        return "[警告] 任务达到最大迭代次数 (" + MAX_TASK_ITERATIONS + ")";
    }

    /**
     * 构建单任务的系统提示词。
     * <p>
     * 包含：当前任务信息 + 前序任务的执行结果（作为上下文）。
     */
    private String buildTaskPrompt(Task task, ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个任务执行者。请完成以下任务。\n\n");
        sb.append("当前任务: ").append(task.getDescription()).append("\n");
        sb.append("任务类型: ").append(task.getType()).append("\n\n");

        // 注入前序任务的结果
        if (!task.getDependencies().isEmpty()) {
            sb.append("前序任务的结果:\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep != null && dep.getResult() != null) {
                    sb.append("  [").append(depId).append("] ").append(dep.getDescription())
                            .append(":\n  ").append(truncate(dep.getResult(), 500)).append("\n\n");
                }
            }
        }

        sb.append("请根据以上信息完成任务。如果需要计算或获取信息，请使用工具。完成后直接输出结果。");
        return sb.toString();
    }

    // ==================== 失败处理 ====================

    /**
     * 处理执行失败。
     * <p>
     * 策略：
     * - 如果完成进度 < 50% → 调用 replan 重新规划
     * - 如果完成进度 >= 50% → 汇总已有结果返回
     */
    private String handleFailure(ExecutionPlan plan) throws IOException {
        double progress = plan.getProgress();

        // 找到失败的任务
        Task failedTask = null;
        for (Task task : plan.getTasks().values()) {
            if (task.getStatus() == Task.Status.FAILED) {
                failedTask = task;
                break;
            }
        }

        String failureReason = failedTask != null
                ? "[" + failedTask.getId() + "] " + failedTask.getError()
                : "未知原因";

        System.out.printf("%n⚠ 任务执行失败: %s%n", failureReason);

        if (progress < 0.5) {
            // 进度不够，重新规划
            System.out.println("🔄 进度不足 50%，尝试重新规划...");
            ExecutionPlan newPlan = planner.replan(plan, failureReason);
            System.out.println(newPlan.visualize());
            return executePlan(newPlan);  // 递归执行新计划
        } else {
            // 进度过半，汇总已有结果
            System.out.println("⚠ 进度过半，汇总已有结果");
            plan.markFailed();
            return buildFinalResult(plan);
        }
    }

    /**
     * 跳过失败任务的所有下游依赖。
     * <p>
     * 递归地：失败的 Task → 它的 dependents 标记 SKIPPED → dependents 的 dependents 也 SKIPPED
     */
    private void skipDownstream(Task failedTask, ExecutionPlan plan) {
        for (String dependentId : failedTask.getDependents()) {
            Task dependent = plan.getTask(dependentId);
            if (dependent != null && dependent.getStatus() == Task.Status.PENDING) {
                dependent.markSkipped();
                System.out.printf("  ⏭ 跳过 [%s] %s（上游 %s 失败）%n",
                        dependentId, dependent.getDescription(), failedTask.getId());
                // 递归跳过更下游的任务
                skipDownstream(dependent, plan);
            }
        }
    }

    // ==================== 结果汇总 ====================

    /**
     * 构建最终结果 —— 优先取叶子任务（没有 dependents 的末端节点）的结果。
     * <p>
     * 叶子任务通常是 ANALYSIS 或 VERIFICATION 类型，
     * 它们的结果本身就是对所有前序结果的总结。
     */
    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();

        // 优先取叶子任务的结果
        List<Task> leafTasks = new ArrayList<>();
        for (Task task : plan.getTasks().values()) {
            if (task.getDependents().isEmpty() && task.getResult() != null) {
                leafTasks.add(task);
            }
        }

        if (!leafTasks.isEmpty()) {
            for (Task leaf : leafTasks) {
                sb.append(leaf.getResult()).append("\n");
            }
        } else {
            // 没有叶子任务有结果，汇总所有已完成任务
            sb.append("📊 执行汇总:\n\n");
            for (Task task : plan.getTasks().values()) {
                String icon = switch (task.getStatus()) {
                    case COMPLETED -> "✅";
                    case FAILED -> "❌";
                    case SKIPPED -> "⏭";
                    default -> "⏸";
                };
                sb.append(String.format("  %s [%s] %s", icon, task.getId(), task.getDescription()));
                if (task.getResult() != null) {
                    sb.append("\n     → ").append(truncate(task.getResult(), 200));
                }
                if (task.getError() != null) {
                    sb.append("\n     ⚠ ").append(task.getError());
                }
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    // ==================== 辅助方法 ====================

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\n+", " ");  // 多行压成一行
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
    }
}
