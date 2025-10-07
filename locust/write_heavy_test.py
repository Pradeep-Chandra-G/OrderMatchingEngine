from locust import HttpUser, task, between, events
import random
import json
import time
from datetime import datetime
from collections import defaultdict
import logging

# ============================================================================
# CONFIGURATION
# ============================================================================

# Update with your actual trader IDs
TRADERS = {
    "alice": "a0081bb2-42fa-4244-ad87-84a6c9a50f03",
    "bob": "a1b7f888-0f55-4df6-984d-e3e873969c7c",
    "charlie": "37f22c79-0799-4c5c-bf68-d2b89c0aba58"
}

# Expanded symbol universe for realistic testing
SYMBOLS = [
    "AAPL", "GOOGL", "MSFT", "AMZN", "TSLA",
    "META", "NVDA", "AMD", "INTC", "ORCL",
    "IBM", "CSCO", "ADBE", "CRM", "NFLX"
]

# Base prices for each symbol
BASE_PRICES = {
    "AAPL": 150.0, "GOOGL": 140.0, "MSFT": 380.0, "AMZN": 145.0, "TSLA": 250.0,
    "META": 320.0, "NVDA": 480.0, "AMD": 140.0, "INTC": 45.0, "ORCL": 110.0,
    "IBM": 155.0, "CSCO": 52.0, "ADBE": 550.0, "CRM": 220.0, "NFLX": 450.0
}

# Consistency tracking
consistency_tracker = {
    "orders_submitted": 0,
    "orders_rejected": 0,
    "response_times": [],
    "order_ids": set(),
    "duplicate_ids": 0,
    "invalid_responses": 0
}

# Latency buckets
latency_buckets = {
    "< 5ms": 0,
    "5-10ms": 0,
    "10-20ms": 0,
    "20-50ms": 0,
    "50-100ms": 0,
    "> 100ms": 0
}

# ============================================================================
# HELPER FUNCTIONS
# ============================================================================

def track_latency(response_time):
    """Track latency distribution"""
    if response_time < 5:
        latency_buckets["< 5ms"] += 1
    elif response_time < 10:
        latency_buckets["5-10ms"] += 1
    elif response_time < 20:
        latency_buckets["10-20ms"] += 1
    elif response_time < 50:
        latency_buckets["20-50ms"] += 1
    elif response_time < 100:
        latency_buckets["50-100ms"] += 1
    else:
        latency_buckets["> 100ms"] += 1

def generate_order_price(symbol, order_type, strategy="normal"):
    """Generate order price based on strategy"""
    base = BASE_PRICES[symbol]

    if strategy == "aggressive":
        # Aggressive orders likely to match immediately
        if order_type == "BUY":
            return round(base + random.uniform(5, 15), 2)
        else:
            return round(base - random.uniform(5, 15), 2)
    elif strategy == "passive":
        # Passive orders less likely to match immediately
        if order_type == "BUY":
            return round(base - random.uniform(5, 15), 2)
        else:
            return round(base + random.uniform(5, 15), 2)
    else:  # normal
        # Normal market making around base price
        return round(base + random.uniform(-10, 10), 2)

# ============================================================================
# TEST SCENARIO 1: HIGH FREQUENCY MARKET MAKING
# ============================================================================

class HighFrequencyMarketMaker(HttpUser):
    """
    Simulates high-frequency market maker behavior
    - Very fast order submission (sub-second)
    - Alternating buy/sell orders
    - Tight spreads around market price
    - Tests: RPS, latency under sustained load
    """
    wait_time = between(0.05, 0.2)  # 50-200ms between orders
    weight = 3  # Higher weight = more users of this type

    def on_start(self):
        self.trader_id = random.choice(list(TRADERS.values()))
        self.order_count = 0
        print(f"[HFT Market Maker] Started with trader: {self.trader_id}")

    @task(10)
    def place_market_making_order(self):
        """Place tight bid/ask orders"""
        symbol = random.choice(SYMBOLS)
        order_type = "BUY" if random.random() < 0.5 else "SELL"

        order = {
            "traderId": self.trader_id,
            "symbol": symbol,
            "type": order_type,
            "orderType": "LIMIT",
            "price": generate_order_price(symbol, order_type, "normal"),
            "quantity": random.randint(10, 100)
        }

        start_time = time.time()
        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[HFT] Market Making Order"
        ) as response:
            response_time = (time.time() - start_time) * 1000
            track_latency(response_time)
            consistency_tracker["response_times"].append(response_time)

            if response.status_code == 200:
                try:
                    data = response.json()
                    order_id = data.get('id')

                    # Check for duplicate IDs (consistency issue)
                    if order_id in consistency_tracker["order_ids"]:
                        consistency_tracker["duplicate_ids"] += 1
                        print(f"⚠️  DUPLICATE ORDER ID: {order_id}")
                    else:
                        consistency_tracker["order_ids"].add(order_id)

                    consistency_tracker["orders_submitted"] += 1
                    self.order_count += 1
                    response.success()
                except json.JSONDecodeError:
                    consistency_tracker["invalid_responses"] += 1
                    response.failure("Invalid JSON response")
            elif response.status_code == 400:
                consistency_tracker["orders_rejected"] += 1
                response.success()  # Expected rejection is not a failure
            else:
                response.failure(f"Unexpected status: {response.status_code}")

# ============================================================================
# TEST SCENARIO 2: BURST TRADING
# ============================================================================

class BurstTrader(HttpUser):
    """
    Simulates burst trading patterns
    - Rapid order submission in bursts
    - Tests system behavior under sudden load spikes
    - Tests: Peak RPS handling, latency spikes
    """
    wait_time = between(2, 5)  # Longer wait between bursts
    weight = 2

    def on_start(self):
        self.trader_id = random.choice(list(TRADERS.values()))
        print(f"[Burst Trader] Started with trader: {self.trader_id}")

    @task(1)
    def execute_burst(self):
        """Execute a burst of orders"""
        burst_size = random.randint(10, 30)
        symbol = random.choice(SYMBOLS)

        for i in range(burst_size):
            order_type = "BUY" if i % 2 == 0 else "SELL"

            order = {
                "traderId": self.trader_id,
                "symbol": symbol,
                "type": order_type,
                "orderType": "LIMIT",
                "price": generate_order_price(symbol, order_type, "normal"),
                "quantity": random.randint(5, 50)
            }

            start_time = time.time()
            with self.client.post(
                "/api/orders",
                json=order,
                catch_response=True,
                name="[BURST] Rapid Order"
            ) as response:
                response_time = (time.time() - start_time) * 1000
                track_latency(response_time)
                consistency_tracker["response_times"].append(response_time)

                if response.status_code == 200:
                    try:
                        data = response.json()
                        order_id = data.get('id')
                        if order_id in consistency_tracker["order_ids"]:
                            consistency_tracker["duplicate_ids"] += 1
                        else:
                            consistency_tracker["order_ids"].add(order_id)
                        consistency_tracker["orders_submitted"] += 1
                        response.success()
                    except:
                        consistency_tracker["invalid_responses"] += 1
                        response.failure("Invalid JSON")
                elif response.status_code == 400:
                    consistency_tracker["orders_rejected"] += 1
                    response.success()
                else:
                    response.failure(f"Unexpected status: {response.status_code}")

            # Very short delay between orders in burst
            time.sleep(0.01)

# ============================================================================
# TEST SCENARIO 3: AGGRESSIVE LIQUIDITY TAKER
# ============================================================================

class AggressiveTaker(HttpUser):
    """
    Simulates aggressive traders who take liquidity
    - Orders designed to match immediately
    - Tests matching engine performance
    - Tests: Order matching speed, consistency
    """
    wait_time = between(0.1, 0.5)
    weight = 2

    def on_start(self):
        self.trader_id = random.choice(list(TRADERS.values()))
        print(f"[Aggressive Taker] Started with trader: {self.trader_id}")

    @task(5)
    def aggressive_buy(self):
        """Place aggressive buy order"""
        symbol = random.choice(SYMBOLS)

        order = {
            "traderId": self.trader_id,
            "symbol": symbol,
            "type": "BUY",
            "orderType": "LIMIT",
            "price": generate_order_price(symbol, "BUY", "aggressive"),
            "quantity": random.randint(20, 100)
        }

        start_time = time.time()
        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[AGG] Buy Order"
        ) as response:
            response_time = (time.time() - start_time) * 1000
            track_latency(response_time)
            consistency_tracker["response_times"].append(response_time)

            if response.status_code == 200:
                try:
                    data = response.json()
                    order_id = data.get('id')
                    if order_id in consistency_tracker["order_ids"]:
                        consistency_tracker["duplicate_ids"] += 1
                    else:
                        consistency_tracker["order_ids"].add(order_id)
                    consistency_tracker["orders_submitted"] += 1
                    response.success()
                except:
                    consistency_tracker["invalid_responses"] += 1
                    response.failure("Invalid JSON")
            elif response.status_code == 400:
                consistency_tracker["orders_rejected"] += 1
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")

    @task(5)
    def aggressive_sell(self):
        """Place aggressive sell order"""
        symbol = random.choice(SYMBOLS)

        order = {
            "traderId": self.trader_id,
            "symbol": symbol,
            "type": "SELL",
            "orderType": "LIMIT",
            "price": generate_order_price(symbol, "SELL", "aggressive"),
            "quantity": random.randint(20, 100)
        }

        start_time = time.time()
        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[AGG] Sell Order"
        ) as response:
            response_time = (time.time() - start_time) * 1000
            track_latency(response_time)
            consistency_tracker["response_times"].append(response_time)

            if response.status_code == 200:
                try:
                    data = response.json()
                    order_id = data.get('id')
                    if order_id in consistency_tracker["order_ids"]:
                        consistency_tracker["duplicate_ids"] += 1
                    else:
                        consistency_tracker["order_ids"].add(order_id)
                    consistency_tracker["orders_submitted"] += 1
                    response.success()
                except:
                    consistency_tracker["invalid_responses"] += 1
                    response.failure("Invalid JSON")
            elif response.status_code == 400:
                consistency_tracker["orders_rejected"] += 1
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")

# ============================================================================
# TEST SCENARIO 4: PASSIVE LIQUIDITY PROVIDER
# ============================================================================

class PassiveLiquidityProvider(HttpUser):
    """
    Simulates passive liquidity providers
    - Orders away from current price
    - Provides depth to order book
    - Tests: Order book depth management
    """
    wait_time = between(0.3, 1.0)
    weight = 1

    def on_start(self):
        self.trader_id = random.choice(list(TRADERS.values()))
        print(f"[Liquidity Provider] Started with trader: {self.trader_id}")

    @task(3)
    def provide_bid_liquidity(self):
        """Place passive buy orders (below market)"""
        symbol = random.choice(SYMBOLS)

        order = {
            "traderId": self.trader_id,
            "symbol": symbol,
            "type": "BUY",
            "orderType": "LIMIT",
            "price": generate_order_price(symbol, "BUY", "passive"),
            "quantity": random.randint(50, 200)
        }

        start_time = time.time()
        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[PASSIVE] Bid Liquidity"
        ) as response:
            response_time = (time.time() - start_time) * 1000
            track_latency(response_time)
            consistency_tracker["response_times"].append(response_time)

            if response.status_code == 200:
                try:
                    data = response.json()
                    order_id = data.get('id')
                    if order_id in consistency_tracker["order_ids"]:
                        consistency_tracker["duplicate_ids"] += 1
                    else:
                        consistency_tracker["order_ids"].add(order_id)
                    consistency_tracker["orders_submitted"] += 1
                    response.success()
                except:
                    consistency_tracker["invalid_responses"] += 1
                    response.failure("Invalid JSON")
            elif response.status_code == 400:
                consistency_tracker["orders_rejected"] += 1
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")

    @task(3)
    def provide_ask_liquidity(self):
        """Place passive sell orders (above market)"""
        symbol = random.choice(SYMBOLS)

        order = {
            "traderId": self.trader_id,
            "symbol": symbol,
            "type": "SELL",
            "orderType": "LIMIT",
            "price": generate_order_price(symbol, "SELL", "passive"),
            "quantity": random.randint(50, 200)
        }

        start_time = time.time()
        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[PASSIVE] Ask Liquidity"
        ) as response:
            response_time = (time.time() - start_time) * 1000
            track_latency(response_time)
            consistency_tracker["response_times"].append(response_time)

            if response.status_code == 200:
                try:
                    data = response.json()
                    order_id = data.get('id')
                    if order_id in consistency_tracker["order_ids"]:
                        consistency_tracker["duplicate_ids"] += 1
                    else:
                        consistency_tracker["order_ids"].add(order_id)
                    consistency_tracker["orders_submitted"] += 1
                    response.success()
                except:
                    consistency_tracker["invalid_responses"] += 1
                    response.failure("Invalid JSON")
            elif response.status_code == 400:
                consistency_tracker["orders_rejected"] += 1
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")

# ============================================================================
# TEST SCENARIO 5: CONCURRENT MATCHING TEST
# ============================================================================

class ConcurrentMatchTester(HttpUser):
    """
    Tests concurrent matching of same price level
    - Multiple traders submit orders at exact same price/time
    - Tests: Race conditions, locking, ACID properties
    """
    wait_time = between(0.01, 0.05)  # Very fast to create concurrency
    weight = 2

    def on_start(self):
        self.trader_id = random.choice(list(TRADERS.values()))
        print(f"[Concurrent Tester] Started with trader: {self.trader_id}")

    @task(10)
    def concurrent_order(self):
        """Submit orders designed to match concurrently"""
        symbol = random.choice(SYMBOLS[:5])  # Focus on fewer symbols
        base_price = BASE_PRICES[symbol]

        # Use exact prices to maximize concurrent matching
        order_type = random.choice(["BUY", "SELL"])
        price = round(base_price + random.choice([-5, -2, 0, 2, 5]), 2)

        order = {
            "traderId": self.trader_id,
            "symbol": symbol,
            "type": order_type,
            "orderType": "LIMIT",
            "price": price,
            "quantity": random.randint(10, 50)
        }

        start_time = time.time()
        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[CONCURRENT] Race Order"
        ) as response:
            response_time = (time.time() - start_time) * 1000
            track_latency(response_time)
            consistency_tracker["response_times"].append(response_time)

            if response.status_code == 200:
                try:
                    data = response.json()
                    order_id = data.get('id')
                    if order_id in consistency_tracker["order_ids"]:
                        consistency_tracker["duplicate_ids"] += 1
                        print(f"🔴 CONSISTENCY ERROR: Duplicate ID {order_id}")
                    else:
                        consistency_tracker["order_ids"].add(order_id)
                    consistency_tracker["orders_submitted"] += 1
                    response.success()
                except:
                    consistency_tracker["invalid_responses"] += 1
                    response.failure("Invalid JSON")
            elif response.status_code == 400:
                consistency_tracker["orders_rejected"] += 1
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")

# ============================================================================
# EVENT HANDLERS FOR REPORTING
# ============================================================================

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    """Print test configuration"""
    print("\n" + "="*80)
    print("WRITE-INTENSIVE LOAD TEST - STOCK EXCHANGE")
    print("="*80)
    print(f"Traders: {len(TRADERS)}")
    print(f"Symbols: {len(SYMBOLS)} ({', '.join(SYMBOLS[:5])}...)")
    print(f"Test Scenarios:")
    print(f"  1. High-Frequency Market Maker (weight=3)")
    print(f"  2. Burst Trader (weight=2)")
    print(f"  3. Aggressive Taker (weight=2)")
    print(f"  4. Passive Liquidity Provider (weight=1)")
    print(f"  5. Concurrent Match Tester (weight=2)")
    print("="*80)
    print("Metrics to Monitor:")
    print("  - RPS (Requests Per Second)")
    print("  - Latency Distribution")
    print("  - Order Acceptance Rate")
    print("  - Consistency (duplicate IDs, invalid responses)")
    print("="*80 + "\n")

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    """Print comprehensive statistics"""
    print("\n" + "="*80)
    print("WRITE-INTENSIVE LOAD TEST - RESULTS")
    print("="*80 + "\n")

    # Consistency Report
    print("CONSISTENCY & RELIABILITY:")
    print("-" * 40)
    total_orders = consistency_tracker["orders_submitted"]
    rejected = consistency_tracker["orders_rejected"]
    duplicates = consistency_tracker["duplicate_ids"]
    invalid = consistency_tracker["invalid_responses"]

    print(f"Total Orders Submitted: {total_orders:,}")
    print(f"Orders Rejected: {rejected:,} ({rejected/(total_orders+rejected)*100:.2f}%)")
    print(f"Duplicate IDs: {duplicates}")
    print(f"Invalid Responses: {invalid}")
    print(f"Unique Order IDs: {len(consistency_tracker['order_ids']):,}")

    if duplicates > 0:
        print(f"\n⚠️  CRITICAL: {duplicates} duplicate order IDs detected!")
        print("   This indicates a consistency/race condition issue.")
    else:
        print(f"\n✅ No duplicate order IDs detected")

    # Latency Distribution
    print("\n\nLATENCY DISTRIBUTION:")
    print("-" * 40)
    for bucket, count in latency_buckets.items():
        if count > 0:
            pct = (count / len(consistency_tracker["response_times"])) * 100
            print(f"{bucket:12s}: {count:6,} ({pct:5.1f}%)")

    # Calculate percentiles
    if consistency_tracker["response_times"]:
        times = sorted(consistency_tracker["response_times"])
        p50 = times[int(len(times) * 0.50)]
        p95 = times[int(len(times) * 0.95)]
        p99 = times[int(len(times) * 0.99)]
        avg = sum(times) / len(times)

        print(f"\nLatency Percentiles:")
        print(f"  Average: {avg:.2f}ms")
        print(f"  p50:     {p50:.2f}ms")
        print(f"  p95:     {p95:.2f}ms")
        print(f"  p99:     {p99:.2f}ms")

    # Performance Assessment
    print("\n\nPERFORMANCE ASSESSMENT:")
    print("-" * 40)

    if consistency_tracker["response_times"]:
        avg_latency = sum(consistency_tracker["response_times"]) / len(consistency_tracker["response_times"])

        if avg_latency < 10:
            print("✅ Latency: EXCELLENT (< 10ms average)")
        elif avg_latency < 20:
            print("✅ Latency: GOOD (< 20ms average)")
        elif avg_latency < 50:
            print("⚠️  Latency: ACCEPTABLE (< 50ms average)")
        else:
            print("🔴 Latency: NEEDS IMPROVEMENT (> 50ms average)")

    if duplicates == 0 and invalid == 0:
        print("✅ Consistency: PERFECT")
    elif duplicates > 0:
        print("🔴 Consistency: FAILED (duplicate IDs detected)")
    else:
        print("⚠️  Consistency: ISSUES DETECTED")

    total_attempts = total_orders + rejected
    rejection_rate = (rejected / total_attempts * 100) if total_attempts > 0 else 0

    if rejection_rate < 5:
        print(f"✅ Reliability: EXCELLENT ({rejection_rate:.1f}% rejection rate)")
    elif rejection_rate < 10:
        print(f"⚠️  Reliability: ACCEPTABLE ({rejection_rate:.1f}% rejection rate)")
    else:
        print(f"🔴 Reliability: POOR ({rejection_rate:.1f}% rejection rate)")

    print("\n" + "="*80)
    print("Test complete! Check Locust web UI for detailed RPS metrics.")
    print("="*80 + "\n")


# ============================================================================
# USAGE INSTRUCTIONS
# ============================================================================
"""
USAGE:

1. Start your Spring Boot application

2. Run with web UI (recommended):
   locust -f write_intensive_loadtest.py --host=http://localhost:8080

   Then open http://localhost:8089 in browser and configure:
   - Number of users: 3-50
   - Spawn rate: 1-10 per second
   - Duration: 5-30 minutes

3. Run headless (command line):

   # Light load test (3 users, 1 req/s spawn rate, 5 min)
   locust -f write_intensive_loadtest.py --host=http://localhost:8080 \
          --users=3 --spawn-rate=1 --run-time=5m --headless

   # Medium load test (10 users, 2 req/s spawn rate, 10 min)
   locust -f write_intensive_loadtest.py --host=http://localhost:8080 \
          --users=10 --spawn-rate=2 --run-time=10m --headless

   # Heavy load test (30 users, 5 req/s spawn rate, 15 min)
   locust -f write_intensive_loadtest.py --host=http://localhost:8080 \
          --users=30 --spawn-rate=5 --run-time=15m --headless

   # Stress test (50 users, 10 req/s spawn rate, 10 min)
   locust -f write_intensive_loadtest.py --host=http://localhost:8080 \
          --users=50 --spawn-rate=10 --run-time=10m --headless

4. Generate reports:
   locust -f write_intensive_loadtest.py --host=http://localhost:8080 \
          --users=10 --spawn-rate=2 --run-time=10m --headless \
          --html=report.html --csv=results

KEY METRICS TO MONITOR:
- RPS (Requests/Second): Target 100+ for production
- Average Latency: Target < 10ms for writes
- p95 Latency: Target < 20ms
- p99 Latency: Target < 50ms
- Error Rate: Target < 0.1%
- Duplicate IDs: Target = 0 (critical for consistency)

WHAT EACH SCENARIO TESTS:
- HFT Market Maker: Sustained high RPS, latency consistency
- Burst Trader: Peak load handling, latency spikes
- Aggressive Taker: Matching engine performance
- Passive Liquidity Provider: Order book depth management
- Concurrent Match Tester: Race conditions, ACID properties

RECOMMENDED TEST SEQUENCE:
1. Baseline: 3 users, 5 min → Establish baseline performance
2. Moderate: 10 users, 10 min → Test normal production load
3. Heavy: 30 users, 15 min → Test peak production load
4. Stress: 50 users, 10 min → Find breaking point
"""