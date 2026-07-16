package edu.cqie.paiclidemo.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 代码索引管理器 —— RAG 索引流水线的编排者。
 * <p>
 * <b>完整流水线</b>：
 * <pre>
 *  /index 命令触发
 *       │
 *       ▼
 *  ┌──────────────────────────────────────────────────┐
 *  │  ① 遍历文件树（Files.walkFileTree）               │
 *  │     - 跳过 node_modules / target / .git / .idea   │
 *  │     - 只收集 .java / .py / .md 等代码文本文件      │
 *  └─────────────────────┬────────────────────────────┘
 *                        ▼
 *  ┌──────────────────────────────────────────────────┐
 *  │  ② 逐文件处理                                     │
 *  │     ├─ CodeChunker.chunkFile()  → List&lt;CodeChunk&gt; │
 *  │     ├─ EmbeddingClient.embed()  → float[] 向量    │
 *  │     └─ 组装 CodeChunkEntry(chunk, embedding)      │
 *  └─────────────────────┬────────────────────────────┘
 *                        ▼
 *  ┌──────────────────────────────────────────────────┐
 *  │  ③ 持久化到 SQLite                                │
 *  │     ├─ VectorStore.clearProject()  → 清空旧数据   │
 *  │     ├─ VectorStore.insertChunks()  → 批量插入     │
 *  │     └─ VectorStore.insertRelations()（未来扩展）   │
 *  └──────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>容错设计</b>：单个文件解析失败只记录警告，不中断整个流程。
 * 每处理 10 个文件报告一次进度。
 * <p>
 * <b>全量重建</b>：每次索引都先 clearProject() 再 insert，
 * 保证数据一致性，避免增量更新的复杂性。
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public class CodeIndex {

    private static final Logger log = LoggerFactory.getLogger(CodeIndex.class);

    private final EmbeddingClient embeddingClient;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;
    private final ProgressListener progressListener;

    /**
     * 需要跳过的目录名（非代码 / 生成物 / IDE 配置）。
     * <p>
     * 以 "." 开头的目录在 collectFiles 中也会跳过（如 .git, .idea）。
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", "target", "build",
            ".git", ".idea", ".vscode",
            "dist", "out", "__pycache__"
    );

    /**
     * 需要索引的文件扩展名（代码 + 配置 + 文档）。
     */
    private static final Set<String> INDEX_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".go", ".rs",
            ".c", ".cpp", ".h", ".md",
            ".xml", ".properties", ".yaml", ".yml",
            ".json", ".sh", ".gradle", ".kt"
    );

    // ==================== 进度回调 ====================

    /**
     * 索引进度监听器。
     * <p>
     * CLI 中可以传入 {@code System.out::println} 实时显示进度，
     * 测试中传入 noop() 静默运行。
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);

        /** 空实现，不输出任何信息 */
        static ProgressListener noop() {
            return message -> {};
        }
    }

    // ==================== 构造方法 ====================

    public CodeIndex() {
        this(new EmbeddingClient(), ProgressListener.noop());
    }

    public CodeIndex(EmbeddingClient embeddingClient) {
        this(embeddingClient, ProgressListener.noop());
    }

    public CodeIndex(ProgressListener progressListener) {
        this(new EmbeddingClient(), progressListener);
    }

    public CodeIndex(EmbeddingClient embeddingClient, ProgressListener progressListener) {
        this.embeddingClient = embeddingClient;
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
        this.progressListener = progressListener == null
                ? ProgressListener.noop() : progressListener;
    }

    // ==================== 索引入口 ====================

    /**
     * 索引指定路径的代码库。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>验证路径存在</li>
     *   <li>遍历文件树，收集待索引文件</li>
     *   <li>逐文件：分块 → 向量化 → 收集 entry</li>
     *   <li>全量写入 SQLite（先清空再插入）</li>
     * </ol>
     *
     * @param projectPath 项目根目录路径
     * @return 索引结果统计
     */
    public IndexResult index(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            String message = "路径不存在: " + projectPath;
            emit("❌ " + message);
            return new IndexResult(0, 0, message);
        }

        emit("🔍 开始索引: " + root);

        // ① 收集文件
        List<Path> filesToIndex = new ArrayList<>();
        collectFiles(root, filesToIndex);
        emit("📁 发现 " + filesToIndex.size() + " 个文件待索引");

        if (filesToIndex.isEmpty()) {
            String message = "未找到可索引的代码文件";
            emit("⚠️ " + message);
            return new IndexResult(0, 0, message);
        }

        // ② 逐文件处理：分块 + 向量化
        List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> allRelations = new ArrayList<>();
        int processed = 0;
        int total = filesToIndex.size();

        for (Path file : filesToIndex) {
            processed++;
            if (processed % 10 == 0 || processed == total) {
                emit(String.format("   进度: %d/%d (%s)",
                        processed, total, file.getFileName()));
            }

            try {
                // 分块
                List<CodeChunk> chunks = chunker.chunkFile(file);

                // 为每个 chunk 生成 Embedding 向量
                for (CodeChunk chunk : chunks) {
                    float[] embedding = embeddingClient.embed(chunk.toEmbeddingText());
                    entries.add(new VectorStore.CodeChunkEntry(chunk, embedding));
                }

                // 关系分析：解析 Java 文件的 extends/implements/contains/calls 关系
                if (file.toString().endsWith(".java")) {
                    allRelations.addAll(analyzer.analyzeFile(file));
                }
            } catch (Exception e) {
                emit("   ⚠️ 索引失败: " + file.getFileName() + " - " + e.getMessage());
                log.warn("code index failed for file {}", file, e);
            }
        }

        // ③ 持久化到 SQLite（全量重建：先清空再插入）
        try (VectorStore store = createVectorStore(root.toString())) {
            store.clearProject();
            store.insertChunks(entries);
            store.insertRelations(allRelations);

            VectorStore.IndexStats stats = store.getStats();
            String msg = String.format("索引完成：%d 个代码块，%d 条关系",
                    stats.chunkCount(), stats.relationCount());
            emit("✅ " + msg);
            return new IndexResult(stats.chunkCount(), stats.relationCount(), msg);
        } catch (Exception e) {
            String error = "持久化失败: " + e.getMessage();
            emit("❌ " + error);
            log.warn("code index persistence failed for root {}", root, e);
            return new IndexResult(0, 0, error);
        }
    }

    // ==================== 文件遍历 ====================

    /**
     * 递归遍历目录，收集需要索引的代码文件。
     * <p>
     * 跳过策略：
     * <ul>
     *   <li>{@link #SKIP_DIRS} 中的目录名（node_modules / target / .git 等）</li>
     *   <li>以 "." 开头的隐藏目录</li>
     * </ul>
     * <p>
     * 收集策略：只收集 {@link #INDEX_EXTENSIONS} 中的扩展名。
     */
    void collectFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (SKIP_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (INDEX_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;  // 跳过不可访问的文件
                }
            });
        } catch (IOException e) {
            emit("❌ 遍历文件失败: " + e.getMessage());
            log.warn("code index file traversal failed for root {}", root, e);
        }
    }

    // ==================== 工具方法 ====================

    private void emit(String message) {
        progressListener.onProgress(message);
    }

    /**
     * 创建 VectorStore 实例。
     * <p>
     * 包级可见，测试时可通过系统属性 {@code paicli-demo.rag.dir}
     * 将数据库指向临时目录。
     */
    VectorStore createVectorStore(String projectPath) throws java.sql.SQLException {
        return new VectorStore(projectPath);
    }

    // ==================== 结果 Record ====================

    /**
     * 索引结果统计。
     *
     * @param chunkCount    成功索引的代码块数
     * @param relationCount 成功索引的关系数
     * @param message       人类可读的结果描述
     */
    public record IndexResult(int chunkCount, int relationCount, String message) {}
}
