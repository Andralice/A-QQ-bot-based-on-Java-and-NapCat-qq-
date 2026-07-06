package com.start.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 环境变量占位符解析工具，统一处理 {@code ${ENV_VAR:default}} 格式。 */
public final class EnvResolver {

    private static final Logger logger = LoggerFactory.getLogger(EnvResolver.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?\\}");

    private EnvResolver() {}

    /** 解析 {@code ${ENV_NAME:defaultValue}} 占位符。若环境变量已设置则返回其值，否则返回默认值。 */
    public static String resolve(String value) {
        if (value == null) return null;
        Matcher m = ENV_PATTERN.matcher(value.trim());
        if (m.matches()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
            String defaultValue = m.group(2);
            if (defaultValue != null) {
                return defaultValue;
            }
            logger.warn("环境变量 {} 未设置", envName);
        }
        return value;
    }
}
