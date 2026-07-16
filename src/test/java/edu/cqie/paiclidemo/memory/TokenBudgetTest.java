package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.llm.LlmClient;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenBudget 单元测试。
 */
@DisplayName("TokenBudget - Token 预算管理")
class TokenBudgetTest {

    @Nested
    @DisplayName("预算计算")
    class BudgetCalc {

        @Test
        @DisplayName("默认可用预算 = 窗口 - 各项预留")
        void defaultAvailable() {
            TokenBudget budget = new TokenBudget(128_000);
            // 128000 - 500 - 800 - 2000 = 124700
            assertEquals(124_700, budget.getAvailableForConversation());
        }

        @Test
        @DisplayName("自定义预留参数")
        void customReserves() {
            TokenBudget budget = new TokenBudget(1000, 100, 200, 300);
            assertEquals(400, budget.getAvailableForConversation());
        }

        @Test
        @DisplayName("小窗口模型")
        void smallWindow() {
            TokenBudget budget = new TokenBudget(4096);
            // 4096 - 500 - 800 - 2000 = 796
            assertEquals(796, budget.getAvailableForConversation());
        }
    }

    @Nested
    @DisplayName("压缩触发判断")
    class CompressionTrigger {

        @Test
        @DisplayName("未达阈值不触发压缩")
        void noCompression() {
            ConversationMemory mem = new ConversationMemory(100);
            mem.store(new MemoryEntry("e1", "短内容",
                    MemoryEntry.MemoryType.CONVERSATION, Map.of(), 10));

            TokenBudget budget = new TokenBudget(128_000);
            assertFalse(budget.needsCompression(mem, 0.8));
        }

        @Test
        @DisplayName("达到阈值触发压缩")
        void triggerCompression() {
            ConversationMemory mem = new ConversationMemory(100);
            mem.store(new MemoryEntry("e1", "长内容",
                    MemoryEntry.MemoryType.CONVERSATION, Map.of(), 85));

            TokenBudget budget = new TokenBudget(128_000);
            assertTrue(budget.needsCompression(mem, 0.8));
        }

        @Test
        @DisplayName("恰好等于阈值")
        void exactThreshold() {
            ConversationMemory mem = new ConversationMemory(100);
            mem.store(new MemoryEntry("e1", "内容",
                    MemoryEntry.MemoryType.CONVERSATION, Map.of(), 80));

            TokenBudget budget = new TokenBudget(128_000);
            assertTrue(budget.needsCompression(mem, 0.8));
        }
    }

    @Nested
    @DisplayName("Token 使用统计")
    class UsageTracking {

        @Test
        @DisplayName("recordUsage 累计统计")
        void recordUsage() {
            TokenBudget budget = new TokenBudget(128_000);
            budget.recordUsage(100, 50);
            budget.recordUsage(200, 80);

            assertEquals(300, budget.getTotalInputTokens());
            assertEquals(130, budget.getTotalOutputTokens());
            assertEquals(2, budget.getLlmCallCount());
        }

        @Test
        @DisplayName("getUsageReport 包含关键信息")
        void usageReport() {
            TokenBudget budget = new TokenBudget(128_000);
            budget.recordUsage(100, 50);

            String report = budget.getUsageReport();
            assertTrue(report.contains("调用 1 次"));
            assertTrue(report.contains("总输入: 100"));
            assertTrue(report.contains("总输出: 50"));
        }
    }

    @Nested
    @DisplayName("消息 Token 估算")
    class MessageEstimation {

        @Test
        @DisplayName("estimateMessagesTokens 正确估算")
        void estimateMessages() {
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.system("系统提示"),
                    LlmClient.Message.user("你好"),
                    LlmClient.Message.assistant("你好！有什么可以帮你的？")
            );

            int tokens = TokenBudget.estimateMessagesTokens(messages);
            assertTrue(tokens > 0);
            // 3 条消息 × 4 overhead + 内容 token
            assertTrue(tokens >= 12, "至少包含消息开销");
        }

        @Test
        @DisplayName("null 列表返回 0")
        void nullMessages() {
            assertEquals(0, TokenBudget.estimateMessagesTokens(null));
        }

        @Test
        @DisplayName("带 ToolCall 的消息额外计算参数 tokens")
        void withToolCalls() {
            LlmClient.ToolCall tc = new LlmClient.ToolCall("tc1",
                    new LlmClient.ToolCall.Function("calc", "{\"expression\": \"2+3\"}"));
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.assistant("调用工具", List.of(tc))
            );

            int tokens = TokenBudget.estimateMessagesTokens(messages);
            assertTrue(tokens > 4, "应包含工具参数 token");
        }

        @Test
        @DisplayName("isWithinBudget 判断正确")
        void isWithinBudget() {
            TokenBudget budget = new TokenBudget(1000, 0, 0, 0);
            // 可用 = 1000
            List<LlmClient.Message> small = List.of(LlmClient.Message.user("hi"));
            assertTrue(budget.isWithinBudget(small));
        }
    }
}
