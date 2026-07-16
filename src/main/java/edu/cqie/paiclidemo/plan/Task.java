package edu.cqie.paiclidemo.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务节点 —— Plan-and-Execute 模式中的最小执行单元。
 * <p>
 * 每个 Task 代表计划中的一个步骤，包含：
 * - 做什么（id + description + type）
 * - 当前进展（status + result）
 * - 依赖谁（dependencies：必须先完成这些任务才能执行自己）
 * <p>
 * 状态机：PENDING → RUNNING → COMPLETED / FAILED
 *                                          ↘ SKIPPED（上游失败，本任务被跳过）
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class Task {

    // ==================== 枚举定义 ====================

    /** 任务类型 —— 决定执行策略 */
    public enum Type {
        /** 规划决策：需要 LLM 思考才能完成 */
        PLANNING,
        /** 读文件：本地文件操作，直接执行 */
        FILE_READ,
        /** 写文件：本地文件操作，直接执行 */
        FILE_WRITE,
        /** 执行命令：shell 命令，直接执行 */
        COMMAND,
        /** 分析结果：需要 LLM 对前序结果进行综合分析 */
        ANALYSIS,
        /** 验证正确性：检查前序步骤的输出是否符合预期 */
        VERIFICATION
    }

    /** 任务状态 —— 状态机驱动 */
    public enum Status {
        /** 等待执行（初始状态） */
        PENDING,
        /** 正在执行 */
        RUNNING,
        /** 执行成功 */
        COMPLETED,
        /** 执行失败 */
        FAILED,
        /** 被跳过（上游依赖失败时自动标记） */
        SKIPPED
    }

    // ==================== 字段 ====================

    private final String id;
    private final String description;
    private final Type type;
    private Status status;
    private String result;   // 执行结果
    private String error;    // 错误信息
    private final List<String> dependencies;  // 依赖的任务 ID 列表（我必须等它们完成）
    private final List<String> dependents = new ArrayList<>();  // 依赖我的任务 ID 列表（它们必须等我完成）
    private Instant startTime;
    private Instant endTime;

    // ==================== 构造函数 ====================

    public Task(String id, String description, Type type, List<String> dependencies) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = Status.PENDING;
        this.dependencies = dependencies != null ? dependencies : List.of();
    }

    // ==================== 状态转换（生命周期） ====================

    /** PENDING → RUNNING：开始执行 */
    public void markStarted() {
        this.status = Status.RUNNING;
        this.startTime = Instant.now();
    }

    /** RUNNING → COMPLETED：执行成功，记录结果 */
    public void markCompleted(String result) {
        this.status = Status.COMPLETED;
        this.result = result;
        this.endTime = Instant.now();
    }

    /** RUNNING → FAILED：执行失败，记录错误 */
    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.endTime = Instant.now();
    }

    /** PENDING → SKIPPED：上游失败，本任务被跳过 */
    public void markSkipped() {
        this.status = Status.SKIPPED;
        this.endTime = Instant.now();
    }

    // ==================== 查询方法 ====================

    /** 是否是终态（COMPLETED / FAILED / SKIPPED） */
    public boolean isTerminated() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.SKIPPED;
    }

    /**
     * 判断当前任务是否可以执行。
     * <p>
     * 条件：自身是 PENDING 状态，且所有依赖任务都已 COMPLETED。
     * <p>
     * 这是 DAG 执行的核心判断——只有"时机成熟"的任务才会被触发。
     */
    public boolean isExecutable(Map<String, Task> taskMap) {
        if (status != Status.PENDING) {
            return false;
        }
        for (String depId : dependencies) {
            Task dep = taskMap.get(depId);
            if (dep == null || dep.getStatus() != Status.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /** 添加反向边：记录谁依赖我 */
    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    /** 耗时（毫秒），未完成返回 -1 */
    public long elapsedMillis() {
        if (startTime == null) {
            return -1;
        }
        Instant end = endTime != null ? endTime : Instant.now();
        return end.toEpochMilli() - startTime.toEpochMilli();
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getDescription() { return description; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return dependencies; }
    public List<String> getDependents() { return dependents; }

    @Override
    public String toString() {
        return String.format("[%s] %s (%s) %s",
                id, description, type, status);
    }
}
