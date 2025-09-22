package app.pradeep.OrderMatchingEngine.repository;

import app.pradeep.OrderMatchingEngine.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    // Find orders by status, type, and symbol - ordered for matching
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.type = :type AND o.symbol = :symbol ORDER BY o.price DESC, o.timestamp ASC")
    List<Order> findByStatusAndTypeAndSymbolOrderByPriceDescTimestampAsc(
            @Param("status") String status,
            @Param("type") String type,
            @Param("symbol") String symbol
    );

    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.type = :type AND o.symbol = :symbol ORDER BY o.price ASC, o.timestamp ASC")
    List<Order> findByStatusAndTypeAndSymbolOrderByPriceAscTimestampAsc(
            @Param("status") String status,
            @Param("type") String type,
            @Param("symbol") String symbol
    );

    // Find all distinct symbols that have orders with given status
    @Query("SELECT DISTINCT o.symbol FROM Order o WHERE o.status = :status")
    List<String> findDistinctSymbolsByStatus(@Param("status") String status);

    // Find orders by trader
    List<Order> findByTraderId(UUID traderId);

    // Find orders by status
    List<Order> findByStatus(String status);

    // Find orders by symbol
    List<Order> findBySymbol(String symbol);

    // Find orders by symbol and status
    List<Order> findBySymbolAndStatus(String symbol, String status);
}