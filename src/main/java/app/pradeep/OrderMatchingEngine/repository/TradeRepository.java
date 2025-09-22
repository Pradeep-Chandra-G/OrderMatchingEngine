package app.pradeep.OrderMatchingEngine.repository;

import app.pradeep.OrderMatchingEngine.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {
}