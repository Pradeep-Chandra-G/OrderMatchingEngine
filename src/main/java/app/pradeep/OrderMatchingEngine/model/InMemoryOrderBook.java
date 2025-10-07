package app.pradeep.OrderMatchingEngine.model;

import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * In-memory order book for a single symbol.
 * Thread-safe with buy/sell queues optimized for matching.
 */
public class InMemoryOrderBook {

    private final String symbol;

    // Buy orders: highest price first, then FIFO (oldest first)
    private final PriorityBlockingQueue<Order> buyQueue;

    // Sell orders: lowest price first, then FIFO (oldest first)
    private final PriorityBlockingQueue<Order> sellQueue;

    public InMemoryOrderBook(String symbol) {
        this.symbol = symbol;

        // Buy orders comparator: highest price first, then oldest timestamp
        Comparator<Order> buyComparator = (o1, o2) -> {
            int priceCompare = Double.compare(o2.getPrice(), o1.getPrice()); // Descending
            if (priceCompare != 0) return priceCompare;
            return Long.compare(o1.getTimestamp(), o2.getTimestamp()); // Ascending (FIFO)
        };

        // Sell orders comparator: lowest price first, then oldest timestamp
        Comparator<Order> sellComparator = (o1, o2) -> {
            int priceCompare = Double.compare(o1.getPrice(), o2.getPrice()); // Ascending
            if (priceCompare != 0) return priceCompare;
            return Long.compare(o1.getTimestamp(), o2.getTimestamp()); // Ascending (FIFO)
        };

        this.buyQueue = new PriorityBlockingQueue<>(100, buyComparator);
        this.sellQueue = new PriorityBlockingQueue<>(100, sellComparator);
    }

    public void addOrder(Order order) {
        if ("BUY".equals(order.getType())) {
            buyQueue.offer(order);
        } else if ("SELL".equals(order.getType())) {
            sellQueue.offer(order);
        }
    }

    public Order peekBestBuy() {
        return buyQueue.peek();
    }

    public Order peekBestSell() {
        return sellQueue.peek();
    }

    public Order pollBestBuy() {
        return buyQueue.poll();
    }

    public Order pollBestSell() {
        return sellQueue.poll();
    }

    public boolean hasBuyOrders() {
        return !buyQueue.isEmpty();
    }

    public boolean hasSellOrders() {
        return !sellQueue.isEmpty();
    }

    public int getBuyOrderCount() {
        return buyQueue.size();
    }

    public int getSellOrderCount() {
        return sellQueue.size();
    }

    public String getSymbol() {
        return symbol;
    }

    // Remove a specific order (for cancellations)
    public boolean removeOrder(Order order) {
        if ("BUY".equals(order.getType())) {
            return buyQueue.remove(order);
        } else if ("SELL".equals(order.getType())) {
            return sellQueue.remove(order);
        }
        return false;
    }

    // Get all orders for viewing (creates snapshots)
    public List<Order> getAllBuyOrders() {
        return new ArrayList<>(buyQueue);
    }

    public List<Order> getAllSellOrders() {
        return new ArrayList<>(sellQueue);
    }

    public void clear() {
        buyQueue.clear();
        sellQueue.clear();
    }
}
