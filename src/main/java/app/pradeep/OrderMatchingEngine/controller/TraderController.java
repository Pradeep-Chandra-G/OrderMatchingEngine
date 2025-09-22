package app.pradeep.OrderMatchingEngine.controller;

import app.pradeep.OrderMatchingEngine.model.Trader;
import app.pradeep.OrderMatchingEngine.service.TraderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/traders")
public class TraderController {

    private final TraderService traderService;

    public TraderController(TraderService traderService) {
        this.traderService = traderService;
    }

    // Create trader
    @PostMapping
    public Trader createTrader(@RequestBody Trader trader) {
        return traderService.createTrader(trader);
    }

    // Get all traders
    @GetMapping
    public List<Trader> getAllTraders() {
        return traderService.getAllTraders();
    }

    // Get trader by ID
    @GetMapping("/{id}")
    public Trader getTraderById(@PathVariable UUID id) {
        return traderService.getTraderById(id);
    }

    // Update trader (balance, positions, etc.)
    @PutMapping("/{id}")
    public Trader updateTrader(@PathVariable UUID id, @RequestBody Trader trader) {
        return traderService.updateTrader(id, trader);
    }

    // Delete trader
    @DeleteMapping("/{id}")
    public String deleteTrader(@PathVariable UUID id) {
        traderService.deleteTrader(id);
        return "Trader deleted: " + id;
    }
}

