package money.mnmoney.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Event นี้จะถูกเรียกเมื่อยอดเงินของผู้เล่นมีการเปลี่ยนแปลง (ใน Cache)
 * เหมาะสำหรับนำไปใช้ทำ Scoreboard หรืออัปเดต GUI แบบ Real-time
 */
public class PlayerBalanceChangeEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final UUID uuid;
    private final double oldBalance;
    private final double newBalance;
    private final String type; // "Wallet" or "Bank"

    public PlayerBalanceChangeEvent(UUID uuid, double oldBalance, double newBalance, String type) {
        this.uuid = uuid;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.type = type;
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public double getOldBalance() {
        return oldBalance;
    }

    public double getNewBalance() {
        return newBalance;
    }

    /**
     * @return ประเภทของกระเป๋าเงิน ("Wallet" หรือ "Bank")
     */
    public String getType() {
        return type;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
