package edu.cqie.paiclidemo.memory;

import java.util.List;
import java.util.Optional;

/**
 * Memory 接口 - 记忆系统的统一抽象。
 * <p>
 * 短期记忆（ConversationMemory）和长期记忆（LongTermMemory）都实现此接口，
 * 提供一致的存取 API。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public interface Memory {

    /** 存储一条记忆 */
    void store(MemoryEntry entry);

    /** 根据 ID 检索记忆 */
    Optional<MemoryEntry> retrieve(String id);

    /** 搜索相关记忆（关键词匹配） */
    List<MemoryEntry> search(String query, int limit);

    /** 获取所有记忆 */
    List<MemoryEntry> getAll();

    /** 删除指定记忆 */
    boolean delete(String id);

    /** 清空所有记忆 */
    void clear();

    /** 获取当前记忆的 token 总数 */
    int getTokenCount();

    /** 获取记忆条数 */
    int size();
}
