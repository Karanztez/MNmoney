package money.mnmoney;

import money.mnmoney.api.MNmoneyAPI;
import money.mnmoney.config.ConfigManager;
import money.mnmoney.listener.PlayerListener;
import money.mnmoney.manager.MobDropManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.StringUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class MNmoney extends JavaPlugin implements TabCompleter {

    private Connection connection;
    private ConfigManager configManager;
    private MNmoneyAPI api;
    private MobDropManager mobDropManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        configManager.reloadMonstersConfig();
        
        mobDropManager = new MobDropManager(this);
        mobDropManager.startTask();
        
        api = new MNmoneyAPI(this);

        setupMySQL();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MNPlaceholder(this).register();
            getLogger().info("§a[MNMONEY] Hook PlaceholderAPI สำเร็จ!");
        } else {
            getLogger().warning("§e[MNMONEY] ไม่พบ PlaceholderAPI - placeholders จะไม่ทำงาน");
        }

        var cmds = new String[]{"mnc", "mnp", "mndeposit", "mnwithdraw", "mntop", "givemoney", "setmoney", "takemoney"};
        for (String cmdName : cmds) {
            var cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(this::onCommand);
                cmd.setTabCompleter(this);
            }
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getLogger().info("§a[MNMONEY] Magnus Network Economy พร้อมใช้งานแล้ว!");
    }

    @Override
    public void onDisable() {
        if (mobDropManager != null) {
            mobDropManager.stopTask();
        }
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
        }
        getLogger().info("§e[MNMONEY] ปิดระบบเรียบร้อย");
    }

    private void setupMySQL() {
        FileConfiguration cfg = getConfig();
        String url = "jdbc:mysql://" + cfg.getString("mysql.host", "localhost") + ":" +
                cfg.getInt("mysql.port", 3306) + "/" + cfg.getString("mysql.database", "magnus_db") +
                "?useSSL=false&autoReconnect=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Bangkok";

        try {
            connection = DriverManager.getConnection(url, cfg.getString("mysql.username"), cfg.getString("mysql.password"));
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS mnmoney_wallet (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "player_name VARCHAR(64), " +
                        "balance DOUBLE DEFAULT 0)");

                stmt.execute("CREATE TABLE IF NOT EXISTS mnmoney_bank (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "player_name VARCHAR(64), " +
                        "balance DOUBLE DEFAULT 0)");

                stmt.execute("CREATE TABLE IF NOT EXISTS mnmoney_transactions (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY," +
                        "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "from_uuid VARCHAR(36)," +
                        "from_name VARCHAR(64)," +
                        "to_uuid VARCHAR(36)," +
                        "to_name VARCHAR(64)," +
                        "type VARCHAR(50)," +
                        "amount DOUBLE," +
                        "balance_after DOUBLE," +
                        "note TEXT)");

                stmt.execute("ALTER TABLE mnmoney_wallet ADD COLUMN IF NOT EXISTS player_name VARCHAR(64)");
                stmt.execute("ALTER TABLE mnmoney_bank ADD COLUMN IF NOT EXISTS player_name VARCHAR(64)");
                stmt.execute("ALTER TABLE mnmoney_transactions ADD COLUMN IF NOT EXISTS from_name VARCHAR(64)");
                stmt.execute("ALTER TABLE mnmoney_transactions ADD COLUMN IF NOT EXISTS to_name VARCHAR(64)");
            }
            getLogger().info("§a[MNMONEY] เชื่อมต่อ MySQL และอัปเดตตารางสำเร็จ (รวมชื่อผู้เล่น)");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "§c[MNMONEY] เชื่อม MySQL ล้มเหลว", e);
            setEnabled(false);
        }
    }

    public Connection getConnection() {
        return connection;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MNmoneyAPI getApi() {
        return api;
    }
    
    public MobDropManager getMobDropManager() {
        return mobDropManager;
    }

    public <T> CompletableFuture<T> supplyAsyncQuery(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void runAsyncUpdate(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                task.run();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Error running async update", t);
            }
        });
    }

    public CompletableFuture<Double> getWallet(UUID uuid) {
        return supplyAsyncQuery(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM mnmoney_wallet WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getDouble("balance");
                }
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error getting wallet for " + uuid, e);
            }
            return 0.0;
        });
    }

    public CompletableFuture<Double> getBank(UUID uuid) {
        return supplyAsyncQuery(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM mnmoney_bank WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getDouble("balance");
                }
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error getting bank for " + uuid, e);
            }
            return 0.0;
        });
    }

    public void setWallet(UUID uuid, double amount) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        runAsyncUpdate(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO mnmoney_wallet (uuid, player_name, balance) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE player_name = ?, balance = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, amount);
                ps.setString(4, name);
                ps.setDouble(5, amount);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error setting wallet for " + uuid, e);
            }
        });
    }

    public void setBank(UUID uuid, double amount) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        runAsyncUpdate(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO mnmoney_bank (uuid, player_name, balance) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE player_name = ?, balance = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, amount);
                ps.setString(4, name);
                ps.setDouble(5, amount);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error setting bank for " + uuid, e);
            }
        });
    }

    public void logTransaction(UUID from, UUID to, String type, double amount, double after, String note) {
        runAsyncUpdate(() -> {
            String fromName = from != null ? Bukkit.getOfflinePlayer(from).getName() : "Console";
            String toName = to != null ? Bukkit.getOfflinePlayer(to).getName() : "Console";

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO mnmoney_transactions (from_uuid, from_name, to_uuid, to_name, type, amount, balance_after, note) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, from != null ? from.toString() : null);
                ps.setString(2, fromName);
                ps.setString(3, to != null ? to.toString() : null);
                ps.setString(4, toName);
                ps.setString(5, type);
                ps.setDouble(6, amount);
                ps.setDouble(7, after);
                ps.setString(8, note);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error logging transaction", e);
            }
        });
    }

    public CompletableFuture<Boolean> hasAccount(UUID uuid, String table) {
        return supplyAsyncQuery(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error checking account in " + table, e);
            }
            return false;
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> playerNames = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], playerNames, completions);
        }
        
        Collections.sort(completions);
        return completions;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        UUID uuid = player != null ? player.getUniqueId() : null;
        boolean isConsole = sender instanceof org.bukkit.command.ConsoleCommandSender;

        String cmdName = cmd.getName().toLowerCase();

        if (cmdName.equals("mnc") || cmdName.equals("mnp") || cmdName.equals("mndeposit") || cmdName.equals("mnwithdraw")) {
            if (player == null) {
                sender.sendMessage("§cคำสั่งนี้ใช้ได้เฉพาะในเกมเท่านั้น");
                return true;
            }
        }

        if (cmdName.equals("mnc")) {
            getWallet(uuid).thenCombine(getBank(uuid), (w, b) -> {
                player.sendMessage("§6§l✦ Magnus Network Economy ✦");
                player.sendMessage("§aWallet (กระเป๋า): §e" + String.format("%,.2f", w) + " บาท");
                player.sendMessage("§bBank (บัญชี): §e" + String.format("%,.2f", b) + " บาท");
                player.sendMessage("§7รวมทั้งหมด: §e" + String.format("%,.2f", w + b) + " บาท");
                return null;
            }).exceptionally(ex -> {
                player.sendMessage("§cเกิดข้อผิดพลาดในการดึงข้อมูล");
                getLogger().log(Level.WARNING, "Error in /mnc", ex);
                return null;
            });
            return true;
        }

        if (cmdName.equals("mnp")) {
            if (args.length != 2) {
                player.sendMessage("§cใช้: /mnp <ผู้เล่น> <จำนวน>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage("§cไม่พบผู้เล่นที่ออนไลน์");
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[1]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage("§cจำนวนเงินต้องเป็นตัวเลขมากกว่า 0");
                return true;
            }

            getWallet(uuid).thenAccept(wallet -> {
                if (wallet < amount) {
                    player.sendMessage("§cเงินใน wallet ไม่พอ!");
                    return;
                }
                setWallet(uuid, wallet - amount);
                getWallet(target.getUniqueId()).thenAccept(tWallet -> {
                    setWallet(target.getUniqueId(), tWallet + amount);
                    logTransaction(uuid, target.getUniqueId(), "pay", amount, wallet - amount, "โอนเงินให้ " + target.getName());
                    player.sendMessage("§aโอน §e" + String.format("%,.2f", amount) + " บาท §aให้ " + target.getName());
                    target.sendMessage("§aได้รับ §e" + String.format("%,.2f", amount) + " บาท §aจาก " + player.getName());
                });
            }).exceptionally(ex -> {
                player.sendMessage("§cเกิดข้อผิดพลาดระหว่างโอน");
                return null;
            });
            return true;
        }
        
        if (cmdName.equals("mndeposit")) {
            if (args.length != 1) {
                player.sendMessage("§cใช้: /mndeposit <จำนวน>");
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[0]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage("§cจำนวนเงินต้องเป็นตัวเลขมากกว่า 0");
                return true;
            }

            getWallet(uuid).thenAccept(wallet -> {
                if (wallet < amount) {
                    player.sendMessage("§cเงินใน wallet ไม่พอ!");
                    return;
                }
                setWallet(uuid, wallet - amount);
                getBank(uuid).thenAccept(bank -> {
                    setBank(uuid, bank + amount);
                    logTransaction(uuid, uuid, "deposit", amount, wallet - amount, "ฝากเงินเข้าธนาคาร");
                    player.sendMessage("§aฝากเงิน §e" + String.format("%,.2f", amount) + " บาท §aเข้าบัญชีธนาคารสำเร็จ");
                    player.sendMessage("§7Wallet ใหม่: §e" + String.format("%,.2f", wallet - amount));
                    player.sendMessage("§7Bank ใหม่: §e" + String.format("%,.2f", bank + amount));
                });
            }).exceptionally(ex -> {
                player.sendMessage("§cเกิดข้อผิดพลาดระหว่างฝากเงิน");
                return null;
            });
            return true;
        }

        if (cmdName.equals("mnwithdraw")) {
            if (args.length != 1) {
                player.sendMessage("§cใช้: /mnwithdraw <จำนวน>");
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[0]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage("§cจำนวนเงินต้องเป็นตัวเลขมากกว่า 0");
                return true;
            }

            getBank(uuid).thenAccept(bank -> {
                if (bank < amount) {
                    player.sendMessage("§cเงินในบัญชีธนาคารไม่พอ!");
                    return;
                }
                setBank(uuid, bank - amount);
                getWallet(uuid).thenAccept(wallet -> {
                    setWallet(uuid, wallet + amount);
                    logTransaction(uuid, uuid, "withdraw", amount, wallet + amount, "ถอนเงินจากธนาคาร");
                    player.sendMessage("§aถอนเงิน §e" + String.format("%,.2f", amount) + " บาท §aจากบัญชีธนาคารสำเร็จ");
                    player.sendMessage("§7Wallet ใหม่: §e" + String.format("%,.2f", wallet + amount));
                    player.sendMessage("§7Bank ใหม่: §e" + String.format("%,.2f", bank - amount));
                });
            }).exceptionally(ex -> {
                player.sendMessage("§cเกิดข้อผิดพลาดระหว่างถอนเงิน");
                return null;
            });
            return true;
        }

        if (cmdName.equals("mntop")) {
            supplyAsyncQuery(() -> {
                List<String> top = new ArrayList<>();
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT w.uuid, COALESCE(w.balance, 0) + COALESCE(b.balance, 0) AS total " +
                                     "FROM mnmoney_wallet w LEFT JOIN mnmoney_bank b ON w.uuid = b.uuid " +
                                     "ORDER BY total DESC LIMIT 10")) {
                    int rank = 1;
                    while (rs.next()) {
                        UUID u = UUID.fromString(rs.getString("uuid"));
                        double bal = rs.getDouble("total");
                        OfflinePlayer p = Bukkit.getOfflinePlayer(u);
                        String name = p.getName() != null ? p.getName() : "ไม่ทราบชื่อ";
                        top.add("§7#" + rank + " §f" + name + " §e" + String.format("%,.2f", bal) + " บาท");
                        rank++;
                    }
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error fetching top", e);
                }
                return top;
            }).thenAcceptAsync(topList -> {
                sender.sendMessage("§6§l✦ Magnus Top 10 ยอดเงินรวม ✦");
                if (topList.isEmpty()) {
                    sender.sendMessage("§cยังไม่มีข้อมูล");
                } else {
                    topList.forEach(sender::sendMessage);
                }
            }, runnable -> new BukkitRunnable() {
                @Override
                public void run() {
                    runnable.run();
                }
            }.runTask(this));
            return true;
        }

        boolean hasAdmin = sender.hasPermission("mnmoney.admin") || isConsole;
        if (!hasAdmin) {
            sender.sendMessage("§cคุณไม่มีสิทธิ์ใช้คำสั่งนี้");
            return true;
        }

        if (cmdName.equals("givemoney") || cmdName.equals("setmoney") || cmdName.equals("takemoney")) {
            if (args.length != 2) {
                sender.sendMessage("§cใช้: /" + label + " <ผู้เล่น> <จำนวน>");
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            UUID tUUID = target.getUniqueId();
            if (tUUID == null) {
                sender.sendMessage("§cไม่พบผู้เล่น " + args[0]);
                return true;
            }

            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cจำนวนเงินไม่ถูกต้อง");
                return true;
            }

            getWallet(tUUID).thenAccept(oldBal -> {
                double newBal = oldBal;
                String action = cmdName;

                if (action.equals("givemoney")) {
                    if (amount <= 0) {
                        sender.sendMessage("§cจำนวนต้องมากกว่า 0");
                        return;
                    }
                    newBal += amount;
                } else if (action.equals("setmoney")) {
                    if (amount < 0) {
                        sender.sendMessage("§cจำนวนต้องไม่ติดลบ");
                        return;
                    }
                    newBal = amount;
                } else if (action.equals("takemoney")) {
                    if (amount <= 0) {
                        sender.sendMessage("§cจำนวนต้องมากกว่า 0");
                        return;
                    }
                    if (oldBal < amount) {
                        sender.sendMessage("§cเงินไม่พอที่จะยึด (" + String.format("%,.2f", oldBal) + ")");
                        return;
                    }
                    newBal -= amount;
                }

                final double finalNewBal = newBal;
                setWallet(tUUID, finalNewBal);
                String by = isConsole ? "Console" : player.getName();
                logTransaction(null, tUUID, action, amount, finalNewBal, action + " โดย " + by);
                sender.sendMessage("§aทำรายการ " + action + " §e" + String.format("%,.2f", amount) + " บาท §aสำเร็จ");
                sender.sendMessage("§7ยอด wallet ใหม่: §e" + String.format("%,.2f", finalNewBal));

                Player online = target.getPlayer();
                if (online != null) {
                    online.sendMessage("§aยอดเงินของคุณถูกปรับโดย " + by + " เป็น §e" + String.format("%,.2f", finalNewBal) + " บาท");
                }
            }).exceptionally(ex -> {
                sender.sendMessage("§cเกิดข้อผิดพลาด");
                return null;
            });
            return true;
        }

        return false;
    }
}
