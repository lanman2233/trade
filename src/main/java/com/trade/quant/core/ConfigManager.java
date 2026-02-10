package com.trade.quant.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 配置管理器。
 * 负责加载 config.properties，并在缺失时从模板生成。
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
     * 加载配置文件。
     * 若配置不存在则尝试从模板创建。
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
     * 从模板创建配置文件。
     */
    private void createConfigFromTemplate() {
        Path templatePath = Paths.get(CONFIG_TEMPLATE_FILE);
        if (!Files.exists(templatePath)) {
            throw new RuntimeException(
                    "配置模板文件 " + CONFIG_TEMPLATE_FILE + " 不存在\n" +
                    "请先创建配置模板文件。"
            );
        }

        try {
            Files.copy(templatePath, Paths.get(CONFIG_FILE));
            System.out.println("已从模板创建配置文件: " + CONFIG_FILE);
            System.out.println("请编辑 " + CONFIG_FILE + " 填入 API 密钥");
        } catch (IOException e) {
            throw new RuntimeException("无法从模板创建配置文件", e);
        }
    }

    /**
     * 根据运行模式和交易所，校验最小必要配置。
     */
    private void validateConfiguration() {
        String mode = getProperty("app.mode", "backtest").trim().toLowerCase();

        if ("live".equals(mode)) {
            String exchange = getProperty("live.exchange", "binance");
            validateExchangeCredentials(exchange);
            return;
        }

        // backtest: 如果使用本地数据文件，可跳过 API 校验。
        String backtestDataFile = getProperty("backtest.data.file", "").trim();
        if (!backtestDataFile.isEmpty()) {
            return;
        }

        String exchange = getProperty("backtest.exchange", "binance");
        validateExchangeCredentials(exchange);
    }

    private void validateExchangeCredentials(String exchangeName) {
        if ("binance".equalsIgnoreCase(exchangeName)) {
            if (!hasProperty("binance.api.key") || !hasProperty("binance.api.secret")) {
                throw new RuntimeException(
                        "配置文件不完整: Binance 实盘/拉取数据需要以下配置:\n" +
                        "  binance.api.key=YOUR_API_KEY\n" +
                        "  binance.api.secret=YOUR_SECRET_KEY"
                );
            }
            return;
        }

        if ("okx".equalsIgnoreCase(exchangeName)) {
            if (!hasProperty("okx.api.key") || !hasProperty("okx.api.secret") || !hasProperty("okx.api.passphrase")) {
                throw new RuntimeException(
                        "配置文件不完整: OKX 实盘/拉取数据需要以下配置:\n" +
                        "  okx.api.key=YOUR_API_KEY\n" +
                        "  okx.api.secret=YOUR_SECRET_KEY\n" +
                        "  okx.api.passphrase=YOUR_API_PASSPHRASE"
                );
            }
            return;
        }

        throw new RuntimeException("不支持的交易所: " + exchangeName + "，仅支持 binance / okx");
    }

    public String getBinanceApiKey() {
        return getProperty("binance.api.key");
    }

    public String getBinanceApiSecret() {
        return getProperty("binance.api.secret");
    }

    public String getOkxApiKey() {
        return getProperty("okx.api.key");
    }

    public String getOkxApiSecret() {
        return getProperty("okx.api.secret");
    }

    public String getOkxApiPassphrase() {
        return getProperty("okx.api.passphrase");
    }

    /**
     * 获取配置项（必须存在）。
     */
    public String getProperty(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new RuntimeException("配置项缺失: " + key);
        }
        return value.trim();
    }

    /**
     * 获取配置项（带默认值）。
     */
    public String getProperty(String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * 检查配置项是否存在且不是模板占位值。
     */
    public boolean hasProperty(String key) {
        String value = properties.getProperty(key);
        return value != null && !value.trim().isEmpty() && !value.trim().startsWith("YOUR_");
    }

    public int getIntProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(getProperty(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public boolean getBooleanProperty(String key, boolean defaultValue) {
        try {
            return Boolean.parseBoolean(getProperty(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 获取 BigDecimal 配置。
     */
    public java.math.BigDecimal getBigDecimalProperty(String key, java.math.BigDecimal defaultValue) {
        try {
            String value = getProperty(key);
            return new java.math.BigDecimal(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 功能开关约定：xxx.enabled。
     */
    public boolean isFeatureEnabled(String featurePrefix) {
        String key = featurePrefix + ".enabled";
        return getBooleanProperty(key, false);
    }

    public String getProxyHost() {
        return getProperty("proxy.host", "");
    }

    public int getProxyPort() {
        return getIntProperty("proxy.port", 0);
    }

    public boolean isProxyEnabled() {
        String host = getProxyHost();
        int port = getProxyPort();
        return !host.isEmpty() && port > 0;
    }

    public void reload() {
        loadConfiguration();
    }
}
