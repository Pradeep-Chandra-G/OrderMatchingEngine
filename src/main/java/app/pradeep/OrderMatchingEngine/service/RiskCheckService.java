package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.Order;
import app.pradeep.OrderMatchingEngine.model.Trader;
import org.springframework.stereotype.Service;

@Service
public class RiskCheckService {

    public boolean validate(Order order) {
        Trader trader = order.getTrader();
        if ("BUY".equals(order.getType())) {
            return trader.getBalance() >= order.getPrice() * order.getQuantity();
        } else if ("SELL".equals(order.getType())) {
            int position = trader.getPositions().getOrDefault("STOCK", 0);
            return position >= order.getQuantity();
        }
        return false;
    }
}
