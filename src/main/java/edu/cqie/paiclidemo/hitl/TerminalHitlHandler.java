package edu.cqie.paiclidemo.hitl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端 HITL 处理器 —— 基于 BufferedReader/PrintStream 实现人工审批交互。
 * <p>
 * <b>交互方式</b>：
 * <pre>
 *   y / Enter  → 批准本次执行
 *   a          → 批准本次会话所有后续同类工具
 *   n          → 拒绝执行（会提示输入原因）
 *   s          → 跳过本步骤
 * </pre>
 * <p>
 * <b>安全措施</b>：
 * <ul>
 *   <li>{@code requestApproval()} 是 {@code synchronized}，
 *       防止 Multi-Agent 并行场景下多个审批请求同时弹</li>
 *   <li>连续 3 次无效输入自动拒绝（防止死循环）</li>
 *   <li>I/O 异常默认拒绝（fail-safe）</li>
 * </ul>
 * <p>
 * "全部批准"缓存使用 {@link ConcurrentHashMap} 的 KeySet，线程安全。
 *
 * @author Fonzo
 * @date 2026/07/17
 */
public class TerminalHitlHandler implements HitlHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalHitlHandler.class);
    private static final int MAX_INVALID_INPUTS = 3;

    private final BufferedReader reader;
    private final PrintStream out;
    private volatile boolean enabled = true;

    /** "全部批准"缓存：用户选 [a] 后，该工具名后续不再弹审批 */
    private final Set<String> approvedAllTools = ConcurrentHashMap.newKeySet();

    /**
     * 标准构造：使用 System.in 和 System.out。
     */
    public TerminalHitlHandler() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    /**
     * 测试构造：可注入自定义的输入输出流。
     */
    public TerminalHitlHandler(BufferedReader reader, PrintStream out) {
        this.reader = reader;
        this.out = out;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        // 展示审批卡片
        out.println();
        out.println(request.toDisplayText());
        out.println();

        int invalidCount = 0;

        while (invalidCount < MAX_INVALID_INPUTS) {
            out.print("  请选择 [y/a/n/s]: ");
            out.flush();

            String input;
            try {
                input = reader.readLine();
            } catch (IOException e) {
                log.error("读取审批输入失败，默认拒绝", e);
                return ApprovalResult.reject("I/O 异常，自动拒绝");
            }

            if (input == null) {
                // EOF
                return ApprovalResult.reject("输入流关闭，自动拒绝");
            }

            String trimmed = input.trim().toLowerCase();

            switch (trimmed) {
                case "y", "" -> {
                    log.info("HITL approved: {}", request.toolName());
                    return ApprovalResult.approve();
                }
                case "a" -> {
                    approvedAllTools.add(request.toolName());
                    log.info("HITL approved-all: {}", request.toolName());
                    out.println("  ✅ 已批准本次会话所有 [" + request.toolName() + "] 操作");
                    return ApprovalResult.approveAll();
                }
                case "n" -> {
                    out.print("  请输入拒绝原因 (回车跳过): ");
                    out.flush();
                    String reason = "";
                    try {
                        String reasonInput = reader.readLine();
                        if (reasonInput != null && !reasonInput.isBlank()) {
                            reason = reasonInput.trim();
                        }
                    } catch (IOException ignored) {
                    }
                    if (reason.isEmpty()) {
                        reason = "用户手动拒绝";
                    }
                    log.info("HITL rejected: {} - {}", request.toolName(), reason);
                    return ApprovalResult.reject(reason);
                }
                case "s" -> {
                    log.info("HITL skipped: {}", request.toolName());
                    return ApprovalResult.skip();
                }
                default -> {
                    invalidCount++;
                    out.println("  ❌ 无效输入 '" + trimmed + "'，请输入 y/a/n/s");
                }
            }
        }

        // 连续无效输入，自动拒绝
        log.warn("HITL auto-rejected after {} invalid inputs for tool: {}",
                MAX_INVALID_INPUTS, request.toolName());
        out.println("  ⚠️ 连续无效输入，自动拒绝");
        return ApprovalResult.reject("连续无效输入，自动拒绝");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearApprovedAll();
        }
        log.info("HITL {}", enabled ? "已启用" : "已关闭");
    }

    @Override
    public boolean isApprovedAll(String toolName) {
        return approvedAllTools.contains(toolName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllTools.clear();
        log.info("HITL 全部批准缓存已清除");
    }
}
