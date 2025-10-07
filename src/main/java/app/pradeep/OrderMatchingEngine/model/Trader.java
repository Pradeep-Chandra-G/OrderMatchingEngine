package app.pradeep.OrderMatchingEngine.model;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name="traders")
public class Trader {

    @Id
    private UUID id = UUID.randomUUID();

    private String name;
    private double balance;

    // CRITICAL: Add version field for optimistic locking
    @Version
    private Long version;

    // Use EAGER fetch to avoid lazy initialization issues
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trader_positions", joinColumns = @JoinColumn(name = "trader_id"))
    @MapKeyColumn(name = "symbol")
    @Column(name = "quantity")
    private Map<String, Integer> positions = new HashMap<>();

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Map<String, Integer> getPositions() {
        return positions;
    }

    public void setPositions(Map<String, Integer> positions) {
        this.positions = positions;
    }
}