package app.pradeep.OrderMatchingEngine.repository;

import app.pradeep.OrderMatchingEngine.model.Trader;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TraderRepository extends JpaRepository<Trader, UUID> {
}