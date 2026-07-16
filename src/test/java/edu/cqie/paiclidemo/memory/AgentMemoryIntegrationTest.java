package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.agent.Agent;
import edu.cqie.paiclidemo.llm.LlmClient;
import edu.cqie.paiclidemo.tool.ToolRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static edu.cqie.paiclidemo.tool.ToolRegistry.createParameters;
import static edu.cqie.paiclidemo.tool.ToolRegistry.Param;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent + Memory 集成测试。
 * 验证 Agent 在有 Memory 的情况下：
 * - 消息被正确记录到短期记忆
 * - 长期记忆被注入到 system prompt
 * - clearHistory 触发事实提取
 */
@DisplayName("Agent + Memory 集成测试")
class AgentMemoryIntegrationTest {

    @TempDir
    Path tempDir;

    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register("add", "加法",
                createParameters(new Param("a", "string", ""), new Param("b", "string", "")),
                args -> {
                    int a = Integer.parseInt(args.get("a"));
                    int b = Integer.parseInt(args.get("b"));
                    return String.valueOf(a + b);
                });
    }

    private MemoryManager createMemoryManager() {
        LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
        return new MemoryManager(mockLlm, 500, 128_000, ltm, 0.8);
    }

    // ==================== 短期记忆记录 ====================

    @Nested
    @DisplayName("短期记忆记录")
    class ShortTermRecording {

        @Test
        @DisplayName("用户消息被记录到短期记忆")
        void userMessageRecorded() throws IOException {
            mockLlm.enqueueText("你好！");

            MemoryManager mm = createMemoryManager();
            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            agent.run("你好");

            assertTrue(mm.getShortTermMemory().size() >= 1,
                    "短期记忆应至少有 1 条（用户消息）");
        }

        @Test
        @DisplayName("助手回复被记录到短期记忆")
        void assistantMessageRecorded() throws IOException {
            mockLlm.enqueueText("我是助手");

            MemoryManager mm = createMemoryManager();
            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            agent.run("你是谁？");

            // 应该有 user + assistant = 至少 2 条
            assertTrue(mm.getShortTermMemory().size() >= 2);
        }

        @Test
        @DisplayName("工具结果被记录到短期记忆")
        void toolResultRecorded() throws IOException {
            // 第一次：调用工具
            mockLlm.enqueue(MockLlmClient.toolCallResponse(
                    MockLlmClient.toolCall("tc1", "add", "{\"a\": \"1\", \"b\": \"2\"}")
            ));
            // 第二次：给出最终回复
            mockLlm.enqueueText("1+2=3");

            MemoryManager mm = createMemoryManager();
            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            agent.run("计算 1+2");

            // 应该有 user + tool_result + assistant = 至少 3 条
            boolean hasToolResult = mm.getShortTermMemory().getAll().stream()
                    .anyMatch(e -> e.getType() == MemoryEntry.MemoryType.TOOL_RESULT);
            assertTrue(hasToolResult, "应记录工具结果到短期记忆");
        }

        @Test
        @DisplayName("token 使用被记录")
        void tokenUsageRecorded() throws IOException {
            mockLlm.enqueueText("回复");

            MemoryManager mm = createMemoryManager();
            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            agent.run("测试");

            assertTrue(mm.getTokenBudget().getLlmCallCount() > 0);
            assertTrue(mm.getTokenBudget().getTotalInputTokens() > 0);
        }
    }

    // ==================== 长期记忆注入 ====================

    @Nested
    @DisplayName("长期记忆注入到 system prompt")
    class LongTermInjection {

        @Test
        @DisplayName("有长期记忆时 system prompt 包含记忆上下文")
        void memoryInSystemPrompt() throws IOException {
            mockLlm.enqueueText("好的");

            MemoryManager mm = createMemoryManager();
            mm.storeFact("用户偏好 Java 语言");

            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            // 查询中包含 "Java" 关键词，匹配长期记忆
            agent.run("帮我写 Java 代码");

            // 检查 conversationHistory 的 system 消息是否包含长期记忆
            List<LlmClient.Message> history = agent.getConversationHistory();
            LlmClient.Message systemMsg = history.get(0);
            assertTrue(systemMsg.content().contains("Java"),
                    "system prompt 应包含长期记忆中的 Java 关键词");
        }

        @Test
        @DisplayName("无长期记忆时 system prompt 正常")
        void noLongTermMemory() throws IOException {
            mockLlm.enqueueText("好的");

            MemoryManager mm = createMemoryManager();
            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            agent.run("你好");

            List<LlmClient.Message> history = agent.getConversationHistory();
            LlmClient.Message systemMsg = history.get(0);
            assertEquals("system", systemMsg.role());
            assertNotNull(systemMsg.content());
        }
    }

    // ==================== clearHistory ====================

    @Nested
    @DisplayName("clearHistory 行为")
    class ClearHistory {

        @Test
        @DisplayName("clearHistory 清空对话但保留长期记忆")
        void clearPreservesLongTerm() throws IOException {
            mockLlm.enqueueText("回复1");
            mockLlm.enqueueText("回复2");

            MemoryManager mm = createMemoryManager();
            mm.storeFact("重要事实");

            Agent agent = new Agent(mockLlm, toolRegistry, mm);
            agent.run("第一轮对话");
            agent.clearHistory();

            // 对话历史应重置（只有 system prompt）
            assertEquals(1, agent.getConversationHistory().size());
            assertEquals("system", agent.getConversationHistory().get(0).role());

            // 长期记忆仍在
            assertEquals(1, mm.getLongTermMemory().size());
        }

        @Test
        @DisplayName("无 Memory 时 clearHistory 正常工作")
        void clearWithoutMemory() throws IOException {
            mockLlm.enqueueText("回复");

            Agent agent = new Agent(mockLlm, toolRegistry); // 无 Memory
            agent.run("测试");
            agent.clearHistory();

            assertEquals(1, agent.getConversationHistory().size());
        }
    }

    // ==================== 向后兼容 ====================

    @Test
    @DisplayName("无 Memory 的 Agent 正常工作（向后兼容）")
    void backwardCompatible() throws IOException {
        mockLlm.enqueueText("你好！");

        Agent agent = new Agent(mockLlm, toolRegistry);
        String result = agent.run("你好");

        assertEquals("你好！", result);
        assertNull(agent.getMemoryManager());
    }
}
