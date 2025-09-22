package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.*;
import app.pradeep.OrderMatchingEngine.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MatchingEngine {

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private final TraderRepository traderRepo;
    private final RiskCheckService riskService;
    private final ReentrantLock matchingLock = new ReentrantLock();

    public MatchingEngine(OrderRepository orderRepo, TradeRepository tradeRepo, TraderRepository traderRepo, RiskCheckService riskService) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.traderRepo = traderRepo;
        this.riskService = riskService;
    }

    @Transactional
    public void submitOrder(Order order) {
        // Validate order first
        if (!riskService.validate(order)) {
            order.setStatus("REJECTED");
            orderRepo.save(order);
            return;
        }

        // Save order as OPEN
        order.setStatus("OPEN");
        orderRepo.save(order);

        // Try to match with existing orders
        matchingLock.lock();
        try {
            matchOrders();
        } finally {
            matchingLock.unlock();
        }
    }

    private void matchOrders() {
        // Get all open buy orders (highest price first)
        List<Order> buyOrders = orderRepo.findByStatusAndTypeOrderByPriceDescTimestampAsc("OPEN", "BUY");
        // Get all open sell orders (lowest price first)
        List<Order> sellOrders = orderRepo.findByStatusAndTypeOrderByPriceAscTimestampAsc("OPEN", "SELL");

        for (Order buyOrder : buyOrders) {
            if (!"OPEN".equals(buyOrder.getStatus())) continue;

            for (Order sellOrder : sellOrders) {
                if (!"OPEN".equals(sellOrder.getStatus())) continue;

                // Check if orders can match
                if (buyOrder.getPrice() >= sellOrder.getPrice()) {
                    executeMatch(buyOrder, sellOrder);

                    // If buy order is fully filled, move to next buy order
                    if (!"OPEN".equals(buyOrder.getStatus())) {
                        break;
                    }
                }
            }
        }
    }

    @Transactional
    private void executeMatch(Order buyOrder, Order sellOrder) {
        int tradeQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
        double tradePrice = sellOrder.getPrice(); // Price improvement for buyer

        // Create trade record
        Trade trade = new Trade();
        trade.setBuyOrder(buyOrder);
        trade.setSellOrder(sellOrder);
        trade.setQuantity(tradeQuantity);
        trade.setPrice(tradePrice);
        tradeRepo.save(trade);

        // Update order quantities
        buyOrder.setQuantity(buyOrder.getQuantity() - tradeQuantity);
        sellOrder.setQuantity(sellOrder.getQuantity() - tradeQuantity);

        // Update order status
        if (buyOrder.getQuantity() == 0) {
            buyOrder.setStatus("FILLED");
        }
        if (sellOrder.getQuantity() == 0) {
            sellOrder.setStatus("FILLED");
        }

        // Update trader positions and balances
        updateTraderBalanceAndPosition(buyOrder.getTrader(), sellOrder.getTrader(), tradePrice, tradeQuantity);

        // Save updated traders
        traderRepo.save(buyOrder.getTrader());
        traderRepo.save(sellOrder.getTrader());

        // Save updated orders
        orderRepo.save(buyOrder);
        orderRepo.save(sellOrder);

        System.out.println("Trade executed: " + tradeQuantity + " shares at $" + tradePrice);
    }

    private void updateTraderBalanceAndPosition(Trader buyer, Trader seller, double price, int quantity) {
        // Update buyer: decrease cash, increase stock position
        buyer.setBalance(buyer.getBalance() - (price * quantity));
        buyer.getPositions().merge("STOCK", quantity, Integer::sum);

        // Update seller: increase cash, decrease stock position
        seller.setBalance(seller.getBalance() + (price * quantity));
        seller.getPositions().merge("STOCK", -quantity, Integer::sum);

        System.out.println("Updated positions - Buyer: " + buyer.getPositions().get("STOCK") +
                ", Seller: " + seller.getPositions().get("STOCK"));
    }
}