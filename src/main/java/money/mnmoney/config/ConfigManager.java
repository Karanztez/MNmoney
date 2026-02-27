package money.mnmoney.config;

import money.mnmoney.MNmoney;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ConfigManager {

    private final MNmoney plugin;
    private FileConfiguration monstersConfig;

    public ConfigManager(MNmoney plugin) {
        this.plugin = plugin;
        saveDefaultMonstersConfig();
    }

    public void reloadMonstersConfig() {
        File monstersFile = new File(plugin.getDataFolder(), "monsters.yml");
        monstersConfig = YamlConfiguration.loadConfiguration(monstersFile);

        InputStream defConfigStream = plugin.getResource("monsters.yml");
        if (defConfigStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream));
            monstersConfig.setDefaults(defConfig);
        }
    }

    public FileConfiguration getMonstersConfig() {
        if (monstersConfig == null) {
            reloadMonstersConfig();
        }
        return monstersConfig;
    }

    public void saveDefaultMonstersConfig() {
        File monstersFile = new File(plugin.getDataFolder(), "monsters.yml");
        if (!monstersFile.exists()) {
            plugin.saveResource("monsters.yml", false);
        }
    }
}
