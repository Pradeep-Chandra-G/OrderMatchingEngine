package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.Trader;
import app.pradeep.OrderMatchingEngine.repository.TraderRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TraderService {

    private final TraderRepository traderRepo;

    public TraderService(TraderRepository traderRepo) {
        this.traderRepo = traderRepo;
    }

    public Trader createTrader(Trader trader) {
        return traderRepo.save(trader);
    }

    public List<Trader> getAllTraders() {
        return traderRepo.findAll();
    }

    public Trader getTraderById(UUID id) {
        return traderRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Trader not found: " + id));
    }

    public Trader updateTrader(UUID id, Trader updatedTrader) {
        Trader existing = getTraderById(id);
        existing.setName(updatedTrader.getName());
        existing.setBalance(updatedTrader.getBalance());
        existing.setPositions(updatedTrader.getPositions());
        return traderRepo.save(existing);
    }

    public void deleteTrader(UUID id) {
        traderRepo.deleteById(id);
    }
}

