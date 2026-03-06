package money.mnmoney.api;

import money.mnmoney.MNmoney;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * API สำหรับปลั๊กอิน MNmoney
 * ให้ปลั๊กอินอื่นสามารถจัดการยอดเงินของผู้เล่นได้
 *
 * วิธีการใช้งานในปลั๊กอินอื่น:
 * 1. เพิ่ม softdepend: [MNmoney] ใน plugin.yml ของคุณ
 * 2. ดึง API มาใช้งาน:
 *    MNmoney mnMoneyPlugin = (MNmoney) Bukkit.getPluginManager().getPlugin("MNmoney");
 *    if (mnMoneyPlugin != null) {
 *        MNmoneyAPI api = mnMoneyPlugin.getApi();
 *        // เรียกใช้เมธอดต่างๆ เช่น api.getWalletBalance(player.getUniqueId()).thenAccept(...);
 *    }
 */
public class MNmoneyAPI {

    private final MNmoney plugin;

    public MNmoneyAPI(MNmoney plugin) {
        this.plugin = plugin;
    }

    /**
     * ดึงยอดเงินใน Wallet ของผู้เล่นแบบ Asynchronous
     * @param uuid UUID ของผู้เล่น
     * @return CompletableFuture ที่จะคืนค่าเป็นยอดเงิน (Double)
     */
    public CompletableFuture<Double> getWalletBalance(UUID uuid) {
        return plugin.getWallet(uuid);
    }

    /**
     * ดึงยอดเงินใน Bank ของผู้เล่นแบบ Asynchronous
     * @param uuid UUID ของผู้เล่น
     * @return CompletableFuture ที่จะคืนค่าเป็นยอดเงิน (Double)
     */
    public CompletableFuture<Double> getBankBalance(UUID uuid) {
        return plugin.getBank(uuid);
    }

    /**
     * ดึงยอดเงินรวม (Wallet + Bank) ของผู้เล่นแบบ Asynchronous
     * @param uuid UUID ของผู้เล่น
     * @return CompletableFuture ที่จะคืนค่าเป็นยอดเงินรวม (Double)
     */
    public CompletableFuture<Double> getTotalBalance(UUID uuid) {
        return getWalletBalance(uuid).thenCombine(getBankBalance(uuid), Double::sum);
    }

    /**
     * ตั้งค่ายอดเงินใน Wallet ของผู้เล่น
     * @param uuid UUID ของผู้เล่น
     * @param amount จำนวนเงินที่ต้องการตั้งค่า
     */
    public void setWalletBalance(UUID uuid, double amount) {
        if (amount < 0) return;
        plugin.setWallet(uuid, amount);
        plugin.logTransaction(null, uuid, "api_set_wallet", amount, amount, "Set by API");
    }

    /**
     * ตั้งค่ายอดเงินใน Bank ของผู้เล่น
     * @param uuid UUID ของผู้เล่น
     * @param amount จำนวนเงินที่ต้องการตั้งค่า
     */
    public void setBankBalance(UUID uuid, double amount) {
        if (amount < 0) return;
        plugin.setBank(uuid, amount);
        plugin.logTransaction(null, uuid, "api_set_bank", amount, amount, "Set by API");
    }

    /**
     * เพิ่มเงินเข้า Wallet ของผู้เล่น (ฝาก)
     * @param uuid UUID ของผู้เล่น
     * @param amount จำนวนเงินที่ต้องการเพิ่ม
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> depositWallet(UUID uuid, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(null);
        return getWalletBalance(uuid).thenAccept(balance -> {
            double newBalance = balance + amount;
            plugin.setWallet(uuid, newBalance);
            plugin.logTransaction(null, uuid, "api_deposit_wallet", amount, newBalance, "Deposit by API");
        });
    }

    /**
     * เพิ่มเงินเข้า Bank ของผู้เล่น
     * @param uuid UUID ของผู้เล่น
     * @param amount จำนวนเงินที่ต้องการเพิ่ม
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> depositBank(UUID uuid, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(null);
        return getBankBalance(uuid).thenAccept(balance -> {
            double newBalance = balance + amount;
            plugin.setBank(uuid, newBalance);
            plugin.logTransaction(null, uuid, "api_deposit_bank", amount, newBalance, "Deposit to Bank by API");
        });
    }

    /**
     * ลดเงินจาก Wallet ของผู้เล่น (ถอน)
     * @param uuid UUID ของผู้เล่น
     * @param amount จำนวนเงินที่ต้องการลด
     * @return CompletableFuture<Boolean> คืนค่า true หากสำเร็จ, false หากเงินไม่พอ
     */
    public CompletableFuture<Boolean> withdrawWallet(UUID uuid, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return getWalletBalance(uuid).thenCompose(balance -> {
            if (balance < amount) {
                return CompletableFuture.completedFuture(false);
            }
            double newBalance = balance - amount;
            plugin.setWallet(uuid, newBalance);
            plugin.logTransaction(null, uuid, "api_withdraw_wallet", amount, newBalance, "Withdraw by API");
            return CompletableFuture.completedFuture(true);
        });
    }

    /**
     * ลดเงินจาก Bank ของผู้เล่น
     * @param uuid UUID ของผู้เล่น
     * @param amount จำนวนเงินที่ต้องการลด
     * @return CompletableFuture<Boolean> คืนค่า true หากสำเร็จ, false หากเงินไม่พอ
     */
    public CompletableFuture<Boolean> withdrawBank(UUID uuid, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return getBankBalance(uuid).thenCompose(balance -> {
            if (balance < amount) {
                return CompletableFuture.completedFuture(false);
            }
            double newBalance = balance - amount;
            plugin.setBank(uuid, newBalance);
            plugin.logTransaction(null, uuid, "api_withdraw_bank", amount, newBalance, "Withdraw from Bank by API");
            return CompletableFuture.completedFuture(true);
        });
    }

    /**
     * ตรวจสอบว่าผู้เล่นมีบัญชีหรือไม่
     * @param uuid UUID ของผู้เล่น
     * @return CompletableFuture<Boolean>
     */
    public CompletableFuture<Boolean> hasAccount(UUID uuid) {
        return plugin.hasAccount(uuid, "mnmoney_wallet");
    }

    /**
     * ดึงชื่อผู้เล่นจาก UUID (ถ้ามีในระบบ)
     * @param uuid UUID ของผู้เล่น
     * @return ชื่อผู้เล่น หรือ null
     */
    public String getPlayerName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName();
    }
}
