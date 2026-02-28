package money.mnmoney.manager;

import money.mnmoney.MNmoney;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MobDropManager {

    private final MNmoney plugin;
    private final Map<UUID, Double> pendingTransactions = new HashMap<>();
    private BukkitRunnable task;

    public MobDropManager(MNmoney plugin) {
        this.plugin = plugin;
    }

    public void startTask() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                saveAll();
            }
        };
        task.runTaskTimer(plugin, 6000L, 6000L); // 5 นาที
    }

    public void stopTask() {
        if (task != null) {
            task.cancel();
        }
        saveAll();
    }

    // เพิ่มยอด Transaction สะสม (เงินเข้ากระเป๋าผู้เล่นไปแล้วตั้งแต่ตอนเก็บ)
    public void addTransaction(UUID uuid, double amount) {
        pendingTransactions.put(uuid, pendingTransactions.getOrDefault(uuid, 0.0) + amount);
    }

    public void savePlayer(UUID uuid) {
        if (pendingTransactions.containsKey(uuid)) {
            double amount = pendingTransactions.remove(uuid);
            if (amount > 0) {
                processLog(uuid, amount);
            }
        }
    }

    private void saveAll() {
        if (pendingTransactions.isEmpty()) return;

        Map<UUID, Double> toSave = new HashMap<>(pendingTransactions);
        pendingTransactions.clear();

        for (Map.Entry<UUID, Double> entry : toSave.entrySet()) {
            if (entry.getValue() > 0) {
                processLog(entry.getKey(), entry.getValue());
            }
        }
    }

    // บันทึก Log และอัปเดตยอดล่าสุดลง DB (เพื่อความชัวร์)
    private void processLog(UUID uuid, double amount) {
        // ดึงยอดเงินล่าสุดจาก Memory (หรือ DB) มาเพื่อบันทึกใน Log ว่า balance_after เป็นเท่าไหร่
        plugin.getWallet(uuid).thenAccept(currentBalance -> {
            // บันทึก Transaction รวบยอด
            plugin.logTransaction(null, uuid, "mob_drop_batch", amount, currentBalance, "รวบยอดเงินจากมอนสเตอร์ (Auto-save)");
            
            // อัปเดตยอดเงินในตาราง Wallet ให้ตรงกับปัจจุบัน (เผื่อกรณี Server Crash แล้ว Memory หาย)
            plugin.setWallet(uuid, currentBalance);
        });
    }
}
