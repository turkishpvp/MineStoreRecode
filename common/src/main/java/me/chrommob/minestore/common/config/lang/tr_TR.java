package me.chrommob.minestore.common.config.lang;

import me.chrommob.minestore.libs.me.chrommob.config.ConfigManager.ConfigKey;
import me.chrommob.minestore.libs.me.chrommob.config.ConfigManager.ConfigWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * TurkishPvP arayüz dili (brand/arayuz-dili.md): sohbet mesajı
 * {@code &8 ┃ &6&lMAĞAZA &8┃ } ile başlar, gövde beyaz, hata tamamen kırmızı,
 * tıklanabilir metin sarı ve parantezli. Menü başlıkları düz metin.
 * Metinler MiniMessage biçiminde.
 */
public class tr_TR extends ConfigWrapper {
    static final String PREFIX = "<dark_gray> ┃ <gold><bold>MAĞAZA</bold> <dark_gray>┃ ";

    public tr_TR() {
        super("tr_TR", getKeys());
    }

    private static List<ConfigKey<?>> getKeys() {
        List<ConfigKey<?>> keys = new ArrayList<>();

        List<ConfigKey<?>> authKeys = new ArrayList<>();
        authKeys.add(new ConfigKey<>("initial-message", PREFIX + "<white>Mağaza hesabına giriş yapılıyor. <click:run_command:'/ms auth'><hover:show_text:'<gray>Girişi onaylar.'><yellow>(Onaylamak için tıkla)</yellow></hover></click>"));
        authKeys.add(new ConfigKey<>("success-message", PREFIX + "<white>Mağaza girişin <green>onaylandı</green>."));
        authKeys.add(new ConfigKey<>("failure-message", PREFIX + "<red>Onay bekleyen bir mağaza girişin yok."));
        authKeys.add(new ConfigKey<>("timeout-message", PREFIX + "<red>Mağaza girişinin süresi doldu."));
        keys.add(new ConfigKey<>("auth", authKeys));

        List<ConfigKey<?>> storeCommandKeys = new ArrayList<>();
        storeCommandKeys.add(new ConfigKey<>("message", PREFIX + "<white>Mağazamız seni bekliyor. <click:open_url:'%store_url%'><hover:show_text:'<gray>Mağazayı tarayıcıda açar.'><yellow>(Açmak için tıkla)</yellow></hover></click>"));
        keys.add(new ConfigKey<>("store-command", storeCommandKeys));

        List<ConfigKey<?>> buyGuiKeys = new ArrayList<>();
        buyGuiKeys.add(new ConfigKey<>("message", PREFIX + "<red>%package%</red> <white>satın almak için bağlantı hazır. <click:open_url:'%buy_url%'><yellow>(Açmak için tıkla)</yellow></click>"));

        List<ConfigKey<?>> buyGuiBackItemKeys = new ArrayList<>();
        buyGuiBackItemKeys.add(new ConfigKey<>("name", "<green>Geri Dön"));
        buyGuiBackItemKeys.add(new ConfigKey<>("description", "<gray>Önceki menüye"));
        buyGuiKeys.add(new ConfigKey<>("back", buyGuiBackItemKeys));

        List<ConfigKey<?>> buyGuiCategoryKeys = new ArrayList<>();
        buyGuiCategoryKeys.add(new ConfigKey<>("title", "Mağaza"));
        buyGuiCategoryKeys.add(new ConfigKey<>("name", "<gold>%category%"));
        buyGuiKeys.add(new ConfigKey<>("category", buyGuiCategoryKeys));

        List<ConfigKey<?>> buyGuiSubCategoryKeys = new ArrayList<>();
        buyGuiSubCategoryKeys.add(new ConfigKey<>("title", "Mağaza - %category%"));
        buyGuiSubCategoryKeys.add(new ConfigKey<>("name", "<gold>%subcategory%"));
        buyGuiKeys.add(new ConfigKey<>("subcategory", buyGuiSubCategoryKeys));

        List<ConfigKey<?>> buyGuiPackageKeys = new ArrayList<>();
        buyGuiPackageKeys.add(new ConfigKey<>("title", "Mağaza - %subcategory%"));
        buyGuiPackageKeys.add(new ConfigKey<>("name", "<gold>%package%"));
        buyGuiPackageKeys.add(new ConfigKey<>("description", "<gray>%description%"));
        List<ConfigKey<?>> buyGuiPackagePriceKeys = new ArrayList<>();
        buyGuiPackagePriceKeys.add(new ConfigKey<>("normal", "<gray>Fiyat: <gold>%price% TL"));
        buyGuiPackagePriceKeys.add(new ConfigKey<>("virtual", "<gray>Fiyat: <gold>%price% Cevher"));
        buyGuiPackageKeys.add(new ConfigKey<>("price", buyGuiPackagePriceKeys));
        buyGuiKeys.add(new ConfigKey<>("package", buyGuiPackageKeys));

        keys.add(new ConfigKey<>("buy-gui", buyGuiKeys));

        List<ConfigKey<?>> subscriptionKeys = new ArrayList<>();
        subscriptionKeys.add(new ConfigKey<>("title", PREFIX + "<white>Aboneliklerin:"));
        subscriptionKeys.add(new ConfigKey<>("status", ""));
        subscriptionKeys.add(new ConfigKey<>("url", " <click:open_url:'%url%'><hover:show_text:'<gray>Abonelik sayfasını açar.'><yellow>(Aboneliği yönetmek için tıkla)</yellow></hover></click>"));
        subscriptionKeys.add(new ConfigKey<>("note", " <white>%note%"));
        subscriptionKeys.add(new ConfigKey<>("none", PREFIX + "<red>Aktif bir aboneliğin yok."));
        subscriptionKeys.add(new ConfigKey<>("error", PREFIX + "<red>Mağazaya şu an ulaşılamıyor, biraz sonra tekrar dene."));
        keys.add(new ConfigKey<>("subscription", subscriptionKeys));

        List<ConfigKey<?>> paymentKeys = new ArrayList<>();
        paymentKeys.add(new ConfigKey<>("success-message", PREFIX + "<white>Satın alma tamamlandı, hesabından <gold>%price% Cevher</gold> düştü."));
        paymentKeys.add(new ConfigKey<>("failure-message", PREFIX + "<red>Cevherin yetmediği için satın alma tamamlanamadı."));
        keys.add(new ConfigKey<>("payment", paymentKeys));

        return keys;
    }
}
