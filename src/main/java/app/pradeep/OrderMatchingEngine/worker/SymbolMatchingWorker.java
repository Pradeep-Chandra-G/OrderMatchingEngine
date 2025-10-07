package app.pradeep.OrderMatchingEngine.worker;

import app.pradeep.OrderMatchingEngine.model.*;
import app.pradeep.OrderMatchingEngine.repository.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.UUID;

/**
 * Dedicated worker thread for matching orders for a single symbol.
 * Enhanced with deadlock prevention via consistent lock ordering.
 */
public class SymbolMatchingWorker implements Runnable {

    private final String symbol;
    private final InMemoryOrderBook orderBook;
    private final BlockingQueue<Order> incomingOrders;
    private final TradeRepository tradeRepo;
    private final OrderRepository orderRepo;
    private final TraderRepository traderRepo;
    private final TransactionTemplate transactionTemplate;
    private volatile boolean running = true;

    public SymbolMatchingWorker(String symbol,
                                TradeRepository tradeRepo,
                                OrderRepository orderRepo,
                                TraderRepository traderRepo,
                                TransactionTemplate transactionTemplate) {
        this.symbol = symbol;
        this.orderBook = new InMemoryOrderBook(symbol);
        this.incomingOrders = new LinkedBlockingQueue<>();
        this.tradeRepo = tradeRepo;
        this.orderRepo = orderRepo;
        this.traderRepo = traderRepo;
        this.transactionTemplate = transactionTemplate;
    }

    public void submitOrder(Order order) {
        try {
            incomingOrders.put(order);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Failed to submit order for " + symbol + ": " + e.getMessage());
        }
    }

    @Override
    public void run() {
        System.out.println("Started matching worker for symbol: " + symbol);

        while (running || !incomingOrders.isEmpty()) {
            try {
                Order newOrder = incomingOrders.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);

                if (newOrder != null) {
                    processOrder(newOrder);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Worker interrupted for symbol: " + symbol);
                break;
            } catch (Exception e) {
                System.err.println("Error in matching worker for " + symbol + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("Stopped matching worker for symbol: " + symbol);
    }

    private void processOrder(Order order) {
        System.out.println("Processing " + order.getType() + " order for " + symbol +
                ": " + order.getQuantity() + " @ $" + order.getPrice());

        orderBook.addOrder(order);
        matchOrders();
    }

    private void matchOrders() {
        while (orderBook.hasBuyOrders() && orderBook.hasSellOrders()) {
            Order bestBuy = orderBook.peekBestBuy();
            Order bestSell = orderBook.peekBestSell();

            if (bestBuy.getPrice() >= bestSell.getPrice()) {
                orderBook.pollBestBuy();
                orderBook.pollBestSell();

                executeMatch(bestBuy, bestSell);

                if (bestBuy.getQuantity() > 0 && "OPEN".equals(bestBuy.getStatus())) {
                    orderBook.addOrder(bestBuy);
                }
                if (bestSell.getQuantity() > 0 && "OPEN".equals(bestSell.getStatus())) {
                    orderBook.addOrder(bestSell);
                }
            } else {
                break;
            }
        }
    }

    /**
     * CRITICAL DEADLOCK FIX: Always acquire locks in consistent order (by UUID)
     * This prevents circular wait conditions between transactions
     */
    private void executeMatch(Order buyOrder, Order sellOrder) {
        int tradeQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
        double tradePrice = sellOrder.getPrice();

        System.out.println("Executing trade: " + tradeQuantity + " shares of " + symbol +
                " at $" + tradePrice);

        buyOrder.setQuantity(buyOrder.getQuantity() - tradeQuantity);
        sellOrder.setQuantity(sellOrder.getQuantity() - tradeQuantity);

        if (buyOrder.getQuantity() == 0) {
            buyOrder.setStatus("FILLED");
        }
        if (sellOrder.getQuantity() == 0) {
            sellOrder.setStatus("FILLED");
        }

        // ============ DEADLOCK PREVENTION ============
        // Lock traders in consistent order (smaller UUID first)
        UUID buyerId = buyOrder.getTrader().getId();
        UUID sellerId = sellOrder.getTrader().getId();

        UUID firstId = buyerId.compareTo(sellerId) < 0 ? buyerId : sellerId;
        UUID secondId = buyerId.compareTo(sellerId) < 0 ? sellerId : buyerId;
        boolean buyerFirst = buyerId.equals(firstId);

        // Persist with retry logic for deadlock recovery
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                transactionTemplate.execute(status -> {
                    try {
                        // Lock traders in consistent order
                        Trader firstTrader = traderRepo.findByIdForUpdate(firstId)
                                .orElseThrow(() -> new RuntimeException("Trader not found: " + firstId));
                        Trader secondTrader = traderRepo.findByIdForUpdate(secondId)
                                .orElseThrow(() -> new RuntimeException("Trader not found: " + secondId));

                        // Assign back based on original roles
                        Trader buyer = buyerFirst ? firstTrader : secondTrader;
                        Trader seller = buyerFirst ? secondTrader : firstTrader;

                        // Update trader positions and balances
                        double totalCost = tradePrice * tradeQuantity;
                        buyer.setBalance(buyer.getBalance() - totalCost);

                        if (buyer.getPositions() == null) {
                            buyer.setPositions(new java.util.HashMap<>());
                        }
                        buyer.getPositions().merge(symbol, tradeQuantity, Integer::sum);

                        seller.setBalance(seller.getBalance() + totalCost);

                        if (seller.getPositions() == null) {
                            seller.setPositions(new java.util.HashMap<>());
                        }
                        seller.getPositions().merge(symbol, -tradeQuantity, Integer::sum);

                        // Save in same consistent order
                        traderRepo.save(firstTrader);
                        traderRepo.save(secondTrader);

                        // Save orders
                        Order managedBuyOrder = orderRepo.save(buyOrder);
                        Order managedSellOrder = orderRepo.save(sellOrder);

                        // Create trade record
                        Trade trade = new Trade();
                        trade.setBuyOrder(managedBuyOrder);
                        trade.setSellOrder(managedSellOrder);
                        trade.setQuantity(tradeQuantity);
                        trade.setPrice(tradePrice);
                        tradeRepo.save(trade);

                        System.out.println("Trade persisted for " + symbol);
                        return null;

                    } catch (Exception e) {
                        System.err.println("Error persisting trade: " + e.getMessage());
                        status.setRollbackOnly();
                        throw e;
                    }
                });

                // Success - break retry loop
                break;

            } catch (Exception e) {
                retryCount++;
                if (e.getMessage() != null && e.getMessage().contains("deadlock")) {
                    System.err.println("Deadlock detected, retry " + retryCount + "/" + maxRetries);
                    if (retryCount < maxRetries) {
                        try {
                            // Exponential backoff: 10ms, 20ms, 40ms
                            Thread.sleep(10 * (long) Math.pow(2, retryCount - 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Retry interrupted", ie);
                        }
                    } else {
                        System.err.println("Max retries exceeded for trade execution");
                        throw new RuntimeException("Failed to execute trade after retries", e);
                    }
                } else {
                    // Non-deadlock error, don't retry
                    throw e;
                }
            }
        }

        System.out.println("Trade executed successfully for " + symbol);
    }

    public void shutdown() {
        running = false;
    }

    public String getStats() {
        return String.format("Symbol: %s | Buy Orders: %d | Sell Orders: %d | Pending: %d",
                symbol, orderBook.getBuyOrderCount(), orderBook.getSellOrderCount(),
                incomingOrders.size());
    }

    public InMemoryOrderBook getOrderBook() {
        return orderBook;
    }
}