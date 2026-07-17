package edu.cqie.paiclidemo.hitl;

import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HITL 工具注册表 —— 在工具执行前插入人工审批拦截。
 * <p>
 * 继承 {@link ToolRegistry}，重写 {@link #executeTool(LlmClient.ToolCall)} 方法：
 * <ol>
 *   <li>如果 HITL 未启用，或工具不需要审批 → 直接调用父类执行</li>
 *   <li>如果该工具已被"全部批准" → 直接调用父类执行</li>
 *   <li>否则弹出审批卡片，根据用户决策执行/拒绝/跳过</li>
 * </ol>
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public class HitlToolRegistry extends ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(HitlToolRegistry.class);

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        this.hitlHandler = hitlHandler;
    }

    /**
     * 重写工具执行：在执行前检查是否需要人工审批。
     * <p>
     * 流程：
     * <pre>
     *   HITL 未启用？         → 直接执行
     *   工具不在危险名单？     → 直接执行
     *   已"全部批准"该工具？   → 直接执行
     *   否则                  → 弹审批卡片 → 根据决策执行/拒绝/跳过
     * </pre>
     */
    @Override
    public String executeTool(LlmClient.ToolCall toolCall) {
        String toolName = toolCall.function().name();
        String argsJson = toolCall.function().arguments();

        // ① HITL 未启用 → 直接执行
        if (!hitlHandler.isEnabled()) {
            return super.executeTool(toolCall);
        }

        // ② 工具不在危险名单 → 直接执行
        if (!ApprovalPolicy.requiresApproval(toolName)) {
            return super.executeTool(toolCall);
        }

        // ③ 已"全部批准"该工具 → 直接执行
        if (hitlHandler.isApprovedAll(toolName)) {
            log.info("HITL auto-approved (cached): {}", toolName);
            return super.executeTool(toolCall);
        }

        // ④ 弹出审批请求
        ApprovalRequest request = ApprovalRequest.of(
                toolName, argsJson,
                null,  // suggestion 由调用方按需传入
                null   // callerContext 由调用方按需传入
        );

        ApprovalResult result = hitlHandler.requestApproval(request);

        return switch (result.decision()) {
            case APPROVED -> {
                log.info("HITL approved: {}", toolName);
                yield super.executeTool(toolCall);
            }
            case APPROVED_ALL -> {
                log.info("HITL approved-all: {}", toolName);
                yield super.executeTool(toolCall);
            }
            case REJECTED -> {
                String reason = result.reason() != null ? result.reason() : "用户拒绝";
                log.info("HITL rejected: {} - {}", toolName, reason);
                yield "[HITL] 操作被拒绝: " + reason;
            }
            case SKIPPED -> {
                log.info("HITL skipped: {}", toolName);
                yield "[HITL] 操作已跳过: " + toolName;
            }
        };
    }

    /**
     * 获取 HITL 处理器（供外部查询状态）。
     */
    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
