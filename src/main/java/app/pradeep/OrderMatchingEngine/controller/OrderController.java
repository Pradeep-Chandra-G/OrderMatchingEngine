package app.pradeep.OrderMatchingEngine.controller;

import app.pradeep.OrderMatchingEngine.model.Order;
import app.pradeep.OrderMatchingEngine.model.Trader;
import app.pradeep.OrderMatchingEngine.repository.OrderRepository;
import app.pradeep.OrderMatchingEngine.repository.TraderRepository;
import app.pradeep.OrderMatchingEngine.service.MatchingEngine;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    // DTO for cleaner API
    public static class OrderRequest {
        private UUID traderId;
        private String symbol;     // Stock symbol (AAPL, GOOGL, etc.)
        private String type;       // BUY / SELL
        private String orderType;  // LIMIT / MARKET
        private double price;
        private int quantity;

        // Getters & setters
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

    @PostMapping
    public String placeOrder(@RequestBody OrderRequest request) {
        // Validate required fields
        if (request.getSymbol() == null || request.getSymbol().trim().isEmpty()) {
            throw new RuntimeException("Symbol is required");
        }

        Trader trader = traderRepo.findById(request.getTraderId())
                .orElseThrow(() -> new RuntimeException("Trader not found"));

        Order order = new Order();
        order.setTrader(trader);
        order.setSymbol(request.getSymbol().toUpperCase()); // Normalize to uppercase
        order.setType(request.getType());
        order.setOrderType(request.getOrderType());
        order.setPrice(request.getPrice());
        order.setQuantity(request.getQuantity());

        engine.submitOrder(order);
        return "Order submitted: " + order.getId() + " for " + order.getSymbol();
    }

    @GetMapping
    public List<Order> getAllOrders() {
        return orderRepo.findAll();
    }

    @GetMapping("/symbol/{symbol}")
    public List<Order> getOrdersBySymbol(@PathVariable String symbol) {
        return orderRepo.findBySymbol(symbol.toUpperCase());
    }

    @GetMapping("/symbol/{symbol}/status/{status}")
    public List<Order> getOrdersBySymbolAndStatus(@PathVariable String symbol, @PathVariable String status) {
        return orderRepo.findBySymbolAndStatus(symbol.toUpperCase(), status.toUpperCase());
    }

    @DeleteMapping("/{id}")
    public String cancelOrder(@PathVariable UUID id) {
        orderRepo.deleteById(id);
        return "Order cancelled: " + id;
    }
}