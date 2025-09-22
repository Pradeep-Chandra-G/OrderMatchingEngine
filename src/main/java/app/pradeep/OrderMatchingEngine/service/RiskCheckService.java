package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.Order;
import app.pradeep.OrderMatchingEngine.model.Trader;
import org.springframework.stereotype.Service;

@Service
public class RiskCheckService {

    public boolean validate(Order order) {
        Trader trader = order.getTrader();

        if ("BUY".equals(order.getType())) {
            // Check if trader has enough balance for the purchase
            double requiredAmount = order.getPrice() * order.getQuantity();
            return trader.getBalance() >= requiredAmount;

        } else if ("SELL".equals(order.getType())) {
            // Check if trader has enough stock to sell
            int currentPosition = trader.getPositions().getOrDefault("STOCK", 0);
            return currentPosition >= order.getQuantity();
        }

        return false;
    }

    public boolean validateBalance(Trader trader, double amount) {
        return trader.getBalance() >= amount;
    }

    public boolean validatePosition(Trader trader, String symbol, int quantity) {
        int currentPosition = trader.getPositions().getOrDefault(symbol, 0);
        return currentPosition >= quantity;
    }
}