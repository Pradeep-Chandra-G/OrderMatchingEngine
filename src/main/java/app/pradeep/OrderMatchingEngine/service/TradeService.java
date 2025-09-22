package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.Trade;
import app.pradeep.OrderMatchingEngine.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TradeService {

    private final TradeRepository tradeRepo;

    public TradeService(TradeRepository tradeRepo) {
        this.tradeRepo = tradeRepo;
    }

    public Trade getTradeById(UUID id) {
        return tradeRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Trade not found: " + id));
    }

    public List<Trade> getAllTrades() {
        return tradeRepo.findAll();
    }

    public Trade createTrade(Trade trade) {
        return tradeRepo.save(trade);
    }

    public void deleteTrade(UUID id) {
        tradeRepo.deleteById(id);
    }
}

