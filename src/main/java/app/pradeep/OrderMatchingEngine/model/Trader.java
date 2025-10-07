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

    // CHANGE: Use EAGER fetch to avoid lazy initialization issues in multi-threaded context
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trader_positions", joinColumns = @JoinColumn(name = "trader_id"))
    @MapKeyColumn(name = "symbol")
    @Column(name = "quantity")
    private Map<String, Integer> positions = new HashMap<>(); // symbol -> quantity

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

    public Map<String, Integer> getPositions() {
        return positions;
    }

    public void setPositions(Map<String, Integer> positions) {
        this.positions = positions;
    }
}