package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.*;
import app.pradeep.OrderMatchingEngine.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MatchingEngine {

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private final TraderRepository traderRepo;
    private final RiskCheckService riskService;

    // CHANGE 1: Per-symbol locks instead of single global lock
    private final ConcurrentHashMap<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager entityManager;

    public MatchingEngine(OrderRepository orderRepo, TradeRepository tradeRepo,
                          TraderRepository traderRepo, RiskCheckService riskService) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.traderRepo = traderRepo;
        this.riskService = riskService;
    }

    // CHANGE 2: Helper method to get lock for specific symbol
    private ReentrantLock getLockForSymbol(String symbol) {
        return symbolLocks.computeIfAbsent(symbol, k -> new ReentrantLock());
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

        // CHANGE 3: Only flush once, not twice
        entityManager.flush();

        System.out.println("Order OPEN for " + order.getSymbol() + ": " + order.getType() +
                " " + order.getQuantity() + " @ $" + order.getPrice());

        // CHANGE 4: Use per-symbol lock
        ReentrantLock lock = getLockForSymbol(order.getSymbol());
        lock.lock();
        try {
            matchOrdersForSymbol(order.getSymbol());
        } finally {
            lock.unlock();
        }
    }

    private void matchOrdersForSymbol(String symbol) {
        System.out.println("Matching orders for symbol: " + symbol);

        // Get all open buy orders for this symbol (highest price first)
        List<Order> buyOrders = orderRepo.findByStatusAndTypeAndSymbolOrderByPriceDescTimestampAsc("OPEN", "BUY", symbol);

        // Get all open sell orders for this symbol (lowest price first)
        List<Order> sellOrders = orderRepo.findByStatusAndTypeAndSymbolOrderByPriceAscTimestampAsc("OPEN", "SELL", symbol);

        System.out.println("Found " + buyOrders.size() + " buy orders and " + sellOrders.size() + " sell orders for " + symbol);

        // CHANGE 5: Track which orders to refresh after modifications
        boolean buyOrderModified = false;

        for (Order buyOrder : buyOrders) {
            // CHANGE 6: Refresh only if previously modified in this iteration
            if (buyOrderModified) {
                entityManager.refresh(buyOrder);
                buyOrderModified = false;
            }

            // CHANGE 7: Check in-memory state first (avoid DB hit)
            if (!"OPEN".equals(buyOrder.getStatus()) || buyOrder.getQuantity() == 0) {
                continue;
            }

            boolean sellOrderModified = false;

            for (Order sellOrder : sellOrders) {
                // CHANGE 8: Refresh only if previously modified
                if (sellOrderModified) {
                    entityManager.refresh(sellOrder);
                    sellOrderModified = false;
                }

                // CHANGE 9: Check in-memory state first
                if (!"OPEN".equals(sellOrder.getStatus()) || sellOrder.getQuantity() == 0) {
                    continue;
                }

                // Check if orders can match (buy price >= sell price)
                if (buyOrder.getPrice() >= sellOrder.getPrice()) {
                    executeMatch(buyOrder, sellOrder);

                    // Mark that orders were modified
                    buyOrderModified = true;
                    sellOrderModified = true;

                    // If buy order is fully filled, move to next buy order
                    if (buyOrder.getQuantity() == 0 || !"OPEN".equals(buyOrder.getStatus())) {
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

        // Update order quantities (in-memory first)
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

        // CHANGE 10: Batch save operations
        traderRepo.save(buyOrder.getTrader());
        traderRepo.save(sellOrder.getTrader());
        orderRepo.save(buyOrder);
        orderRepo.save(sellOrder);

        // CHANGE 11: Remove intermediate flush - let Spring batch at transaction end
        // entityManager.flush(); // REMOVED

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
    @Transactional
    public void matchAllPendingOrders() {
        List<String> symbols = orderRepo.findDistinctSymbolsByStatus("OPEN");
        System.out.println("Matching pending orders for symbols: " + symbols);

        // CHANGE 12: Process each symbol with its own lock (allows parallelization)
        for (String symbol : symbols) {
            ReentrantLock lock = getLockForSymbol(symbol);
            lock.lock();
            try {
                matchOrdersForSymbol(symbol);
            } finally {
                lock.unlock();
            }
        }
    }

    // CHANGE 13: Add method to clear locks for symbols with no open orders (optional cleanup)
    public void cleanupUnusedLocks() {
        List<String> activeSymbols = orderRepo.findDistinctSymbolsByStatus("OPEN");
        symbolLocks.keySet().retainAll(activeSymbols);
        System.out.println("Cleaned up locks. Active symbols: " + activeSymbols.size());
    }
}