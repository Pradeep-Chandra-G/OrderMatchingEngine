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

    public MatchingEngine(OrderRepository orderRepo, TradeRepository tradeRepo,
                          TraderRepository traderRepo, RiskCheckService riskService) {
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
            System.out.println("Order REJECTED for " + order.getSymbol() + " - Risk check failed");
            return;
        }

        // Save order as OPEN
        order.setStatus("OPEN");
        orderRepo.save(order);
        System.out.println("Order OPEN for " + order.getSymbol() + ": " + order.getType() +
                " " + order.getQuantity() + " @ $" + order.getPrice());

        // Try to match with existing orders for this symbol
        matchingLock.lock();
        try {
            matchOrdersForSymbol(order.getSymbol());
        } finally {
            matchingLock.unlock();
        }
    }

    private void matchOrdersForSymbol(String symbol) {
        System.out.println("Matching orders for symbol: " + symbol);

        // Get all open buy orders for this symbol (highest price first)
        List<Order> buyOrders = orderRepo.findByStatusAndTypeAndSymbolOrderByPriceDescTimestampAsc("OPEN", "BUY", symbol);

        // Get all open sell orders for this symbol (lowest price first)
        List<Order> sellOrders = orderRepo.findByStatusAndTypeAndSymbolOrderByPriceAscTimestampAsc("OPEN", "SELL", symbol);

        System.out.println("Found " + buyOrders.size() + " buy orders and " + sellOrders.size() + " sell orders for " + symbol);

        for (Order buyOrder : buyOrders) {
            if (!"OPEN".equals(buyOrder.getStatus())) continue;

            for (Order sellOrder : sellOrders) {
                if (!"OPEN".equals(sellOrder.getStatus())) continue;

                // Check if orders can match (buy price >= sell price)
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
    protected void executeMatch(Order buyOrder, Order sellOrder) {
        int tradeQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
        double tradePrice = sellOrder.getPrice(); // Price improvement for buyer

        System.out.println("Executing trade: " + tradeQuantity + " shares of " + buyOrder.getSymbol() +
                " at $" + tradePrice);

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
        updateTraderBalanceAndPosition(buyOrder.getTrader(), sellOrder.getTrader(),
                buyOrder.getSymbol(), tradePrice, tradeQuantity);

        // Save updated traders
        traderRepo.save(buyOrder.getTrader());
        traderRepo.save(sellOrder.getTrader());

        // Save updated orders
        orderRepo.save(buyOrder);
        orderRepo.save(sellOrder);

        System.out.println("Trade executed successfully for " + buyOrder.getSymbol());
    }

    private void updateTraderBalanceAndPosition(Trader buyer, Trader seller, String symbol, double price, int quantity) {
        // Update buyer: decrease cash, increase stock position
        double totalCost = price * quantity;
        buyer.setBalance(buyer.getBalance() - totalCost);
        buyer.getPositions().merge(symbol, quantity, Integer::sum);

        // Update seller: increase cash, decrease stock position
        seller.setBalance(seller.getBalance() + totalCost);
        seller.getPositions().merge(symbol, -quantity, Integer::sum);

        System.out.println("Updated positions for " + symbol +
                " - Buyer: " + buyer.getPositions().get(symbol) +
                ", Seller: " + seller.getPositions().get(symbol));
    }

    // Method to match all pending orders (useful for system startup)
    public void matchAllPendingOrders() {
        List<String> symbols = orderRepo.findDistinctSymbolsByStatus("OPEN");
        System.out.println("Matching pending orders for symbols: " + symbols);

        matchingLock.lock();
        try {
            for (String symbol : symbols) {
                matchOrdersForSymbol(symbol);
            }
        } finally {
            matchingLock.unlock();
        }
    }
}