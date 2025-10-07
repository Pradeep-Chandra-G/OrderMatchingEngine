package app.pradeep.OrderMatchingEngine.repository;

import app.pradeep.OrderMatchingEngine.model.Trader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface TraderRepository extends JpaRepository<Trader, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trader t WHERE t.id = :id")
    Optional<Trader> findByIdForUpdate(@Param("id") UUID id);
}
