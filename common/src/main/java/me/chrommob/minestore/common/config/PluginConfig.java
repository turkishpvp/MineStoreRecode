package me.chrommob.minestore.common.config;

import me.chrommob.minestore.common.config.lang.cs_CZ;
import me.chrommob.minestore.common.config.lang.en_US;
import me.chrommob.minestore.common.config.lang.tr_TR;
import me.chrommob.minestore.libs.me.chrommob.config.ConfigManager.ConfigKey;
import me.chrommob.minestore.libs.me.chrommob.config.ConfigManager.ConfigManager;
import me.chrommob.minestore.libs.me.chrommob.config.ConfigManager.ConfigWrapper;

import java.io.File;
import java.util.*;

public class PluginConfig extends ConfigWrapper {
    private static final Map<String, ConfigWrapper> langMap = new HashMap<>();

    static {
        langMap.put("cs_CZ", new cs_CZ());
        langMap.put("en_US", new en_US());
        langMap.put("tr_TR", new tr_TR());
    }


    private final ConfigManager configManager;
    private final ConfigManager langConfigManager;
    public PluginConfig(ConfigManager configManager, File configFile) {
        super("config", getKeys());
        this.configManager = configManager;
        this.langConfigManager = new ConfigManager(new File(configFile.getParentFile(), "lang"));
        for (ConfigWrapper configWrapper : langMap.values()) {
            this.langConfigManager.addConfig(configWrapper);
        }
    }

    public static List<ConfigKey<?>> getKeys() {
        List<ConfigKey<?>> keys = new ArrayList<>();

        keys.add(ConfigKeys.DEBUG);
        keys.add(ConfigKeys.COMMAND_EXEC_LOGGING);
        keys.add(ConfigKeys.LANG);

        keys.add(ConfigKeys.STORE_URL);

        List<ConfigKey<?>> apiKeys = new ArrayList<>();
        apiKeys.add(ConfigKeys.API_KEYS.ENABLED);
        apiKeys.add(ConfigKeys.API_KEYS.KEY);
        keys.add(new ConfigKey<>("api", apiKeys));

        List<ConfigKey<?>> weblistenerKeys = new ArrayList<>();
        weblistenerKeys.add(ConfigKeys.WEBLISTENER_KEYS.ENABLED);
        weblistenerKeys.add(ConfigKeys.WEBLISTENER_KEYS.KEY);
        keys.add(new ConfigKey<>("weblistener", weblistenerKeys));

        List<ConfigKey<?>> payNowKeys = new ArrayList<>();
        payNowKeys.add(ConfigKeys.PAYNOW_KEYS.SHARE_IP_ON_JOIN);
        keys.add(new ConfigKey<>("paynow", payNowKeys, Collections.singletonList("You should only care about these if you are using PayNow.gg as a payment method.")));

        List<ConfigKey<?>> authKeys = new ArrayList<>();
        authKeys.add(ConfigKeys.AUTH_KEYS.TIMEOUT);
        keys.add(new ConfigKey<>("auth", authKeys));

        List<ConfigKey<?>> storeCommandKeys = new ArrayList<>();
        storeCommandKeys.add(ConfigKeys.STORE_COMMAND_KEYS.ENABLED);
        keys.add(new ConfigKey<>("store-command", storeCommandKeys));

        List<ConfigKey<?>> buyGuiKeys = new ArrayList<>();
        buyGuiKeys.add(ConfigKeys.BUY_GUI_KEYS.ENABLED);
        List<ConfigKey<?>> buyGuiBackgroundKeys = new ArrayList<>();
        buyGuiBackgroundKeys.add(ConfigKeys.BUY_GUI_KEYS.CATEGORY_KEYS.ENABLED);
        buyGuiBackgroundKeys.add(ConfigKeys.BUY_GUI_KEYS.CATEGORY_KEYS.ITEM);
        buyGuiKeys.add(new ConfigKey<>("background", buyGuiBackgroundKeys));
        List<ConfigKey<?>> buyGuiBackItemKeys = new ArrayList<>();
        buyGuiBackItemKeys.add(ConfigKeys.BUY_GUI_KEYS.BACK_KEYS.ENABLED);
        buyGuiBackItemKeys.add(ConfigKeys.BUY_GUI_KEYS.BACK_KEYS.ITEM);
        buyGuiKeys.add(new ConfigKey<>("back", buyGuiBackItemKeys));
        keys.add(new ConfigKey<>("buy-gui", buyGuiKeys));

        List<ConfigKey<?>> mysqlKeys = new ArrayList<>();
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.ENABLED);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.SYNC_BALANCE);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.SYNC_PREFIX);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.SYNC_SUFFIX);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.SYNC_PLAYER_GROUP);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.IP);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.PORT);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.DATABASE);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.USERNAME);
        mysqlKeys.add(ConfigKeys.MYSQL_KEYS.PASSWORD);
        keys.add(new ConfigKey<>("mysql", mysqlKeys));

        return keys;
    }

    public ConfigWrapper getLang() {
        String lang = ConfigKeys.LANG.getValue();
        if (langConfigManager.getConfigWrapper(lang) != null) {
            return langConfigManager.getConfigWrapper(lang);
        }
        if (langMap.containsKey(lang)) {
            langConfigManager.addConfig(langMap.get(lang));
            return langMap.get(lang);
        }
        ConfigWrapper configWrapper = new en_US(lang);
        langConfigManager.addConfig(configWrapper);
        return configWrapper;
    }

    /**
     * A language line by path, or an empty string when the key is missing.
     *
     * Language files that were written by an older build do not have the keys
     * added since (for example {@code payment}), and the plain accessor throws a
     * NullPointerException on them. A missing line should stay silent instead.
     */
    public String langString(String... path) {
        try {
            ConfigKey<?> key = getLang().getKey(path[0]);
            for (int i = 1; i < path.length && key != null; i++) {
                key = key.getKey(path[i]);
            }
            if (key == null) {
                return "";
            }
            String value = key.getValueAsString();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }

    public void saveConfig() {
        configManager.saveConfig(getName());
        langConfigManager.saveConfig(getLang().getName());
    }

    public void reload() {
        configManager.reloadConfig(getName());
        langConfigManager.reloadConfig(getLang().getName());
    }
}
