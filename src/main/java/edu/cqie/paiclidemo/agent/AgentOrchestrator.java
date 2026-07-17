package edu.cqie.paiclidemo.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.memory.MemoryManager;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Agent 编排器 —— Multi-Agent 系统的"主"。
 * <p>
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理（{@link SubAgent}）是从。
 * <p>
 * <b>协作流程</b>：
 * <pre>
 *  1. 用户提交任务 → 编排器交给规划者（PLANNER）
 *  2. 规划者拆解任务 → 编排器解析 JSON 计划
 *  3. 编排器按依赖顺序将子任务分配给执行者（WORKER）
 *  4. 执行者返回结果 → 编排器交给检查者（REVIEWER）
 *  5. 检查者通过则完成，否则带上反馈重新分配给执行者（最多重试 2 次）
 *  6. 所有子任务完成后，编排器汇总返回最终结果
 * </pre>
 * <p>
 * <b>并行策略</b>：
 * <ul>
 *   <li>同一依赖批次内部<b>并行</b>执行（最多 Worker 池大小并发，默认 2）</li>
 *   <li>每个并行步骤使用独立的 {@link ByteArrayOutputStream} 缓冲流式输出，
 *       批次结束后按 step_id 顺序 flush 到 stdout，避免多线程写同一个终端流造成交错</li>
 *   <li>单步批次仍走直连流式路径，保持"实时打字"的观感</li>
 *   <li>Worker 通过 {@link BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用</li>
 *   <li>Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争</li>
 * </ul>
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();  // 序列化/反序列化

    /** 每个步骤被审查拒绝后的最大重试次数 */
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final PrintStream out;

    // ==================== 内部数据结构 ====================

    /**
     * 执行步骤 —— 编排器内部调度的最小单元。
     * <p>
     * 使用 record 保证不可变性，状态变更通过 withXxx 方法返回新实例。
     *
     * @param id           步骤编号（如 "step_1"）
     * @param description  步骤描述
     * @param type         步骤类型（COMMAND / ANALYSIS / VERIFICATION 等）
     * @param dependencies 依赖的前序步骤 id 列表
     * @param result       执行结果（完成前为 null）
     * @param status       当前状态
     */
    record ExecutionStep(String id, String description, String type,
                         List<String> dependencies, String result,
                         StepStatus status) {

        /** 创建一个 PENDING 状态的步骤 */
        static ExecutionStep pending(String id, String description, String type,
                                     List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        /** 标记为 COMPLETED，附带执行结果 */
        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        /** 标记为 FAILED，附带失败原因 */
        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }
    }

    /** 步骤状态枚举 */
    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    // ==================== 构造方法 ====================

    /**
     * 最简构造：只需要 LlmClient（工具注册表和记忆管理器自动创建）。
     */
    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    /**
     * 标准构造：指定 LlmClient 和工具注册表。
     */
    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    /**
     * 含记忆管理器的构造（与原版 paiCLI 一致）。
     * <p>
     * 截图中标红强调的 {@code memoryManager} 参数就在这里：
     * 编排器用它来记录用户任务和多 Agent 协作结果，
     * 使得 Multi-Agent 的产出能进入记忆系统，与普通对话保持连贯。
     *
     * @param llmClient     LLM 客户端（所有 SubAgent 共享）
     * @param toolRegistry  工具注册表（所有 SubAgent 共享）
     * @param memoryManager 记忆管理器（记录用户消息和协作结果）
     */
    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    /**
     * 完整构造：可指定输出流（用于测试时捕获输出）。
     *
     * @param llmClient     LLM 客户端（所有 SubAgent 共享）
     * @param toolRegistry  工具注册表（所有 SubAgent 共享）
     * @param memoryManager 记忆管理器（可为 null，此时不记录记忆）
     * @param out           输出流（null 则默认 System.out）
     */
    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.out = out == null ? System.out : out;

        // 一个规划者
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        // 两个执行者（并行时各占一个）
        this.workers = List.of(
                new SubAgent("worker-1", AgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-2", AgentRole.WORKER, llmClient, toolRegistry)
        );
        // 一个检查者
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
    }

    // ==================== 核心方法：Multi-Agent 协作 ====================

    /**
     * 运行多 Agent 协作任务。
     * <p>
     * 完整流程：规划 → 解析 → 执行 → 汇总。
     *
     * @param userInput 用户的任务描述
     * @return 最终汇总结果
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        if (memoryManager != null) {
            memoryManager.addUserMessage(userInput);
        }

        // ① 规划阶段：让规划者拆解任务
        out.println("═══════════════════════════════════════");
        out.println("  📋 第一阶段：规划");
        out.println("═══════════════════════════════════════");
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentMessage planResult = planner.execute(planMessage, out);
        planner.clearHistory();

        if (planResult.type() == AgentMessage.Type.ERROR) {
            return "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            return "❌ 规划失败：规划者未能生成有效计划";
        }

        // ② 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
        }

        out.println("═══════════════════════════════════════");
        out.println("  📋 执行计划");
        out.println("═══════════════════════════════════════");
        out.println(summarizeSteps(steps) + "\n");

        // ③ 执行阶段：按依赖顺序分配给执行者
        out.println("═══════════════════════════════════════");
        out.println("  ⚡ 第二阶段：执行");
        out.println("═══════════════════════════════════════");

        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;
            }
            batchIndex++;

            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(singleStepCursor % workers.size());
                singleStepCursor++;
                String context = buildStepContext(steps, step);
                runStep(step, steps, retryCount, worker, reviewer, context, out);
                worker.clearHistory();
            } else {
                // 多步批次：真正并行执行
                out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                        + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
                runBatchParallel(executable, steps, retryCount);
            }
        }

        // ④ 处理因前置失败而无法执行的残留步骤
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
            }
        }

        // ⑤ 汇总结果
        String finalResult = buildFinalResult(steps);
        if (memoryManager != null) {
            memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);
        }

        return finalResult;
    }

    // ==================== 计划解析 ====================

    /**
     * 解析规划者输出的 JSON 计划。
     * <p>
     * 支持两种字段名："steps" 或 "tasks"（兼容不同 LLM 的输出格式）。
     * 解析过程中会对步骤进行重编号（step_1, step_2, ...），
     * 并将原始 id 映射为统一的新 id，确保依赖关系正确指向。
     *
     * @param planJson 规划者输出的 JSON 字符串
     * @return 解析后的执行步骤列表（解析失败时返回空列表）
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            // 去除 markdown 代码块标记
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode root = mapper.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");

            // 兼容 "tasks" 字段
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 第一遍：创建步骤（重编号）
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            // 第二遍：建立依赖映射（用新 id 替换原始 id）
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        String mapped = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(mapped);
                    }
                    int idx = stepIndex - 1;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.result(), old.status()));
                    }
                }
                stepIndex++;
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    // ==================== 依赖解析 ====================

    /**
     * 获取当前可执行的步骤。
     * <p>
     * 可执行条件：状态为 PENDING 且所有依赖步骤都已 COMPLETED。
     * 如果某个步骤的依赖中有 FAILED 状态的步骤，它永远不会被选中，
     * 最终会作为"残留 PENDING"被跳过。
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    // ==================== 审查结果解析 ====================

    /**
     * 解析检查者的审批结果。
     * <p>
     * 解析失败时采取<b>保守策略</b>：默认判为"不通过"，
     * 避免在审查者异常输出时让问题结果直接放行。
     *
     * @param reviewContent 审查者的输出内容
     * @return true = 审查通过，false = 审查拒绝
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // JSON 解析失败：关键词兜底
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析检查者反馈的问题。
     * <p>
     * 优先提取 issues 数组，其次 suggestions 数组，最后取 summary 字段。
     * 全部无法提取时返回默认提示。
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    // ==================== 并行执行 ====================

    /**
     * 并行执行一批相互独立的步骤。
     * <p>
     * 每个步骤获取一个 Worker（从 {@link BlockingQueue} 池中取，用完归还），
     * 同时创建独立的 Reviewer 实例（避免对话历史竞争）。
     * 流式输出写入步骤本地的 {@link ByteArrayOutputStream}；
     * 所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "paicli-demo-multi-agent");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                SubAgent worker = null;
                // 每步独立 Reviewer，避免并行对话历史竞争
                SubAgent localReviewer = new SubAgent(
                        "reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);
                try {
                    worker = workerPool.take();  // 阻塞等待可用 Worker
                    runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    if (worker != null) {
                        worker.clearHistory();
                        workerPool.offer(worker);  // 归还 Worker
                    }
                    stepOut.flush();
                }
                return null;
            }));
        }

        // 等待所有并行任务完成
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
        executor.shutdownNow();

        // 按 step_id 顺序 flush 各步骤的缓冲输出
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    // ==================== 单步执行（Worker + Reviewer + 重试） ====================

    /**
     * 执行单个步骤：Worker 执行 → Reviewer 审查 → 最多重试 2 次。
     * <p>
     * 此方法被串行和并行两条路径共享，通过 {@code out} 参数控制流式输出目的地：
     * <ul>
     *   <li>串行路径：{@code out} = System.out，用户实时看到输出</li>
     *   <li>并行路径：{@code out} = 步骤独立的缓冲流，完成后统一 flush</li>
     * </ul>
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());

        // Worker 执行
        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(taskMsg, context, out);

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        // Reviewer 审查
        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("⚠️ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，保留当前执行结果\n");
            updateStep(steps, step.id(), step.withResult(result.content()));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        // 审查未通过 → 重试循环
        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            // 带反馈的重试
            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext, out);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();

            // 重试后再次审查
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out);
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = true;  // Reviewer 异常时保守接受结果
                issues = "";
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
        } else {
            out.println("⚠️ 步骤 [" + step.id() + "] 超过最大重试次数，保留当前结果\n");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 线程安全地更新步骤列表中的指定步骤。
     * <p>
     * 并行执行时多个线程可能同时调用此方法，
     * 使用 synchronized 保证列表修改的原子性。
     */
    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 构建步骤的上下文信息。
     * <p>
     * 将当前步骤所依赖的前序步骤的执行结果（截断 500 字符）拼接，
     * 让 Worker 了解已完成的前置工作。
     */
    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    /** 格式化步骤列表为可读的文本摘要 */
    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总结果。
     * <p>
     * Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }

    // ==================== Getters ====================

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }
}
