package money.mnmoney.listener;

import money.mnmoney.MNmoney;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class PlayerListener implements Listener {

    private final MNmoney plugin;
    private final Set<EntityType> monsterTypes = EnumSet.allOf(EntityType.class).stream()
            .filter(e -> e.isAlive() && Monster.class.isAssignableFrom(e.getEntityClass()))
            .collect(Collectors.toSet());

    public PlayerListener(MNmoney plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();

        plugin.hasAccount(uuid, "mnmoney_wallet").thenAccept(has -> {
            if (!has) {
                plugin.setWallet(uuid, 0.0);
            } else {
                plugin.runAsyncUpdate(() -> {
                    try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                            "UPDATE mnmoney_wallet SET player_name = ? WHERE uuid = ?")) {
                        ps.setString(1, name);
                        ps.setString(2, uuid.toString());
                        ps.executeUpdate();
                    } catch (SQLException ignored) {}
                });
            }
        });

        plugin.hasAccount(uuid, "mnmoney_bank").thenAccept(has -> {
            if (!has) {
                plugin.setBank(uuid, 0.0);
            } else {
                plugin.runAsyncUpdate(() -> {
                    try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                            "UPDATE mnmoney_bank SET player_name = ? WHERE uuid = ?")) {
                        ps.setString(1, name);
                        ps.setString(2, uuid.toString());
                        ps.executeUpdate();
                    } catch (SQLException ignored) {}
                });
            }
        });
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        if (!monsterTypes.contains(event.getEntityType())) return;

        FileConfiguration monstersConfig = plugin.getConfigManager().getMonstersConfig();
        String mobName = event.getEntityType().name();
        String path = "mobs." + mobName;

        // ตรวจสอบว่าเปิดใช้งานสำหรับ mob ตัวนี้หรือไม่ ถ้าไม่ ให้จบการทำงาน
        if (!monstersConfig.getBoolean(path + ".enabled", monstersConfig.getBoolean("defaults.enabled", true))) {
            return;
        }

        // ดึงค่าเฉพาะของ mob หรือใช้ค่า defaults
        double dropChance = monstersConfig.getDouble(path + ".drop-chance", monstersConfig.getDouble("defaults.drop-chance", 50.0));
        double minAmount = monstersConfig.getDouble(path + ".min-amount", monstersConfig.getDouble("defaults.min-amount", 0.1));
        double maxAmount = monstersConfig.getDouble(path + ".max-amount", monstersConfig.getDouble("defaults.max-amount", 1.0));

        if (ThreadLocalRandom.current().nextDouble() <= (dropChance / 100.0)) {
            double amount = ThreadLocalRandom.current().nextDouble(minAmount, maxAmount);

            ItemStack coin = new ItemStack(Material.GOLD_INGOT);
            ItemMeta meta = coin.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "เหรียญทอง");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "มูลค่า: " + String.format("%.2f", amount) + " บาท",
                    ChatColor.DARK_GRAY + "UUID: " + UUID.randomUUID().toString()
            ));
            coin.setItemMeta(meta);

            event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), coin);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Item item = event.getItem();
        ItemStack itemStack = item.getItemStack();

        if (itemStack.getType() == Material.GOLD_INGOT && itemStack.hasItemMeta()) {
            ItemMeta meta = itemStack.getItemMeta();
            if (meta.hasDisplayName() && meta.getDisplayName().equals(ChatColor.GOLD + "เหรียญทอง") && meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore.size() > 0 && lore.get(0).startsWith(ChatColor.YELLOW + "มูลค่า: ")) {
                    event.setCancelled(true);
                    item.remove();

                    try {
                        String valueString = ChatColor.stripColor(lore.get(0)).split(" ")[1];
                        double value = Double.parseDouble(valueString);
                        Player player = event.getPlayer();

                        plugin.getWallet(player.getUniqueId()).thenAccept(wallet -> {
                            double newBalance = wallet + value;
                            plugin.setWallet(player.getUniqueId(), newBalance);
                            plugin.logTransaction(null, player.getUniqueId(), "mob_drop", value, newBalance, "เก็บเงินจากมอนสเตอร์");
                            player.sendMessage(ChatColor.GOLD + "+ " + String.format("%,.2f", value) + " บาท");
                        });

                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        plugin.getLogger().warning("ไม่สามารถอ่านค่าเงินจากเหรียญได้: " + lore.get(0));
                    }
                }
            }
        }
    }
}
