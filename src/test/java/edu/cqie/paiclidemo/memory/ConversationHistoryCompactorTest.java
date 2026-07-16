package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationHistoryCompactor（对话历史压缩器）单元测试。
 */
@DisplayName("ConversationHistoryCompactor - 对话历史压缩")
class ConversationHistoryCompactorTest {

    private MockLlmClient mockLlm;
    private ConversationHistoryCompactor compactor;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        compactor = new ConversationHistoryCompactor(mockLlm, 2); // 保留最近 2 轮
    }

    /** 构建一条包含 N 轮对话的消息列表 */
    private List<LlmClient.Message> buildHistory(int rounds) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("你是一个助手"));

        for (int i = 1; i <= rounds; i++) {
            history.add(LlmClient.Message.user("用户消息 " + i));
            history.add(LlmClient.Message.assistant("助手回复 " + i));
        }
        return history;
    }

    // ==================== 触发条件 ====================

    @Nested
    @DisplayName("触发条件")
    class TriggerCondition {

        @Test
        @DisplayName("token 未达阈值不压缩")
        void noCompressionBelowThreshold() {
            List<LlmClient.Message> history = buildHistory(3);
            boolean result = compactor.compactIfNeeded(history, 999_999);
            assertFalse(result);
        }

        @Test
        @DisplayName("轮次不足不压缩（user turns <= retain）")
        void noCompressionInsufficientRounds() {
            List<LlmClient.Message> history = buildHistory(2);
            // 只有 2 轮 user 消息, retain = 2, 所以不压缩
            boolean result = compactor.compactIfNeeded(history, 1);
            assertFalse(result);
        }

        @Test
        @DisplayName("空列表不压缩")
        void emptyHistory() {
            List<LlmClient.Message> history = new ArrayList<>();
            assertFalse(compactor.compactIfNeeded(history, 1));
        }

        @Test
        @DisplayName("null 列表不压缩")
        void nullHistory() {
            assertFalse(compactor.compactIfNeeded(null, 1));
        }
    }

    // ==================== 压缩执行 ====================

    @Nested
    @DisplayName("压缩执行")
    class CompressionExecution {

        @Test
        @DisplayName("compactNow 强制压缩")
        void compactNow() throws IOException {
            List<LlmClient.Message> history = buildHistory(5);
            int originalSize = history.size();

            // LLM 返回摘要
            mockLlm.enqueueText("这是压缩后的对话摘要，包含了之前讨论的关键信息。");

            boolean result = compactor.compactNow(history);
            assertTrue(result);
            assertTrue(history.size() < originalSize, "压缩后消息数应减少");

            // 验证结构：system + 摘要user + 确认assistant + 保留的近期消息
            assertEquals("system", history.get(0).role());
            assertTrue(history.get(1).content().contains("已压缩的历史对话摘要"));
            assertEquals("assistant", history.get(2).role());
        }

        @Test
        @DisplayName("compactNow 保留最近 1 轮消息（compactNow 内部用 retain=1）")
        void retainRecentRounds() {
            List<LlmClient.Message> history = buildHistory(5);

            mockLlm.enqueueText("压缩摘要");

            compactor.compactNow(history);

            // compactNow() 内部 retainRounds=1，只保留最后 1 轮
            boolean hasUser5 = history.stream()
                    .anyMatch(m -> "user".equals(m.role()) && m.content().contains("用户消息 5"));
            boolean hasUser1 = history.stream()
                    .anyMatch(m -> "user".equals(m.role()) && m.content().contains("用户消息 1"));

            assertTrue(hasUser5, "应保留最后一轮");
            assertFalse(hasUser1, "第 1 轮应被压缩");
        }

        @Test
        @DisplayName("LLM 调用失败时跳过压缩")
        void llmFailureSkipsCompression() {
            List<LlmClient.Message> history = buildHistory(5);
            int originalSize = history.size();

            // 使用 null LLM client 的 compactor，summarize() 会抛 IOException
            ConversationHistoryCompactor nullCompactor =
                    new ConversationHistoryCompactor(null, 2);

            boolean result = nullCompactor.compactNow(history);
            assertFalse(result);
            assertEquals(originalSize, history.size());
        }

        @Test
        @DisplayName("LLM 返回空摘要时跳过压缩")
        void emptySummarySkipsCompression() {
            List<LlmClient.Message> history = buildHistory(5);

            mockLlm.enqueueText("");

            boolean result = compactor.compactNow(history);
            assertFalse(result);
        }
    }

    // ==================== 阈值触发 ====================

    @Test
    @DisplayName("compactIfNeeded 达到阈值时触发")
    void compactIfNeededTriggered() {
        List<LlmClient.Message> history = buildHistory(5);

        mockLlm.enqueueText("阈值触发的摘要");

        // 阈值设为 1（极低），确保触发
        boolean result = compactor.compactIfNeeded(history, 1);
        assertTrue(result);
    }

    // ==================== 构造参数 ====================

    @Test
    @DisplayName("retainRecentRounds getter 正确")
    void retainRecentRoundsGetter() {
        assertEquals(2, compactor.getRetainRecentRounds());

        ConversationHistoryCompactor c2 = new ConversationHistoryCompactor(mockLlm, 5);
        assertEquals(5, c2.getRetainRecentRounds());
    }

    @Test
    @DisplayName("retainRecentRounds 至少为 1")
    void retainMinimum() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(mockLlm, 0);
        assertEquals(1, c.getRetainRecentRounds());
    }
}
