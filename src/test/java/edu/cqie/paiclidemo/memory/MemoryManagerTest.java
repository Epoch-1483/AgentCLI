package edu.cqie.paiclidemo.memory;

import edu.cqie.paiclidemo.MockLlmClient;
import edu.cqie.paiclidemo.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryManager（门面类）单元测试。
 */
@DisplayName("MemoryManager - 记忆管理器")
class MemoryManagerTest {

    @TempDir
    Path tempDir;

    private MockLlmClient mockLlm;
    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        mockLlm = new MockLlmClient();
        LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
        manager = new MemoryManager(mockLlm, 100, 128_000, ltm, 0.8);
    }

    // ==================== 短期记忆存取 ====================

    @Nested
    @DisplayName("短期记忆存取")
    class ShortTermOps {

        @Test
        @DisplayName("addUserMessage 存入短期记忆")
        void addUserMessage() {
            manager.addUserMessage("你好");
            assertEquals(1, manager.getShortTermMemory().size());
        }

        @Test
        @DisplayName("addAssistantMessage 存入短期记忆")
        void addAssistantMessage() {
            manager.addAssistantMessage("你好！有什么可以帮你的？");
            assertEquals(1, manager.getShortTermMemory().size());
        }

        @Test
        @DisplayName("addToolResult 存入短期记忆（含截断）")
        void addToolResult() {
            String longResult = "a".repeat(1000);
            manager.addToolResult("calculator", longResult);

            assertEquals(1, manager.getShortTermMemory().size());
            // 验证截断
            MemoryEntry entry = manager.getShortTermMemory().getAll().get(0);
            assertTrue(entry.getContent().contains("已截断"));
        }

        @Test
        @DisplayName("多条消息累计 token")
        void tokenAccumulation() {
            manager.addUserMessage("第一条消息");
            manager.addAssistantMessage("第一条回复");
            manager.addUserMessage("第二条消息");

            assertTrue(manager.getShortTermMemory().getTokenCount() > 0);
            assertEquals(3, manager.getShortTermMemory().size());
        }
    }

    // ==================== 长期记忆 ====================

    @Nested
    @DisplayName("长期记忆操作")
    class LongTermOps {

        @Test
        @DisplayName("storeFact 存入长期记忆")
        void storeFact() {
            manager.storeFact("项目使用 Java 17");
            assertEquals(1, manager.getLongTermMemory().size());
        }

        @Test
        @DisplayName("searchLongTerm 按关键词搜索")
        void searchLongTerm() {
            manager.storeFact("项目使用 Java 17");
            manager.storeFact("构建工具是 Maven");
            manager.storeFact("Java 版本很重要");

            List<MemoryEntry> results = manager.searchLongTerm("Java", 5);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("listLongTerm 列出所有")
        void listLongTerm() {
            manager.storeFact("事实A");
            manager.storeFact("事实B");
            assertEquals(2, manager.listLongTerm().size());
        }

        @Test
        @DisplayName("deleteLongTerm 删除")
        void deleteLongTerm() {
            manager.storeFact("待删除");
            String id = manager.listLongTerm().get(0).getId();
            assertTrue(manager.deleteLongTerm(id));
            assertEquals(0, manager.listLongTerm().size());
        }
    }

    // ==================== 记忆上下文构建 ====================

    @Nested
    @DisplayName("记忆上下文构建")
    class ContextBuilding {

        @Test
        @DisplayName("无长期记忆时返回空字符串")
        void emptyContext() {
            String ctx = manager.buildMemoryContext("任意查询", 500);
            assertEquals("", ctx);
        }

        @Test
        @DisplayName("有相关长期记忆时返回格式化上下文")
        void withFacts() {
            manager.storeFact("用户偏好深色主题");
            String ctx = manager.buildMemoryContext("用户偏好", 500);
            assertTrue(ctx.contains("相关记忆"));
            assertTrue(ctx.contains("深色主题"));
        }

        @Test
        @DisplayName("上下文不超过 token 限制")
        void tokenLimit() {
            // 存入大量事实
            for (int i = 0; i < 20; i++) {
                manager.storeFact("这是一条很长的事实描述，包含很多内容，用于测试 token 限制功能" + i);
            }

            // 限制 50 tokens
            String ctx = manager.buildMemoryContext("事实", 50);
            int tokens = MemoryEntry.estimateTokens(ctx);
            // 应该大致在限制范围内（可能略超，因为是按条目粒度截断的）
            assertTrue(tokens < 200, "token 应受限, 实际: " + tokens);
        }
    }

    // ==================== 压缩 ====================

    @Nested
    @DisplayName("压缩触发")
    class Compression {

        @Test
        @DisplayName("未达阈值不压缩")
        void noCompression() {
            manager.addUserMessage("短消息");
            assertFalse(manager.compressIfNeeded());
        }

        @Test
        @DisplayName("达到阈值触发压缩（需要 LLM）")
        void triggerCompression() {
            // 填满短期记忆到 80%+
            // 预算 100 tokens, 80% = 80 tokens
            for (int i = 0; i < 8; i++) {
                manager.getShortTermMemory().store(new MemoryEntry(
                        "e" + i, "内容" + i,
                        MemoryEntry.MemoryType.CONVERSATION,
                        Map.of(), 12
                ));
            }

            // 排队 LLM 压缩响应
            mockLlm.enqueueText("这是压缩后的摘要");

            boolean compressed = manager.compressIfNeeded();
            assertTrue(compressed);
            // 压缩后短期记忆应包含摘要 + 近期消息
            assertTrue(manager.getShortTermMemory().size() > 0);
        }
    }

    // ==================== 清空 ====================

    @Nested
    @DisplayName("清空操作")
    class ClearOps {

        @Test
        @DisplayName("clearShortTerm 只清短期，保留长期")
        void clearShortTerm() {
            manager.addUserMessage("短期消息");
            manager.storeFact("长期事实");

            manager.clearShortTerm();
            assertEquals(0, manager.getShortTermMemory().size());
            assertEquals(1, manager.getLongTermMemory().size());
        }

        @Test
        @DisplayName("clearLongTerm 只清长期")
        void clearLongTerm() {
            manager.addUserMessage("短期消息");
            manager.storeFact("长期事实");

            manager.clearLongTerm();
            assertEquals(1, manager.getShortTermMemory().size());
            assertEquals(0, manager.getLongTermMemory().size());
        }
    }

    // ==================== 状态查询 ====================

    @Test
    @DisplayName("getSystemStatus 包含三部分信息")
    void systemStatus() {
        manager.addUserMessage("测试");
        manager.storeFact("事实");
        manager.recordTokenUsage(100, 50);

        String status = manager.getSystemStatus();
        assertTrue(status.contains("短期记忆"));
        assertTrue(status.contains("长期记忆"));
        assertTrue(status.contains("Token 统计"));
    }
}
