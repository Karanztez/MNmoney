package money.mnmoney.listener;

import money.mnmoney.MNmoney;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        // โหลดข้อมูลเข้า Cache ล่วงหน้า (Proactive Caching)
        plugin.getApi().preloadPlayerData(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // บันทึก Log ยอดเงินค้างจ่ายเมื่อผู้เล่นออกจากเกม
        plugin.getMobDropManager().savePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        if (!monsterTypes.contains(event.getEntityType())) return;

        FileConfiguration monstersConfig = plugin.getConfigManager().getMonstersConfig();
        String mobName = event.getEntityType().name();
        String path = "mobs." + mobName;

        if (!monstersConfig.getBoolean(path + ".enabled", monstersConfig.getBoolean("defaults.enabled", true))) {
            return;
        }

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

                        // 1. ให้เงินทันที! (ดึงยอดเก่า -> บวกยอดใหม่ -> บันทึก)
                        plugin.getWallet(player.getUniqueId()).thenAccept(wallet -> {
                            double newBalance = wallet + value;
                            plugin.setWallet(player.getUniqueId(), newBalance);
                            
                            // 2. ส่งยอดไปรอทำ Log (Batch Processing)
                            plugin.getMobDropManager().addTransaction(player.getUniqueId(), value);
                            
                            // 3. แจ้งเตือนผู้เล่นทันที
                            plugin.notifyPlayer(player.getUniqueId(), value, "เก็บเหรียญ");
                        });

                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        plugin.getLogger().warning("ไม่สามารถอ่านค่าเงินจากเหรียญได้: " + lore.get(0));
                    }
                }
            }
        }
    }
}
