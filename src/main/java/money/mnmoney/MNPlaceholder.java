package money.mnmoney;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * PlaceholderAPI Expansion สำหรับระบบเงิน MNmoney
 * - ใช้ placeholder: %mnmoney_mnw%, %mnmoney_mnb%, %mnmoney_mntotal%
 * - ไม่ override getDescription() เพื่อหลีกเลี่ยง deprecated warning
 *   (PAPI เวอร์ชันใหม่ไม่ต้องการแล้ว และจะถูกลบใน 2.13.0)
 */
public class MNPlaceholder extends PlaceholderExpansion {

    private final MNmoney plugin;

    public MNPlaceholder(MNmoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mnmoney";
    }

    @Override
    public @NotNull String getAuthor() {
        return "MAFIA";
    }

    /**
     * เวอร์ชันของ expansion นี้ (ดึงจาก plugin.yml โดยตรง)
     * - ถ้าไม่ override PAPI จะใช้ version จาก plugin description อัตโนมัติ
     * - แต่เพื่อความชัดเจน เรา override ไว้ (และ suppress warning ถ้ามี)
     */
    @SuppressWarnings("deprecation")
    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "Offline";
        }

        UUID uuid = player.getUniqueId();

        if (params.equalsIgnoreCase("mnw")) { // Wallet
            Double wallet = plugin.getWallet(uuid).join();
            return wallet != null ? String.format("%,.2f", wallet) : "0.00";
        }

        if (params.equalsIgnoreCase("mnb")) { // Bank
            Double bank = plugin.getBank(uuid).join();
            return bank != null ? String.format("%,.2f", bank) : "0.00";
        }

        if (params.equalsIgnoreCase("mntotal")) { // รวม
            Double wallet = plugin.getWallet(uuid).join();
            Double bank = plugin.getBank(uuid).join();
            double total = (wallet != null ? wallet : 0.0) + (bank != null ? bank : 0.0);
            return String.format("%,.2f", total);
        }

        return null;
    }
}