package com.trade.quant.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 配置管理器 - 负责从配置文件读取敏感信息
 * 配置文件位置: 项目根目录/config.properties
 */
public class ConfigManager {

    private static final String CONFIG_FILE = "config.properties";
    private static final String CONFIG_TEMPLATE_FILE = "config.template.properties";
    private static ConfigManager instance;
    private Properties properties;

    private ConfigManager() {
        loadConfiguration();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /**
     * 加载配置文件
     * 如果配置文件不存在，会尝试从模板创建
     */
    private void loadConfiguration() {
        properties = new Properties();
        Path configPath = Paths.get(CONFIG_FILE);

        if (!Files.exists(configPath)) {
            System.err.println("警告: 配置文件 " + CONFIG_FILE + " 不存在");
            System.err.println("正在从模板创建配置文件: " + CONFIG_TEMPLATE_FILE);
            createConfigFromTemplate();
        }

        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(CONFIG_FILE),
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
            validateConfiguration();
        } catch (IOException e) {
            throw new RuntimeException("无法加载配置文件: " + CONFIG_FILE, e);
        }
    }

    /**
     * 从模板创建配置文件
     */
    private void createConfigFromTemplate() {
        Path templatePath = Paths.get(CONFIG_TEMPLATE_FILE);
        if (!Files.exists(templatePath)) {
            throw new RuntimeException(
                "配置模板文件 " + CONFIG_TEMPLATE_FILE + " 不存在！\n" +
                "请先创建配置模板文件。"
            );
        }

        try {
            Files.copy(templatePath, Paths.get(CONFIG_FILE));
            System.out.println("已从模板创建配置文件: " + CONFIG_FILE);
            System.out.println("请编辑 " + CONFIG_FILE + " 填入您的API密钥");
        } catch (IOException e) {
            throw new RuntimeException("无法从模板创建配置文件", e);
        }
    }

    /**
     * 验证配置完整性
     */
    private void validateConfiguration() {
        if (!hasProperty("binance.api.key") ||
            !hasProperty("binance.api.secret")) {
            throw new RuntimeException(
                "配置文件不完整！请确保 " + CONFIG_FILE + " 包含以下配置:\n" +
                "  binance.api.key=YOUR_API_KEY\n" +
                "  binance.api.secret=YOUR_SECRET_KEY"
            );
        }
    }

    /**
     * 获取 Binance API Key
     */
    public String getBinanceApiKey() {
        return getProperty("binance.api.key");
    }

    /**
     * 获取 Binance API Secret
     */
    public String getBinanceApiSecret() {
        return getProperty("binance.api.secret");
    }

    /**
     * 获取配置属性
     */
    public String getProperty(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new RuntimeException("配置项缺失: " + key);
        }
        return value.trim();
    }

    /**
     * 检查属性是否存在
     */
    public boolean hasProperty(String key) {
        String value = properties.getProperty(key);
        return value != null && !value.trim().isEmpty() && !value.trim().startsWith("YOUR_");
    }

    /**
     * 获取整数配置
     */
    public int getIntProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(getProperty(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 获取布尔配置
     */
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        try {
            return Boolean.parseBoolean(getProperty(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 重新加载配置
     */
    public void reload() {
        loadConfiguration();
    }
}
