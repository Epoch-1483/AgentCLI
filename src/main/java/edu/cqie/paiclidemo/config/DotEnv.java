package edu.cqie.paiclidemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * .env 文件配置加载器 —— 从项目根目录的 .env 文件读取 KEY=VALUE 配置。
 * <p>
 * <b>设计来源</b>：参考原版 PaiCLI 的配置加载方式，将分散在多个类中的 .env 解析逻辑
 * 收拢到一个统一的工具类中，避免重复代码。
 * <p>
 * <b>解析规则</b>：
 * <ul>
 *   <li>每行一个 {@code KEY=VALUE}，{@code =} 号左边是 key，右边是 value</li>
 *   <li>以 {@code #} 开头的行为注释，跳过</li>
 *   <li>空行跳过</li>
 *   <li>value 两端的引号（{@code "} 或 {@code '}）会被自动去除</li>
 *   <li>key 两端的空格会被自动去除</li>
 * </ul>
 * <p>
 * <b>配置优先级（高 → 低）</b>：
 * <pre>
 *   JVM 系统属性（-DKEY=VALUE）
 *        ↓ 未设置则
 *   OS 环境变量
 *        ↓ 未设置则
 *   .env 文件（当前目录）
 *        ↓ 未设置则
 *   .env 文件（用户主目录）
 *        ↓ 未设置则
 *   代码默认值
 * </pre>
 * <p>
 * <b>用法</b>：
 * <pre>
 *   String apiKey = DotEnv.get("GLM_API_KEY", "");
 *   String provider = DotEnv.get("EMBEDDING_PROVIDER", "ollama");
 * </pre>
 *
 * @author Fonzo
 * @date 2026/07/16
 */
public final class DotEnv {

    private static final Logger log = LoggerFactory.getLogger(DotEnv.class);

    /** 缓存已解析的 .env 文件内容，避免重复 IO */
    private static volatile Map<String, String> cache;

    private DotEnv() {} // 工具类，不可实例化

    // ==================== 核心 API ====================

    /**
     * 读取配置值，按优先级依次查找。
     * <p>
     * 查找顺序：JVM 系统属性 → OS 环境变量 → .env 文件（当前目录）→ .env 文件（用户主目录）→ 默认值。
     *
     * @param key          配置键名（如 GLM_API_KEY、EMBEDDING_PROVIDER）
     * @param defaultValue 默认值（所有来源都未找到时返回）
     * @return 配置值，或 defaultValue
     */
    public static String get(String key, String defaultValue) {
        // ① JVM 系统属性（-D 参数）
        String value = System.getProperty(key);
        if (isPresent(value)) {
            return value;
        }

        // ② OS 环境变量
        value = System.getenv(key);
        if (isPresent(value)) {
            return value;
        }

        // ③ .env 文件
        value = loadAll().get(key);
        if (isPresent(value)) {
            return value;
        }

        // ④ 代码默认值
        return defaultValue;
    }

    /**
     * 强制重新加载 .env 文件（清除缓存）。
     * <p>
     * 一般只在测试中使用，生产环境 .env 文件不会运行时变化。
     */
    public static void reload() {
        cache = null;
    }

    // ==================== .env 文件解析 ====================

    /**
     * 加载所有 .env 文件中的配置项。
     * <p>
     * 按顺序搜索：
     * <ol>
     *   <li>当前工作目录的 {@code .env}（{@code new File(".env")}）</li>
     *   <li>用户主目录的 {@code .env}（{@code ~/.env}）</li>
     * </ol>
     * 后找到的会覆盖先找到的（当前目录优先级更高）。
     */
    private static Map<String, String> loadAll() {
        Map<String, String> local = cache;
        if (local != null) {
            return local;
        }

        synchronized (DotEnv.class) {
            if (cache != null) {
                return cache;
            }

            Map<String, String> merged = new LinkedHashMap<>();

            // 先加载用户主目录的 .env（低优先级）
            String home = System.getProperty("user.home");
            if (home != null) {
                Path homeEnv = Path.of(home, ".env");
                merged.putAll(parseFile(homeEnv));
            }

            // 再加载当前目录的 .env（高优先级，覆盖主目录的同名 key）
            Path cwdEnv = new File(".env").toPath().toAbsolutePath().normalize();
            merged.putAll(parseFile(cwdEnv));

            cache = merged;
            if (!merged.isEmpty()) {
                log.info("从 .env 文件加载了 {} 个配置项", merged.size());
            }
            return cache;
        }
    }

    /**
     * 解析单个 .env 文件为 key-value Map。
     * <p>
     * 文件不存在或读取失败时返回空 Map（静默处理，不抛异常）。
     *
     * @param file .env 文件路径
     * @return 解析后的配置 Map
     */
    static Map<String, String> parseFile(Path file) {
        Map<String, String> result = new LinkedHashMap<>();

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return result;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                // 跳过空行和注释
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // 查找第一个 = 号
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex <= 0) {
                    continue; // 没有 = 号或 = 在最前面，跳过
                }

                String key = trimmed.substring(0, eqIndex).trim();
                String value = trimmed.substring(eqIndex + 1).trim();

                // 去除两端引号
                value = stripQuotes(value);

                if (!key.isEmpty()) {
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            log.warn("读取 .env 文件失败: {}", file, e);
        }

        return result;
    }

    /**
     * 去除字符串两端的引号（单引号或双引号）。
     * <p>
     * 支持：{@code "value"} → {@code value}，{@code 'value'} → {@code value}。
     * 不匹配的情况（如 {@code "value'}）原样返回。
     */
    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 判断值是否存在且非空 */
    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }
}
