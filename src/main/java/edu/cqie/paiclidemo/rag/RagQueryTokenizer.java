package edu.cqie.paiclidemo.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 查询分词器 —— 从自然语言查询中提取"代码关键词"。
 * <p>
 * <b>目标不是做复杂 NLP</b>，而是把查询里的类名、方法名等技术术语提取出来，
 * 供 {@link CodeRetriever} 做关键词检索加权。
 * <p>
 * 分词策略（双通道并行）：
 * <pre>
 *  输入: "Agent 的 run 方法是怎么实现的"
 *       │
 *       ├─ 通道 1: jieba 中文分词
 *       │   → ["Agent", "的", "run", "方法", "是", "怎么", "实现", "的"]
 *       │   → 过滤停用词 + 长度 &lt; 2 → ["Agent", "run", "方法"]
 *       │
 *       └─ 通道 2: ASCII 正则 [A-Za-z][A-Za-z0-9_.$-]{1,}
 *           → ["Agent", "run"]
 *
 *  合并去重（LinkedHashSet 保持插入顺序）:
 *       → {"Agent", "run", "方法"}
 * </pre>
 * <p>
 * <b>停用词表</b>：过滤中文查询中常见但对代码检索无意义的词：
 * "怎么"、"如何"、"什么"、"哪些"、"一下"、"实现"、"的是"、"一个"、"可以"、"这里"、"那里"
 *
 * @author Fonzo
 * @date 2026/07/16
 */
final class RagQueryTokenizer {

    /**
     * jieba 中文分词器（懒加载单例）。
     * <p>
     * jieba-analysis 在首次加载词典时会直接向 stdout 打印初始化信息，
     * 构造时临时重定向 System.out 到 ByteArrayOutputStream 以静默输出。
     */
    private static final JiebaSegmenter SEGMENTER = createSegmenterSilently();

    /**
     * ASCII 代码 token 正则：以字母开头，后跟至少 1 个字母/数字/特殊符号。
     * <p>
     * 支持类名（Agent）、方法名（addUserMessage）、带点路径（Agent.run）、
     * 带美元符号（Map$Entry）、带连字符（spring-boot）等代码标识符。
     */
    private static final Pattern ASCII_TOKEN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.$-]{1,}");

    private RagQueryTokenizer() {} // 工具类，不可实例化

    /**
     * 对查询文本进行分词，返回去重后的关键词集合。
     *
     * @param query 自然语言查询（如 "Agent 的 run 方法是怎么实现的"）
     * @return 代码关键词集合（有序、去重）
     */
    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        String normalized = query.trim();

        // 通道 1：jieba 中文分词
        List<String> words = SEGMENTER.sentenceProcess(normalized);
        for (String word : words) {
            String token = word.trim();
            if (isUsefulToken(token)) {
                tokens.add(token);
            }
        }

        // 通道 2：ASCII 正则匹配（确保英文代码标识符不被 jieba 拆碎）
        Matcher matcher = ASCII_TOKEN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isUsefulToken(token)) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    /**
     * 判断 token 是否有意义（非停用词 + 长度 ≥ 2 + 包含有效字符）。
     */
    private static boolean isUsefulToken(String token) {
        if (token == null) {
            return false;
        }
        String normalized = token.trim();
        if (normalized.length() < 2) {
            return false;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean stopword = switch (lower) {
            case "怎么", "如何", "什么", "哪些", "一下",
                 "实现", "的是", "一个", "可以", "这里", "那里" -> true;
            default -> false;
        };
        return !stopword && isMeaningful(normalized);
    }

    /**
     * 判断 token 是否包含有意义的字符（汉字或 ASCII 字母/数字）。
     * <p>
     * 过滤纯标点、纯空白等无意义 token。
     */
    private static boolean isMeaningful(String token) {
        boolean hasHan = token.codePoints().anyMatch(
                cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        boolean hasAsciiWord = token.codePoints().anyMatch(Character::isLetterOrDigit);
        return hasHan || hasAsciiWord;
    }

    /**
     * 静默创建 JiebaSegmenter，抑制首次加载时的 stdout 输出。
     */
    private static JiebaSegmenter createSegmenterSilently() {
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(
                    new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            return new JiebaSegmenter();
        } finally {
            System.setOut(originalOut);
        }
    }
}
