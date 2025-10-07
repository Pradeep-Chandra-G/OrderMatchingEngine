package app.pradeep.OrderMatchingEngine.service;

import app.pradeep.OrderMatchingEngine.model.*;
import app.pradeep.OrderMatchingEngine.repository.*;
import app.pradeep.OrderMatchingEngine.worker.SymbolMatchingWorker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

@Service
public class MatchingEngine {

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private final TraderRepository traderRepo;
    private final RiskCheckService riskService;
    private final TransactionTemplate transactionTemplate;

    private final ConcurrentHashMap<String, SymbolMatchingWorker> symbolWorkers = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutor;
    private final ConcurrentHashMap<UUID, Order> activeOrders = new ConcurrentHashMap<>();

    public MatchingEngine(OrderRepository orderRepo,
                          TradeRepository tradeRepo,
                          TraderRepository traderRepo,
                          RiskCheckService riskService,
                          TransactionTemplate transactionTemplate) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.traderRepo = traderRepo;
        this.riskService = riskService;
        this.transactionTemplate = transactionTemplate;

        this.workerExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("SymbolWorker-" + t.getId());
            t.setDaemon(false);
            return t;
        });
    }

    @PostConstruct
    public void initialize() {
        System.out.println("Initializing Order Matching Engine...");

        List<Order> openOrders = orderRepo.findByStatus("OPEN");
        System.out.println("Loading " + openOrders.size() + " open orders from database...");

        Map<String, List<Order>> ordersBySymbol = new HashMap<>();
        for (Order order : openOrders) {
            ordersBySymbol.computeIfAbsent(order.getSymbol(), k -> new ArrayList<>()).add(order);
            activeOrders.put(order.getId(), order);
        }

        for (Map.Entry<String, List<Order>> entry : ordersBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<Order> orders = entry.getValue();

            SymbolMatchingWorker worker = getOrCreateWorker(symbol);
            for (Order order : orders) {
                worker.submitOrder(order);
            }

            System.out.println("Loaded " + orders.size() + " orders for " + symbol);
        }

        System.out.println("Order Matching Engine initialized with " + symbolWorkers.size() + " symbol workers");
    }

    @Transactional
    public void submitOrder(Order order) {
        // Eagerly fetch trader with positions
        Trader trader = traderRepo.findById(order.getTrader().getId())
                .orElseThrow(() -> new RuntimeException("Trader not found"));

        if (trader.getPositions() == null) {
            trader.setPositions(new HashMap<>());
        }

        order.setTrader(trader);

        // Risk validation
        if (!riskService.validate(order)) {
            order.setStatus("REJECTED");
            orderRepo.save(order); // Persist rejected orders
            System.out.println("Order REJECTED for " + order.getSymbol() + " - Risk check failed");
            return;
        }

        // Set order as OPEN (in-memory only)
        order.setStatus("OPEN");
        activeOrders.put(order.getId(), order);

        // FIX: Don't persist OPEN orders - keep them in-memory only
        // They will be persisted when FILLED, CANCELLED, or on shutdown

        System.out.println("Order OPEN for " + order.getSymbol() + ": " + order.getType() +
                " " + order.getQuantity() + " @ $" + order.getPrice());

        // Get or create worker for this symbol
        SymbolMatchingWorker worker = getOrCreateWorker(order.getSymbol());

        // Submit order to the symbol's dedicated thread
        worker.submitOrder(order);
    }

    private SymbolMatchingWorker getOrCreateWorker(String symbol) {
        return symbolWorkers.computeIfAbsent(symbol, s -> {
            System.out.println("Creating new worker thread for symbol: " + s);
            SymbolMatchingWorker worker = new SymbolMatchingWorker(
                    s, tradeRepo, orderRepo, traderRepo, transactionTemplate
            );
            workerExecutor.submit(worker);
            return worker;
        });
    }

    @Transactional
    public void cancelOrder(UUID orderId) {
        Order order = activeOrders.get(orderId);

        if (order == null) {
            throw new RuntimeException("Order not found: " + orderId);
        }

        if (!"OPEN".equals(order.getStatus())) {
            throw new RuntimeException("Cannot cancel order with status: " + order.getStatus());
        }

        order.setStatus("CANCELLED");
        activeOrders.remove(orderId);

        SymbolMatchingWorker worker = symbolWorkers.get(order.getSymbol());
        if (worker != null) {
            worker.getOrderBook().removeOrder(order);
        }

        // Persist cancelled order
        orderRepo.save(order);

        System.out.println("Order cancelled: " + orderId + " for " + order.getSymbol());
    }

    public List<Order> getActiveOrders() {
        return new ArrayList<>(activeOrders.values());
    }

    public List<Order> getActiveOrdersForSymbol(String symbol) {
        SymbolMatchingWorker worker = symbolWorkers.get(symbol);
        if (worker == null) {
            return Collections.emptyList();
        }

        List<Order> orders = new ArrayList<>();
        orders.addAll(worker.getOrderBook().getAllBuyOrders());
        orders.addAll(worker.getOrderBook().getAllSellOrders());
        return orders;
    }

    public Map<String, String> getSymbolStats() {
        Map<String, String> stats = new HashMap<>();
        symbolWorkers.forEach((symbol, worker) -> {
            stats.put(symbol, worker.getStats());
        });
        return stats;
    }

    public Map<String, Object> getOrderBookSnapshot(String symbol) {
        SymbolMatchingWorker worker = symbolWorkers.get(symbol);
        if (worker == null) {
            return Collections.emptyMap();
        }

        InMemoryOrderBook book = worker.getOrderBook();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("symbol", symbol);
        snapshot.put("buyOrders", book.getAllBuyOrders());
        snapshot.put("sellOrders", book.getAllSellOrders());
        snapshot.put("buyCount", book.getBuyOrderCount());
        snapshot.put("sellCount", book.getSellOrderCount());

        return snapshot;
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("Shutting down Order Matching Engine...");

        symbolWorkers.values().forEach(SymbolMatchingWorker::shutdown);

        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        persistRemainingOrders();

        System.out.println("Order Matching Engine shut down complete");
    }

    private void persistRemainingOrders() {
        System.out.println("Persisting " + activeOrders.size() + " remaining orders...");

        for (Order order : activeOrders.values()) {
            if ("OPEN".equals(order.getStatus())) {
                orderRepo.save(order);
            }
        }

        System.out.println("All orders persisted");
    }

    public int getWorkerCount() {
        return symbolWorkers.size();
    }

    public int getActiveOrderCount() {
        return activeOrders.size();
    }
}