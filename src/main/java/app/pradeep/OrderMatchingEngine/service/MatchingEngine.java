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

    // NEW: In-memory trader cache for risk checks (reduces DB reads)
    private final ConcurrentHashMap<UUID, TraderSnapshot> traderCache = new ConcurrentHashMap<>();

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

        // Use fixed thread pool with core count * 2 for better resource management
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        this.workerExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r);
            t.setName("SymbolWorker-" + t.getId());
            t.setDaemon(false);
            return t;
        });
    }

    @PostConstruct
    public void initialize() {
        System.out.println("Initializing Order Matching Engine...");

        // Load all traders into cache
        List<Trader> allTraders = traderRepo.findAll();
        for (Trader trader : allTraders) {
            traderCache.put(trader.getId(), new TraderSnapshot(trader));
        }
        System.out.println("Cached " + traderCache.size() + " traders");

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

    /**
     * OPTIMIZED: Fast-path validation using cached trader data
     */
    @Transactional
    public void submitOrder(Order order) {
        UUID traderId = order.getTrader().getId();

        // FAST PATH: Check cache first
        TraderSnapshot snapshot = traderCache.get(traderId);
        if (snapshot == null) {
            // Cache miss - load from DB and cache
            Trader trader = traderRepo.findById(traderId)
                    .orElseThrow(() -> new RuntimeException("Trader not found"));
            snapshot = new TraderSnapshot(trader);
            traderCache.put(traderId, snapshot);
        }

        // Create temporary trader object for validation (no DB hit)
        Trader tempTrader = snapshot.toTrader();
        order.setTrader(tempTrader);

        // EARLY REJECTION: Validate BEFORE entering matching engine
        if (!riskService.validate(order)) {
            order.setStatus("REJECTED");
            orderRepo.save(order);
            System.out.println("Order REJECTED for " + order.getSymbol() + " - Risk check failed");
            return;
        }

        // Order is valid - proceed to matching
        order.setStatus("OPEN");
        activeOrders.put(order.getId(), order);

        System.out.println("Order OPEN for " + order.getSymbol() + ": " + order.getType() +
                " " + order.getQuantity() + " @ $" + order.getPrice());

        SymbolMatchingWorker worker = getOrCreateWorker(order.getSymbol());
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

    /**
     * Update trader cache after a trade completes
     */
    public void updateTraderCache(UUID traderId, double balanceDelta, String symbol, int positionDelta) {
        traderCache.computeIfPresent(traderId, (id, snapshot) -> {
            snapshot.balance += balanceDelta;
            if (positionDelta != 0) {
                snapshot.positions.merge(symbol, positionDelta, Integer::sum);
            }
            return snapshot;
        });
    }

    /**
     * Refresh cache for a specific trader (call after external updates)
     */
    public void refreshTraderCache(UUID traderId) {
        traderRepo.findById(traderId).ifPresent(trader ->
                traderCache.put(traderId, new TraderSnapshot(trader))
        );
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

    /**
     * Lightweight trader snapshot for caching
     * Reduces memory footprint vs caching full Trader entities
     */
    private static class TraderSnapshot {
        UUID id;
        double balance;
        Map<String, Integer> positions;

        TraderSnapshot(Trader trader) {
            this.id = trader.getId();
            this.balance = trader.getBalance();
            this.positions = new ConcurrentHashMap<>(trader.getPositions());
        }

        Trader toTrader() {
            Trader trader = new Trader();
            trader.setId(id);
            trader.setBalance(balance);
            trader.setPositions(new HashMap<>(positions));
            return trader;
        }
    }
}