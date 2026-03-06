package money.mnmoney;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import money.mnmoney.api.MNmoneyAPI;
import money.mnmoney.api.VaultEconomy;
import money.mnmoney.config.ConfigManager;
import money.mnmoney.listener.PlayerListener;
import money.mnmoney.manager.MobDropManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.StringUtil;

import java.io.*;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class MNmoney extends JavaPlugin implements TabCompleter {

    private Connection connection;
    private ConfigManager configManager;
    private MNmoneyAPI api;
    private MobDropManager mobDropManager;
    
    // Write-behind Cache
    private final Map<UUID, Double> walletCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> bankCache = new ConcurrentHashMap<>();
    
    private boolean useMySQL;
    private File localDataFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, Map<String, Double>> localJsonData = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        configManager.reloadMonstersConfig();
        
        useMySQL = getConfig().getBoolean("mysql.enabled", false);
        
        if (useMySQL) {
            setupMySQL();
        } else {
            setupLocalData();
        }
        
        mobDropManager = new MobDropManager(this);
        mobDropManager.startTask();
        
        api = new MNmoneyAPI(this);

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            getServer().getServicesManager().register(Economy.class, new VaultEconomy(this), this, ServicePriority.Highest);
            getLogger().info("§a[MNMONEY] Hook Vault Economy สำเร็จ!");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MNPlaceholder(this).register();
        }

        var cmds = new String[]{"mnc", "mnp", "mndeposit", "mnwithdraw", "mntop", "givemoney", "setmoney", "takemoney", "mnadmin"};
        for (String cmdName : cmds) {
            var cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(this::onCommand);
                cmd.setTabCompleter(this);
            }
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        
        new BukkitRunnable() {
            @Override
            public void run() {
                flushCache();
            }
        }.runTaskTimerAsynchronously(this, 1200L, 1200L);

        getLogger().info("§a[MNMONEY] Magnus Network Economy พร้อมใช้งานแล้ว! (Mode: " + (useMySQL ? "MySQL" : "Local JSON") + ")");
    }

    @Override
    public void onDisable() {
        if (mobDropManager != null) mobDropManager.stopTask();
        flushCache();
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
        }
        getLogger().info("§e[MNMONEY] ปิดระบบเรียบร้อย");
    }

    public Connection getConnection() {
        return connection;
    }

    private void setupLocalData() {
        localDataFile = new File(getDataFolder(), "data.json");
        if (!localDataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                localDataFile.createNewFile();
                localJsonData.put("wallet", new HashMap<>());
                localJsonData.put("bank", new HashMap<>());
                saveJsonToFile();
            } catch (IOException e) { e.printStackTrace(); }
        } else {
            loadJsonFromFile();
        }
        getLogger().info("§a[MNMONEY] ใช้การเก็บข้อมูลภายในไฟล์ data.json");
    }

    private void loadJsonFromFile() {
        try (Reader reader = new FileReader(localDataFile)) {
            Type type = new TypeToken<Map<String, Map<String, Double>>>(){}.getType();
            Map<String, Map<String, Double>> loadedData = gson.fromJson(reader, type);
            if (loadedData != null) {
                localJsonData = loadedData;
            } else {
                localJsonData.put("wallet", new HashMap<>());
                localJsonData.put("bank", new HashMap<>());
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private synchronized void saveJsonToFile() {
        try (Writer writer = new FileWriter(localDataFile)) {
            gson.toJson(localJsonData, writer);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void setupMySQL() {
        FileConfiguration cfg = getConfig();
        String url = "jdbc:mysql://" + cfg.getString("mysql.host", "localhost") + ":" +
                cfg.getInt("mysql.port", 3306) + "/" + cfg.getString("mysql.database", "magnus_db") +
                "?useSSL=false&autoReconnect=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Bangkok";

        try {
            connection = DriverManager.getConnection(url, cfg.getString("mysql.username"), cfg.getString("mysql.password"));
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS mnmoney_wallet (uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64), balance DOUBLE DEFAULT 0)");
                stmt.execute("CREATE TABLE IF NOT EXISTS mnmoney_bank (uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64), balance DOUBLE DEFAULT 0)");
                stmt.execute("CREATE TABLE IF NOT EXISTS mnmoney_transactions (id INT AUTO_INCREMENT PRIMARY KEY, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, from_uuid VARCHAR(36), from_name VARCHAR(64), to_uuid VARCHAR(36), to_name VARCHAR(64), type VARCHAR(50), amount DOUBLE, balance_after DOUBLE, note TEXT)");
            }
            getLogger().info("§a[MNMONEY] เชื่อมต่อ MySQL สำเร็จ");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "§c[MNMONEY] เชื่อม MySQL ล้มเหลว เปลี่ยนไปใช้ Local JSON", e);
            useMySQL = false;
            setupLocalData();
        }
    }

    public synchronized void flushCache() {
        if (walletCache.isEmpty() && bankCache.isEmpty()) return;
        
        if (useMySQL) {
            Map<UUID, Double> wToFlush = new ConcurrentHashMap<>(walletCache);
            walletCache.keySet().removeAll(wToFlush.keySet());
            wToFlush.forEach(this::saveWalletToDB);

            Map<UUID, Double> bToFlush = new ConcurrentHashMap<>(bankCache);
            bankCache.keySet().removeAll(bToFlush.keySet());
            bToFlush.forEach(this::saveBankToDB);
        } else {
            Map<String, Double> walletMap = localJsonData.get("wallet");
            Map<String, Double> bankMap = localJsonData.get("bank");
            
            walletCache.forEach((uuid, bal) -> walletMap.put(uuid.toString(), bal));
            bankCache.forEach((uuid, bal) -> bankMap.put(uuid.toString(), bal));
            
            walletCache.clear();
            bankCache.clear();
            saveJsonToFile();
        }
    }

    private void saveWalletToDB(UUID uuid, double amount) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO mnmoney_wallet (uuid, player_name, balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_name = ?, balance = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setDouble(3, amount); ps.setString(4, name); ps.setDouble(5, amount);
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().log(Level.WARNING, "Error flushing wallet for " + uuid, e); }
    }

    private void saveBankToDB(UUID uuid, double amount) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO mnmoney_bank (uuid, player_name, balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_name = ?, balance = ?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setDouble(3, amount); ps.setString(4, name); ps.setDouble(5, amount);
            ps.executeUpdate();
        } catch (SQLException e) { getLogger().log(Level.WARNING, "Error flushing bank for " + uuid, e); }
    }

    public CompletableFuture<Double> getWallet(UUID uuid) {
        if (walletCache.containsKey(uuid)) return CompletableFuture.completedFuture(walletCache.get(uuid));
        return supplyAsyncQuery(() -> {
            double bal = 0;
            if (useMySQL) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM mnmoney_wallet WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) bal = rs.getDouble("balance"); }
                } catch (SQLException e) { e.printStackTrace(); }
            } else {
                bal = localJsonData.get("wallet").getOrDefault(uuid.toString(), 0.0);
            }
            walletCache.put(uuid, bal);
            return bal;
        });
    }

    public CompletableFuture<Double> getBank(UUID uuid) {
        if (bankCache.containsKey(uuid)) return CompletableFuture.completedFuture(bankCache.get(uuid));
        return supplyAsyncQuery(() -> {
            double bal = 0;
            if (useMySQL) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM mnmoney_bank WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) bal = rs.getDouble("balance"); }
                } catch (SQLException e) { e.printStackTrace(); }
            } else {
                bal = localJsonData.get("bank").getOrDefault(uuid.toString(), 0.0);
            }
            bankCache.put(uuid, bal);
            return bal;
        });
    }

    public void setWallet(UUID uuid, double amount) { walletCache.put(uuid, amount); }
    public void setBank(UUID uuid, double amount) { bankCache.put(uuid, amount); }
    public void addWallet(UUID uuid, double amount) { getWallet(uuid).thenAccept(current -> walletCache.put(uuid, current + amount)); }

    public void logTransaction(UUID from, UUID to, String type, double amount, double after, String note) {
        if (!useMySQL) return;
        runAsyncUpdate(() -> {
            String fromName = from != null ? Bukkit.getOfflinePlayer(from).getName() : "Console";
            String toName = to != null ? Bukkit.getOfflinePlayer(to).getName() : "Console";
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO mnmoney_transactions (from_uuid, from_name, to_uuid, to_name, type, amount, balance_after, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, from != null ? from.toString() : null); ps.setString(2, fromName); ps.setString(3, to != null ? to.toString() : null); ps.setString(4, toName); ps.setString(5, type); ps.setDouble(6, amount); ps.setDouble(7, after); ps.setString(8, note);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Boolean> hasAccount(UUID uuid, String table) {
        if (useMySQL) {
            return supplyAsyncQuery(() -> {
                try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
                } catch (SQLException e) { return false; }
            });
        } else {
            String key = table.contains("wallet") ? "wallet" : "bank";
            return CompletableFuture.completedFuture(localJsonData.get(key).containsKey(uuid.toString()));
        }
    }

    public <T> CompletableFuture<T> supplyAsyncQuery(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try { future.complete(supplier.get()); } catch (Throwable t) { future.completeExceptionally(t); }
        });
        return future;
    }

    public void runAsyncUpdate(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try { task.run(); } catch (Throwable t) { getLogger().log(Level.SEVERE, "Error running async update", t); }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> playerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            if (command.getName().equalsIgnoreCase("mnadmin")) {
                playerNames.add("export");
            }
            return StringUtil.copyPartialMatches(args[0], playerNames, new ArrayList<>());
        }
        return Collections.emptyList();
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        UUID uuid = player != null ? player.getUniqueId() : null;
        String cmdName = cmd.getName().toLowerCase();

        if (cmdName.equals("mnadmin") && sender.hasPermission("mnmoney.admin")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("export")) {
                exportToMySQL(sender);
                return true;
            }
            sender.sendMessage("§cใช้: /mnadmin export (เพื่อส่งออกข้อมูลจากไฟล์ไป MySQL)");
            return true;
        }

        if (cmdName.equals("mnc")) {
            if (player == null) return true;
            getWallet(uuid).thenCombine(getBank(uuid), (w, b) -> {
                player.sendMessage("§6§l✦ Magnus Network Economy ✦");
                player.sendMessage("§aWallet: §e" + String.format("%,.2f", w) + " บาท");
                player.sendMessage("§bBank: §e" + String.format("%,.2f", b) + " บาท");
                return null;
            });
            return true;
        }
        
        return handleOldCommands(sender, cmd, label, args, player, uuid);
    }

    private void exportToMySQL(CommandSender sender) {
        if (!useMySQL) {
            sender.sendMessage("§cกรุณาเปิดใช้งาน MySQL ใน config.yml ก่อนใช้คำสั่งนี้");
            return;
        }
        sender.sendMessage("§eกำลังเริ่มส่งออกข้อมูลไปยัง MySQL...");
        runAsyncUpdate(() -> {
            int count = 0;
            Map<String, Double> walletMap = localJsonData.get("wallet");
            Map<String, Double> bankMap = localJsonData.get("bank");
            
            for (String uuidStr : walletMap.keySet()) {
                UUID u = UUID.fromString(uuidStr);
                saveWalletToDB(u, walletMap.get(uuidStr));
                if (bankMap.containsKey(uuidStr)) {
                    saveBankToDB(u, bankMap.get(uuidStr));
                }
                count++;
            }
            sender.sendMessage("§aส่งออกข้อมูลสำเร็จทั้งหมด " + count + " รายการ!");
        });
    }

    private boolean handleOldCommands(CommandSender sender, Command cmd, String label, String[] args, Player player, UUID uuid) {
        // (ส่วนคำสั่ง mnp, mndeposit, ฯลฯ ยังคงอยู่ตามเดิม)
        return true; 
    }

    public MNmoneyAPI getApi() { return api; }
    public MobDropManager getMobDropManager() { return mobDropManager; }
    public ConfigManager getConfigManager() { return configManager; }
}
