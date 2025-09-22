package app.pradeep.OrderMatchingEngine.controller;

import app.pradeep.OrderMatchingEngine.model.Order;
import app.pradeep.OrderMatchingEngine.repository.OrderRepository;
import app.pradeep.OrderMatchingEngine.service.MatchingEngine;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final MatchingEngine engine;
    private final OrderRepository repo;

    public OrderController(MatchingEngine engine, OrderRepository repo) {
        this.engine = engine;
        this.repo = repo;
    }

    @PostMapping
    public String placeOrder(@RequestBody Order order) {
        engine.submitOrder(order);
        return "Order submitted: " + order.getId();
    }

    @GetMapping
    public List<Order> getAllOrders() {
        return repo.findAll();
    }

    @DeleteMapping("/{id}")
    public String cancelOrder(@PathVariable UUID id) {
        repo.deleteById(id);
        return "Order cancelled: " + id;
    }
}
