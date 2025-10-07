package app.pradeep.OrderMatchingEngine.controller;

import app.pradeep.OrderMatchingEngine.model.Order;
import app.pradeep.OrderMatchingEngine.model.Trader;
import app.pradeep.OrderMatchingEngine.repository.OrderRepository;
import app.pradeep.OrderMatchingEngine.repository.TraderRepository;
import app.pradeep.OrderMatchingEngine.service.MatchingEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final MatchingEngine engine;
    private final OrderRepository orderRepo;
    private final TraderRepository traderRepo;

    public OrderController(MatchingEngine engine, OrderRepository orderRepo, TraderRepository traderRepo) {
        this.engine = engine;
        this.orderRepo = orderRepo;
        this.traderRepo = traderRepo;
    }

    public static class OrderRequest {
        private UUID traderId;
        private String symbol;
        private String type;       // BUY / SELL
        private String orderType;  // LIMIT / MARKET
        private double price;
        private int quantity;

        public UUID getTraderId() { return traderId; }
        public void setTraderId(UUID traderId) { this.traderId = traderId; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getOrderType() { return orderType; }
        public void setOrderType(String orderType) { this.orderType = orderType; }
        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }

    public static class OrderResponse {
        private UUID id;
        private String status;
        private String symbol;
        private String message;

        public OrderResponse(UUID id, String status, String symbol, String message) {
            this.id = id;
            this.status = status;
            this.symbol = symbol;
            this.message = message;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    @PostMapping
    public ResponseEntity<?> placeOrder(@RequestBody OrderRequest request) {
        try {
            if (request.getSymbol() == null || request.getSymbol().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Symbol is required"));
            }

            Trader trader = traderRepo.findById(request.getTraderId())
                    .orElseThrow(() -> new RuntimeException("Trader not found"));

            Order order = new Order();
            order.setTrader(trader);
            order.setSymbol(request.getSymbol().toUpperCase());
            order.setType(request.getType());
            order.setOrderType(request.getOrderType());
            order.setPrice(request.getPrice());
            order.setQuantity(request.getQuantity());

            engine.submitOrder(order);

            // Return proper JSON response
            OrderResponse response = new OrderResponse(
                    order.getId(),
                    order.getStatus(),
                    order.getSymbol(),
                    "Order " + order.getStatus().toLowerCase()
            );

            // Return 400 for rejected orders, 200 for accepted
            if ("REJECTED".equals(order.getStatus())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all orders (from database - includes FILLED, CANCELLED, REJECTED)
     */
    @GetMapping
    public List<Order> getAllOrders() {
        return orderRepo.findAll();
    }

    /**
     * Get active orders (in-memory OPEN orders)
     */
    @GetMapping("/active")
    public List<Order> getActiveOrders() {
        return engine.getActiveOrders();
    }

    /**
     * Get active orders for a specific symbol
     */
    @GetMapping("/active/symbol/{symbol}")
    public List<Order> getActiveOrdersForSymbol(@PathVariable String symbol) {
        return engine.getActiveOrdersForSymbol(symbol.toUpperCase());
    }

    /**
     * Get order book snapshot for a symbol
     */
    @GetMapping("/orderbook/{symbol}")
    public Map<String, Object> getOrderBook(@PathVariable String symbol) {
        return engine.getOrderBookSnapshot(symbol.toUpperCase());
    }

    /**
     * Get orders by symbol (from database)
     */
    @GetMapping("/symbol/{symbol}")
    public List<Order> getOrdersBySymbol(@PathVariable String symbol) {
        return orderRepo.findBySymbol(symbol.toUpperCase());
    }

    /**
     * Get orders by symbol and status (from database)
     */
    @GetMapping("/symbol/{symbol}/status/{status}")
    public List<Order> getOrdersBySymbolAndStatus(@PathVariable String symbol, @PathVariable String status) {
        return orderRepo.findBySymbolAndStatus(symbol.toUpperCase(), status.toUpperCase());
    }

    /**
     * Cancel an order
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelOrder(@PathVariable UUID id) {
        try {
            engine.cancelOrder(id);
            return ResponseEntity.ok(Map.of(
                    "id", id.toString(),
                    "status", "CANCELLED",
                    "message", "Order cancelled successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get statistics for all symbols
     */
    @GetMapping("/stats")
    public Map<String, String> getStats() {
        return engine.getSymbolStats();
    }

    /**
     * Get engine metrics
     */
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return Map.of(
                "workerCount", engine.getWorkerCount(),
                "activeOrderCount", engine.getActiveOrderCount(),
                "symbolStats", engine.getSymbolStats()
        );
    }
}