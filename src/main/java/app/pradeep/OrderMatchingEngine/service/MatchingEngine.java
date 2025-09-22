package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.*;
import app.pradeep.OrderMatchingEngine.repository.*;
import org.springframework.stereotype.Service;

import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MatchingEngine {

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private final RiskCheckService riskService;

    // Concurrent queues for buy/sell orders
    private PriorityQueue<Order> buyOrders = new PriorityQueue<>(
            (a, b) -> Double.compare(b.getPrice(), a.getPrice())); // Highest price first
    private PriorityQueue<Order> sellOrders = new PriorityQueue<>(
            (a, b) -> Double.compare(a.getPrice(), b.getPrice())); // Lowest price first

    private ExecutorService executor = Executors.newFixedThreadPool(4);

    public MatchingEngine(OrderRepository orderRepo, TradeRepository tradeRepo, RiskCheckService riskService) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.riskService = riskService;
    }

    public void submitOrder(Order order) {
        executor.submit(() -> {
            if (!riskService.validate(order)) {
                order.setStatus("REJECTED");
                orderRepo.save(order);
                return;
            }

            if ("BUY".equals(order.getType())) {
                buyOrders.add(order);
            } else {
                sellOrders.add(order);
            }

            matchOrders();
            orderRepo.save(order);
        });
    }

    private void matchOrders() {
        while (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {
            Order buy = buyOrders.peek();
            Order sell = sellOrders.peek();

            if (buy.getPrice() >= sell.getPrice()) {
                int qty = Math.min(buy.getQuantity(), sell.getQuantity());
                double tradePrice = sell.getPrice();

                Trade trade = new Trade();
                trade.setBuyOrder(buy);
                trade.setSellOrder(sell);
                trade.setQuantity(qty);
                trade.setPrice(tradePrice);
                tradeRepo.save(trade);

                buy.setQuantity(buy.getQuantity() - qty);
                sell.setQuantity(sell.getQuantity() - qty);

                if (buy.getQuantity() == 0) {
                    buyOrders.poll().setStatus("FILLED");
                }
                if (sell.getQuantity() == 0) {
                    sellOrders.poll().setStatus("FILLED");
                }
            } else {
                break; // No match possible
            }
        }
    }
}
