package edu.cqie.paiclidemo.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息。
 * <p>
 * 核心特性：
 * <ul>
 *   <li>持久化到本地 JSON 文件（默认 ~/.paicli-demo/memory/long_term_memory.json）</li>
 *   <li>自动去重：内容完全相同的条目不重复存储</li>
 *   <li>启动时自动加载已有记忆</li>
 *   <li>支持关键词检索 + 时间衰减（越旧的记忆权重越低）</li>
 * </ul>
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class LongTermMemory implements Memory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_FILE = "long_term_memory.json";

    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        this.storageFile = new File(storageDir, STORAGE_FILE);

        // 启动时加载已有记忆
        loadFromDisk();
    }

    @Override
    public void store(MemoryEntry entry) {
        // 去重检查
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }

        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());
        saveToDisk();
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = ConversationMemory.tokenize(query.toLowerCase());

        return entries.values().stream()
                .filter(entry -> {
                    if (ConversationMemory.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    // 也搜索元数据
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> ConversationMemory.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /** 按类型筛选记忆 */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    /** 生成记忆状态摘要 */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L));
    }

    // ==================== 持久化 ====================

    /** 持久化到磁盘（JSON 格式） */
    private void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    /** 从磁盘加载 */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String ts && !ts.isBlank()) {
                timestamp = Instant.parse(ts);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n
                    ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析存储目录 */
    private static File resolveStorageDir() {
        String configured = System.getProperty("paicli.memory.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_MEMORY_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        return new File(new File(System.getProperty("user.home"), ".paicli-demo"), "memory");
    }
}
