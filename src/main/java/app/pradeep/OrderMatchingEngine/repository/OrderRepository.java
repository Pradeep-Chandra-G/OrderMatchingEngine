package app.pradeep.OrderMatchingEngine.repository;

import app.pradeep.OrderMatchingEngine.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    // Find orders by status and type, ordered for matching
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.type = :type ORDER BY o.price DESC, o.timestamp ASC")
    List<Order> findByStatusAndTypeOrderByPriceDescTimestampAsc(@Param("status") String status, @Param("type") String type);

    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.type = :type ORDER BY o.price ASC, o.timestamp ASC")
    List<Order> findByStatusAndTypeOrderByPriceAscTimestampAsc(@Param("status") String status, @Param("type") String type);

    // Find orders by trader
    List<Order> findByTraderId(UUID traderId);

    // Find orders by status
    List<Order> findByStatus(String status);
}