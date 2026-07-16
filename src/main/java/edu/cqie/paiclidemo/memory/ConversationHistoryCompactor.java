package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对话历史压缩器 - 压缩 Agent 实际发给 LLM 的消息列表。
 * <p>
 * 与 {@link ContextCompressor} 的区别：
 * <ul>
 *   <li>ContextCompressor 压的是 ConversationMemory（短期记忆条目）</li>
 *   <li>本类压的是 Agent 的 conversationHistory（{@code List<LlmClient.Message>}）</li>
 * </ul>
 * <p>
 * 算法：
 * <ol>
 *   <li>估算 conversationHistory 当前 token，未达阈值则跳过</li>
 *   <li>找到 user message 索引，保留最近 N 轮的尾部</li>
 *   <li>把分割点之前的消息交给 LLM 生成摘要</li>
 *   <li>重建: [system] + [摘要消息] + [assistant确认] + [尾部保留消息]</li>
 * </ol>
 * <p>
 * 关键约束：分割点必然落在 user message 边界，避免切断 tool_call/tool_result 配对。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已完成的关键操作（工具调用及核心结果）
            3. 已达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用。
            输出 1-3 段中文，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private final LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    /**
     * 评估并按需压缩对话历史。
     *
     * @param history       Agent 的 conversationHistory（原地修改）
     * @param triggerTokens 触发压缩的 token 阈值
     * @return 是否执行了压缩
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        return compact(history, triggerTokens, false, retainRecentRounds);
    }

    /**
     * 立即压缩对话历史（跳过阈值判断）。
     *
     * @param history Agent 的 conversationHistory（原地修改）
     * @return 是否执行了压缩
     */
    public boolean compactNow(List<LlmClient.Message> history) {
        return compact(history, 0, true, 1);
    }

    // ==================== 核心压缩逻辑 ====================

    private boolean compact(List<LlmClient.Message> history, int triggerTokens,
                            boolean force, int retainRounds) {
        if (history == null || history.isEmpty()) {
            return false;
        }

        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (!force && currentTokens < triggerTokens) {
            return false;
        }

        // 找到 system 消息的结束位置
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        // 收集所有 user message 索引
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }

        int effectiveRetain = Math.max(1, retainRounds);
        if (userIndices.size() <= effectiveRetain) {
            log.info("compactIfNeeded skip: only {} user turns, <= retain {}",
                    userIndices.size(), effectiveRetain);
            return false;
        }

        // 分割点：从后往前第 retainRounds 个 user message
        int splitIdx = userIndices.get(userIndices.size() - effectiveRetain);
        if (splitIdx <= systemEnd) {
            return false;
        }

        // 需要压缩的旧消息
        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) {
            return false;
        }

        // 调用 LLM 生成摘要
        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (IOException e) {
            log.warn("对话摘要 LLM 调用失败，跳过压缩", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("对话摘要为空，跳过压缩");
            return false;
        }

        // 重建消息列表
        List<LlmClient.Message> rebuilt = new ArrayList<>();
        // 1. 保留 system 消息
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        // 2. 注入摘要
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        // 3. 保留近期消息
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);

        log.info(String.format(Locale.ROOT,
                "对话历史压缩: tokens %d -> %d, 保留最近 %d 轮",
                currentTokens, afterTokens, effectiveRetain));
        return true;
    }

    /**
     * 调用 LLM 生成对话摘要。
     * protected 可见，便于测试时通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }

        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }

        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    public int getRetainRecentRounds() {
        return retainRecentRounds;
    }
}
