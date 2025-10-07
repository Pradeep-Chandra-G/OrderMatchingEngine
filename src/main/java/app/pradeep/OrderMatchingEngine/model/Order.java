package app.pradeep.OrderMatchingEngine.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_symbol_status", columnList = "symbol, status"),
        @Index(name = "idx_trader_id", columnList = "trader_id"),
        @Index(name = "idx_status", columnList = "status")
})
public class Order {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id = UUID.randomUUID();

    // Store only trader ID (not full object) to reduce memory footprint
    @Column(name = "trader_id", nullable = false, columnDefinition = "uuid")
    private UUID traderId;

    @Column(length = 10, nullable = false)
    private String symbol;

    @Column(length = 4, nullable = false)
    private String type; // BUY / SELL

    @Column(length = 6, nullable = false)
    private String orderType; // LIMIT / MARKET

    @Column(nullable = false)
    private double price;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long timestamp = Instant.now().toEpochMilli();

    @Column(length = 10, nullable = false)
    private String status = "OPEN"; // OPEN / FILLED / CANCELLED / REJECTED

    // Transient field for in-memory trader reference (not persisted)
    @Transient
    private Trader trader;

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTraderId() {
        return traderId;
    }

    public void setTraderId(UUID traderId) {
        this.traderId = traderId;
    }

    public Trader getTrader() {
        return trader;
    }

    public void setTrader(Trader trader) {
        this.trader = trader;
        if (trader != null) {
            this.traderId = trader.getId();
        }
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}