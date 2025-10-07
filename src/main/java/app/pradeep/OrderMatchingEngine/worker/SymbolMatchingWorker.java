package app.pradeep.OrderMatchingEngine.worker;

import app.pradeep.OrderMatchingEngine.model.*;
import app.pradeep.OrderMatchingEngine.repository.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Dedicated worker thread for matching orders for a single symbol.
 * Each symbol gets its own thread for parallel processing.
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

    /**
     * Submit an order to this symbol's worker thread
     */
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
                // Wait for incoming order (blocks if queue is empty)
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

    /**
     * Process a new order: add to book and attempt matching
     */
    private void processOrder(Order order) {
        System.out.println("Processing " + order.getType() + " order for " + symbol +
                ": " + order.getQuantity() + " @ $" + order.getPrice());

        // Add to appropriate queue
        orderBook.addOrder(order);

        // Attempt to match orders
        matchOrders();
    }

    /**
     * Match orders in the order book
     */
    private void matchOrders() {
        while (orderBook.hasBuyOrders() && orderBook.hasSellOrders()) {
            Order bestBuy = orderBook.peekBestBuy();
            Order bestSell = orderBook.peekBestSell();

            // Check if orders can match
            if (bestBuy.getPrice() >= bestSell.getPrice()) {
                // Remove from queues
                orderBook.pollBestBuy();
                orderBook.pollBestSell();

                // Execute the trade
                executeMatch(bestBuy, bestSell);

                // If partially filled, re-add to queue
                if (bestBuy.getQuantity() > 0 && "OPEN".equals(bestBuy.getStatus())) {
                    orderBook.addOrder(bestBuy);
                }
                if (bestSell.getQuantity() > 0 && "OPEN".equals(bestSell.getStatus())) {
                    orderBook.addOrder(bestSell);
                }
            } else {
                // No match possible, stop trying
                break;
            }
        }
    }

    /**
     * Execute a trade between two orders
     */
    private void executeMatch(Order buyOrder, Order sellOrder) {
        int tradeQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
        double tradePrice = sellOrder.getPrice(); // Price improvement for buyer

        System.out.println("Executing trade: " + tradeQuantity + " shares of " + symbol +
                " at $" + tradePrice);

        // Update order quantities in-memory
        buyOrder.setQuantity(buyOrder.getQuantity() - tradeQuantity);
        sellOrder.setQuantity(sellOrder.getQuantity() - tradeQuantity);

        // Update order status if fully filled
        if (buyOrder.getQuantity() == 0) {
            buyOrder.setStatus("FILLED");
        }
        if (sellOrder.getQuantity() == 0) {
            sellOrder.setStatus("FILLED");
        }

        // Persist to database in a transaction
        transactionTemplate.execute(status -> {
            try {
                // Re-fetch traders within transaction to get managed entities with positions loaded
                Trader buyer = traderRepo.findByIdForUpdate(buyOrder.getTrader().getId())
                        .orElseThrow(() -> new RuntimeException("Buyer not found"));
                Trader seller = traderRepo.findByIdForUpdate(sellOrder.getTrader().getId())
                        .orElseThrow(() -> new RuntimeException("Seller not found"));

                // Update trader positions and balances
                double totalCost = tradePrice * tradeQuantity;
                buyer.setBalance(buyer.getBalance() - totalCost);

                // Initialize positions map if null
                if (buyer.getPositions() == null) {
                    buyer.setPositions(new java.util.HashMap<>());
                }
                buyer.getPositions().merge(symbol, tradeQuantity, Integer::sum);

                seller.setBalance(seller.getBalance() + totalCost);

                // Initialize positions map if null
                if (seller.getPositions() == null) {
                    seller.setPositions(new java.util.HashMap<>());
                }
                seller.getPositions().merge(symbol, -tradeQuantity, Integer::sum);

                // Save traders first
                traderRepo.save(buyer);
                traderRepo.save(seller);

                // CRITICAL FIX: Save orders BEFORE creating trade (trade references orders)
                // This ensures orders exist in DB before Hibernate tries to resolve the references
                Order managedBuyOrder = orderRepo.save(buyOrder);
                Order managedSellOrder = orderRepo.save(sellOrder);

                // Create trade record with managed order entities
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
                e.printStackTrace();
                status.setRollbackOnly();
                throw e;
            }
        });

        System.out.println("Trade executed successfully for " + symbol);
    }

    /**
     * Shutdown this worker
     */
    public void shutdown() {
        running = false;
    }

    /**
     * Get statistics about this order book
     */
    public String getStats() {
        return String.format("Symbol: %s | Buy Orders: %d | Sell Orders: %d | Pending: %d",
                symbol, orderBook.getBuyOrderCount(), orderBook.getSellOrderCount(),
                incomingOrders.size());
    }

    public InMemoryOrderBook getOrderBook() {
        return orderBook;
    }
}