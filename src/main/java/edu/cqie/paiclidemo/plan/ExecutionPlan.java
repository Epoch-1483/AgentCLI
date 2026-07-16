package edu.cqie.paiclidemo.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DAG 执行计划 —— 管理任务节点的有向无环图。
 * <p>
 * 核心职责：
 * 1. 存储任务节点（addTask）并自动建立双向依赖边
 * 2. 拓扑排序 + 环检测（computeExecutionOrder）
 * 3. 获取当前可执行的任务（getExecutableTasks）
 * 4. 按并行层级分批（getExecutionBatches）
 * 5. 可视化展示计划状态（visualize）
 * <p>
 * 两遍扫描策略：
 * - 第一遍：遍历 JSON 创建 Task 对象，建立 ID → Task 映射
 *   （解决 LLM 输出任务乱序导致的前向引用问题）
 * - 第二遍：根据 ID 映射重建依赖连接
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class ExecutionPlan {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPlan.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // ==================== 字段 ====================

    private final String id;                        // 计划唯一标识
    private final String goal;
    private String summary;                         // 计划摘要

    /** 任务存储：ID → Task，LinkedHashMap 保持插入顺序 */
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    /** 拓扑排序后的执行顺序 */
    private List<String> executionOrder;

    private PlanStatus status = PlanStatus.CREATED;
    private long startTime;                         // 计划开始时间戳
    private long endTime;                           // 计划结束时间戳

    /** 计划状态 */
    public enum PlanStatus {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED                                   // 被取消
    }

    // ==================== 构造函数 ====================

    /**
     * 双参构造：指定计划 ID。
     * 适用于需要持久化或多计划并存的场景。
     */
    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
    }

    /**
     * 单参构造：自动生成 UUID 作为计划 ID（向后兼容）。
     */
    public ExecutionPlan(String goal) {
        this(UUID.randomUUID().toString().substring(0, 8), goal);
    }

    // ==================== 任务管理 ====================

    /**
     * 添加任务并自动建立双向依赖边。
     * <p>
     * 当 Task A 的 dependencies 包含 Task B 时：
     * - A.dependencies → [B]  （A 必须等 B 完成）
     * - B.dependents  → [A]  （A 在等 B）
     * <p>
     * 双向边的好处：执行完 B 后能快速找到"谁在等我"。
     */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        // 建立反向边：告诉被依赖的任务"谁在等你"
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    /** 获取任务（按 ID） */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /** 获取所有任务 */
    public Map<String, Task> getTasks() {
        return Collections.unmodifiableMap(tasks);
    }

    // ==================== DAG 核心方法 ====================

    /**
     * 获取当前可执行的任务列表。
     * <p>
     * "可执行"= PENDING 状态 + 所有依赖已 COMPLETED。
     * 这是 Executor 每轮循环都要调用的方法。
     */
    public List<Task> getExecutableTasks() {
        List<Task> executable = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (task.isExecutable(tasks)) {
                executable.add(task);
            }
        }
        return executable;
    }

    /**
     * 获取根任务（没有任何依赖的入口节点）。
     */
    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 拓扑排序 + 环检测（DFS 算法）。
     * <p>
     * 使用两个集合：
     * - visiting：当前递归栈中的节点（如果 DFS 又碰到它 → 发现环！）
     * - visited：已处理完的节点
     * <p>
     * 排序结果：DFS 后序遍历，天然就是拓扑正序（不需要 reverse）。
     *
     * @return true = 排序成功，false = 检测到环
     */
    public boolean computeExecutionOrder() {
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();  // 递归栈，用于环检测
        List<String> order = new ArrayList<>();

        for (String taskId : tasks.keySet()) {
            if (!visited.contains(taskId)) {
                if (!topologicalSort(taskId, visited, visiting, order)) {
                    log.error("检测到循环依赖！计划无法执行");
                    return false;
                }
            }
        }

        this.executionOrder = order;
        log.info("拓扑排序完成，执行顺序: {}", order);
        return true;
    }

    /**
     * DFS 递归拓扑排序。
     * <p>
     * 后序入列法：先递归处理所有依赖，再把当前节点加入列表。
     * 这样保证"被依赖者"一定排在"依赖者"前面。
     */
    private boolean topologicalSort(String taskId, Set<String> visited,
                                     Set<String> visiting, List<String> order) {
        // 发现环：当前节点已在递归栈中
        if (visiting.contains(taskId)) {
            return false;
        }
        // 已处理过，跳过
        if (visited.contains(taskId)) {
            return true;
        }

        visiting.add(taskId);  // 进入递归栈

        Task task = tasks.get(taskId);
        if (task != null) {
            // 先递归处理所有依赖
            for (String depId : task.getDependencies()) {
                if (!topologicalSort(depId, visited, visiting, order)) {
                    return false;  // 环传播
                }
            }
        }

        visiting.remove(taskId);  // 离开递归栈
        visited.add(taskId);       // 标记为已处理
        order.add(taskId);         // 后序入列
        return true;
    }

    /**
     * 按并行层级分批。
     * <p>
     * 同一批次内的任务互不依赖，可以并行执行。
     * 例如：
     * <pre>
     *   Batch 1: [Task 1, Task 2]     ← 无依赖，可以并行
     *   Batch 2: [Task 3]             ← 依赖 Task 1 和 Task 2
     *   Batch 3: [Task 4, Task 5]     ← 依赖 Task 3，可以并行
     * </pre>
     */
    public List<List<Task>> getExecutionBatches() {
        List<List<Task>> batches = new ArrayList<>();
        Set<String> completed = new HashSet<>();

        // 不断扫描，直到所有任务都被分批
        while (completed.size() < tasks.size()) {
            List<Task> batch = new ArrayList<>();
            for (Task task : tasks.values()) {
                if (!completed.contains(task.getId())
                        && completed.containsAll(task.getDependencies())) {
                    batch.add(task);
                }
            }
            if (batch.isEmpty()) break;  // 剩余任务有循环依赖
            batches.add(batch);
            for (Task t : batch) {
                completed.add(t.getId());
            }
        }
        return batches;
    }

    // ==================== 状态查询 ====================

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    /**
     * 获取执行顺序（懒加载：如果还没排序，自动触发拓扑排序）。
     */
    public List<String> getExecutionOrder() {
        if (executionOrder == null || executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return executionOrder;
    }

    // ==================== 计划生命周期（计时） ====================

    /** 标记计划开始执行，记录开始时间 */
    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /** 标记计划执行完成，记录结束时间 */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    /** 标记计划执行失败，记录结束时间 */
    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    /** 获取计划总耗时（毫秒），未开始返回 0 */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    // ==================== 进度查询 ====================

    /** 所有任务是否都已完成 */
    public boolean isAllCompleted() {
        return tasks.values().stream().allMatch(t -> t.getStatus() == Task.Status.COMPLETED);
    }

    /** 是否有任务失败 */
    public boolean hasFailed() {
        return tasks.values().stream().anyMatch(t -> t.getStatus() == Task.Status.FAILED);
    }

    /** 完成进度（0.0 ~ 1.0） */
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long done = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.Status.COMPLETED)
                .count();
        return (double) done / tasks.size();
    }

    // ==================== 可视化 ====================

    /**
     * 将计划可视化为文本表格。
     * <p>
     * 示例输出：
     * <pre>
     * ╔═══════════════════════════════════════╗
     * ║  📋 执行计划: 创建 Spring Boot 项目    ║
     * ╠════╦══════════════════╦════════╦══════╣
     * ║ ID ║ 描述             ║ 类型   ║ 状态 ║
     * ╠════╬══════════════════╬════════╬══════╣
     * ║ 1  ║ 创建项目结构     ║ COMMAND║ ✅   ║
     * ║ 2  ║ 写 Controller    ║ FILE   ║ ⏳   ║
     * ║ 3  ║ 编译验证         ║ COMMAND║ ⏸    ║
     * ╚════╩══════════════════╩════════╩══════╝
     * </pre>
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📋 执行计划: ").append(compactGoal(goal, 50)).append("\n");
        sb.append("─".repeat(60)).append("\n");

        List<List<Task>> batches = getExecutionBatches();
        for (int i = 0; i < batches.size(); i++) {
            sb.append("  Batch ").append(i + 1).append(":\n");
            for (Task task : batches.get(i)) {
                String icon = getStatusIcon(task.getStatus());
                String deps = task.getDependencies().isEmpty() ? ""
                        : " (← " + String.join(", ", task.getDependencies()) + ")";
                sb.append(String.format("    %s [%s] %s (%s)%s\n",
                        icon, task.getId(), task.getDescription(), task.getType(), deps));
            }
        }

        sb.append("─".repeat(60)).append("\n");
        sb.append(String.format("  进度: %.0f%% (%d/%d)",
                getProgress() * 100,
                tasks.values().stream().filter(t -> t.getStatus() == Task.Status.COMPLETED).count(),
                tasks.size()));
        if (getDuration() > 0) {
            sb.append(String.format(" | 耗时: %dms", getDuration()));
        }
        sb.append("\n");

        return sb.toString();
    }

    private String getStatusIcon(Task.Status status) {
        return switch (status) {
            case PENDING -> "⏸";
            case RUNNING -> "🔄";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭";
        };
    }

    // ==================== 折叠摘要（避免长 DAG 刷满终端） ====================

    /**
     * 输出计划的折叠摘要 —— 只显示关键信息，不展开完整 DAG。
     * <p>
     * 适用于任务数较多的计划：只展示首批执行和最终收敛任务，
     * 避免 visualize() 输出过长占满终端。
     */
    public String summarize() {
        List<List<Task>> batches = getExecutionBatches();
        List<Task> readyTasks = getExecutableTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 计划摘要\n");
        sb.append("   - 目标: ").append(compactGoal(goal, 48)).append('\n');
        sb.append("   - 任务数: ").append(tasks.size())
                .append(" | 并行批次: ").append(batches.size())
                .append(" | 当前可执行: ").append(readyTasks.size())
                .append(" | 状态: ").append(status).append('\n');

        if (!batches.isEmpty()) {
            sb.append("   - 首批执行: ").append(formatTaskList(batches.get(0), 5)).append('\n');
            if (batches.size() > 1) {
                sb.append("   - 最终收敛: ")
                        .append(formatTaskList(batches.get(batches.size() - 1), 5))
                        .append('\n');
            }
        }

        if (getDuration() > 0) {
            sb.append("   - 耗时: ").append(getDuration()).append("ms\n");
        }

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 将长文本压缩到指定长度内（多行压成单行 + 截断加省略号）。
     */
    private String compactGoal(String rawGoal, int maxLength) {
        if (rawGoal == null) return "";
        String singleLine = rawGoal
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll(" {2,}", " ");
        if (singleLine.length() <= maxLength) {
            return singleLine;
        }
        return singleLine.substring(0, maxLength - 3) + "...";
    }

    /**
     * 格式化任务列表为逗号分隔的 ID 字符串，超过 limit 时截断并标注总数。
     */
    private String formatTaskList(List<Task> batch, int limit) {
        if (batch.isEmpty()) {
            return "无";
        }

        List<String> taskIds = batch.stream()
                .map(Task::getId)
                .toList();

        if (taskIds.size() <= limit) {
            return String.join(", ", taskIds);
        }

        return String.join(", ", taskIds.subList(0, limit))
                + " 等 " + taskIds.size() + " 个任务";
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)",
                id, compactGoal(goal, 30), tasks.size(), status);
    }

    // ==================== 两遍扫描解析 ====================

    /**
     * 从 LLM 返回的 JSON 解析出 ExecutionPlan。
     * <p>
     * 两遍扫描策略：
     * - 第一遍：创建所有 Task 对象，建立 ID → Task 映射
     *   （LLM 输出的任务可能乱序，Task 3 可能排在 Task 1 前面，
     *    第一遍先把所有 Task 建好，第二遍再连依赖边，避免空指针）
     * - 第二遍：根据 ID 映射建立双向依赖连接
     * <p>
     * 预期 JSON 格式：
     * <pre>
     * {
     *   "tasks": [
     *     {"id": "1", "description": "创建项目", "type": "COMMAND", "dependencies": []},
     *     {"id": "2", "description": "写代码", "type": "FILE_WRITE", "dependencies": ["1"]},
     *     {"id": "3", "description": "验证", "type": "VERIFICATION", "dependencies": ["1", "2"]}
     *   ]
     * }
     * </pre>
     */
    public static ExecutionPlan parse(String goal, String planJson) throws Exception {

        // 清理可能的 markdown 代码块
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        JsonNode root = mapper.readTree(cleaned);
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(goal);

        // --- 第一遍扫描：创建所有 Task ---
        Map<String, Task> idMap = new LinkedHashMap<>();
        for (JsonNode taskNode : tasksNode) {
            String id = taskNode.path("id").asText();
            String desc = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText("COMMAND");
            Task.Type type;
            try {
                type = Task.Type.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                type = Task.Type.COMMAND;  // 默认回退
            }

            Task task = new Task(id, desc, type, List.of());  // 先不填依赖
            idMap.put(id, task);
        }

        // --- 第二遍扫描：填充依赖关系 ---
        int idx = 0;
        for (JsonNode taskNode : tasksNode) {
            String origId = taskNode.path("id").asText();
            Task task = idMap.get(origId);

            // 解析依赖
            List<String> deps = new ArrayList<>();
            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String depId = depNode.asText();
                    if (idMap.containsKey(depId)) {
                        deps.add(depId);
                    }
                }
            }

            // 用解析后的依赖重新创建 Task（因为 dependencies 是 final 的）
            Task fullTask = new Task(task.getId(), task.getDescription(), task.getType(), deps);
            plan.addTask(fullTask);
            idx++;
        }

        // 拓扑排序 + 环检测
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("计划中存在循环依赖，无法执行");
        }

        log.info("计划解析完成: {} 个任务, {} 个批次",
                plan.getTasks().size(), plan.getExecutionBatches().size());

        return plan;
    }
}
