package edu.cqie.paiclidemo.memory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LongTermMemory（长期记忆）单元测试。
 * 使用 @TempDir 确保测试不影响实际文件系统。
 */
@DisplayName("LongTermMemory - 长期记忆")
class LongTermMemoryTest {

    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    private MemoryEntry makeFact(String id, String content) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT,
                Map.of("source", "test"), MemoryEntry.estimateTokens(content));
    }

    // ==================== 基本操作 ====================

    @Nested
    @DisplayName("基本 CRUD 操作")
    class BasicCRUD {

        @Test
        @DisplayName("store + retrieve")
        void storeAndRetrieve() {
            memory.store(makeFact("f1", "用户偏好使用深色主题"));
            assertEquals(1, memory.size());

            var found = memory.retrieve("f1");
            assertTrue(found.isPresent());
            assertEquals("用户偏好使用深色主题", found.get().getContent());
        }

        @Test
        @DisplayName("去重：内容完全相同的不重复存储")
        void deduplication() {
            memory.store(makeFact("f1", "相同内容"));
            memory.store(makeFact("f2", "相同内容"));
            assertEquals(1, memory.size());
        }

        @Test
        @DisplayName("不同内容正常存储")
        void differentContent() {
            memory.store(makeFact("f1", "内容A"));
            memory.store(makeFact("f2", "内容B"));
            assertEquals(2, memory.size());
        }

        @Test
        @DisplayName("delete 删除成功")
        void delete() {
            memory.store(makeFact("f1", "待删除"));
            assertTrue(memory.delete("f1"));
            assertEquals(0, memory.size());
        }

        @Test
        @DisplayName("delete 不存在的返回 false")
        void deleteNonExistent() {
            assertFalse(memory.delete("not-exist"));
        }

        @Test
        @DisplayName("clear 清空所有")
        void clear() {
            memory.store(makeFact("f1", "A"));
            memory.store(makeFact("f2", "B"));
            memory.clear();
            assertEquals(0, memory.size());
            assertEquals(0, memory.getTokenCount());
        }

        @Test
        @DisplayName("getAll 返回所有条目")
        void getAll() {
            memory.store(makeFact("f1", "事实1"));
            memory.store(makeFact("f2", "事实2"));
            memory.store(makeFact("f3", "事实3"));
            assertEquals(3, memory.getAll().size());
        }
    }

    // ==================== 搜索 ====================

    @Nested
    @DisplayName("关键词搜索")
    class Search {

        @Test
        @DisplayName("按内容关键词搜索")
        void searchByContent() {
            memory.store(makeFact("f1", "项目使用 Maven 构建"));
            memory.store(makeFact("f2", "技术栈是 Spring Boot"));
            memory.store(makeFact("f3", "Maven 版本是 3.9"));

            List<MemoryEntry> results = memory.search("Maven", 5);
            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("按元数据搜索")
        void searchByMetadata() {
            MemoryEntry entry = new MemoryEntry("f1", "一些内容",
                    MemoryEntry.MemoryType.FACT,
                    Map.of("source", "fact_extractor", "tag", "java"),
                    10);
            memory.store(entry);

            List<MemoryEntry> results = memory.search("java", 5);
            assertEquals(1, results.size());
        }

        @Test
        @DisplayName("搜索无结果返回空列表")
        void searchEmpty() {
            memory.store(makeFact("f1", "Python 项目"));
            assertTrue(memory.search("Rust", 5).isEmpty());
        }

        @Test
        @DisplayName("搜索结果受 limit 限制")
        void searchLimit() {
            for (int i = 0; i < 10; i++) {
                memory.store(makeFact("f" + i, "关键词" + i));
            }
            List<MemoryEntry> results = memory.search("关键词", 3);
            assertEquals(3, results.size());
        }
    }

    // ==================== 持久化 ====================

    @Nested
    @DisplayName("JSON 持久化")
    class Persistence {

        @Test
        @DisplayName("存储后文件存在")
        void fileCreated() {
            memory.store(makeFact("f1", "持久化测试"));
            File jsonFile = tempDir.resolve("long_term_memory.json").toFile();
            assertTrue(jsonFile.exists());
            assertTrue(jsonFile.length() > 0);
        }

        @Test
        @DisplayName("重新加载后数据恢复")
        void reloadFromDisk() {
            memory.store(makeFact("f1", "事实A"));
            memory.store(makeFact("f2", "事实B"));

            // 创建新实例，模拟重启
            LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
            assertEquals(2, reloaded.size());
            assertTrue(reloaded.retrieve("f1").isPresent());
            assertTrue(reloaded.retrieve("f2").isPresent());
        }

        @Test
        @DisplayName("删除后持久化同步更新")
        void deletePersisted() {
            memory.store(makeFact("f1", "事实A"));
            memory.store(makeFact("f2", "事实B"));
            memory.delete("f1");

            LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
            assertEquals(1, reloaded.size());
            assertTrue(reloaded.retrieve("f1").isEmpty());
        }

        @Test
        @DisplayName("清空后文件内容为空列表")
        void clearPersisted() {
            memory.store(makeFact("f1", "事实A"));
            memory.clear();

            LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
            assertEquals(0, reloaded.size());
        }

        @Test
        @DisplayName("不存在的文件不报错")
        void noFileNoError() {
            // tempDir 下没有 JSON 文件
            LongTermMemory fresh = new LongTermMemory(tempDir.toFile());
            assertEquals(0, fresh.size());
        }
    }

    // ==================== 类型筛选 ====================

    @Nested
    @DisplayName("按类型筛选")
    class TypeFilter {

        @Test
        @DisplayName("getByType 只返回指定类型")
        void getByType() {
            memory.store(makeFact("f1", "事实1"));
            memory.store(new MemoryEntry("s1", "摘要1",
                    MemoryEntry.MemoryType.SUMMARY, Map.of(), 5));

            assertEquals(1, memory.getByType(MemoryEntry.MemoryType.FACT).size());
            assertEquals(1, memory.getByType(MemoryEntry.MemoryType.SUMMARY).size());
            assertEquals(0, memory.getByType(MemoryEntry.MemoryType.CONVERSATION).size());
        }
    }

    // ==================== 状态 ====================

    @Test
    @DisplayName("getStatusSummary 包含关键信息")
    void statusSummary() {
        memory.store(makeFact("f1", "事实"));
        memory.store(new MemoryEntry("s1", "摘要",
                MemoryEntry.MemoryType.SUMMARY, Map.of(), 5));

        String status = memory.getStatusSummary();
        assertTrue(status.contains("长期记忆"));
        assertTrue(status.contains("2条"));
    }
}
