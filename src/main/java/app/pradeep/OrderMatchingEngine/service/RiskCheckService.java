package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.Order;
import app.pradeep.OrderMatchingEngine.model.Trader;
import org.springframework.stereotype.Service;

@Service
public class RiskCheckService {

    public boolean validate(Order order) {
        Trader trader = order.getTrader();
        String symbol = order.getSymbol();

        if ("BUY".equals(order.getType())) {
            // Check if trader has enough balance for the purchase
            double requiredAmount = order.getPrice() * order.getQuantity();
            boolean hasBalance = trader.getBalance() >= requiredAmount;

            if (!hasBalance) {
                System.out.println("Risk check FAILED: Insufficient balance. Required: $" + requiredAmount +
                        ", Available: $" + trader.getBalance());
            }
            return hasBalance;

        } else if ("SELL".equals(order.getType())) {
            // Check if trader has enough stock to sell
            int currentPosition = trader.getPositions().getOrDefault(symbol, 0);
            boolean hasPosition = currentPosition >= order.getQuantity();

            if (!hasPosition) {
                System.out.println("Risk check FAILED: Insufficient " + symbol + " position. Required: " +
                        order.getQuantity() + ", Available: " + currentPosition);
            }
            return hasPosition;
        }

        System.out.println("Risk check FAILED: Invalid order type: " + order.getType());
        return false;
    }

    public boolean validateBalance(Trader trader, double amount) {
        return trader.getBalance() >= amount;
    }

    public boolean validatePosition(Trader trader, String symbol, int quantity) {
        int currentPosition = trader.getPositions().getOrDefault(symbol, 0);
        return currentPosition >= quantity;
    }

    // Additional risk checks can be added here
    public boolean validateOrderLimits(Order order) {
        // Example: Maximum order size limits
        if (order.getQuantity() > 10000) {
            System.out.println("Risk check FAILED: Order quantity too large: " + order.getQuantity());
            return false;
        }

        // Example: Minimum/Maximum price limits
        if (order.getPrice() <= 0 || order.getPrice() > 100000) {
            System.out.println("Risk check FAILED: Invalid price: $" + order.getPrice());
            return false;
        }

        return true;
    }
}