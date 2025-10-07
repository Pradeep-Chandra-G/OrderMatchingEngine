from locust import HttpUser, task, between, events
from locust.contrib.fasthttp import FastHttpUser
import random
import json
from datetime import datetime

# Configuration - Update these with actual trader IDs after creating test users
TRADERS = {
    "alice": {
        "id": "6691986d-219d-4066-b40a-a6e67d0d3daa",
        "initial_balance": 1000000,
        "initial_positions": {"AAPL": 1000, "GOOGL": 1000, "MSFT": 1000}
    },
    "bob": {
        "id": "c180c78f-232b-473e-b120-a0b81c60156f",
        "initial_balance": 1000000,
        "initial_positions": {"AAPL": 1000, "GOOGL": 1000, "MSFT": 1000}
    },
    "charlie": {
        "id": "57986fa1-feec-453b-a8e7-19c838edb3b6",
        "initial_balance": 1000000,
        "initial_positions": {"AAPL": 1000, "GOOGL": 1000, "MSFT": 1000}
    }
}

SYMBOLS = ["AAPL", "GOOGL", "MSFT"]
BASE_PRICES = {"AAPL": 150, "GOOGL": 140, "MSFT": 380}

# Statistics tracking
trade_stats = {
    "total_orders": 0,
    "matched_orders": 0,
    "rejected_orders": 0,
    "open_orders": 0
}


class Scenario1User(HttpUser):
    """
    Scenario 1: Single Symbol High Frequency Trading
    - All users trade only AAPL
    - Random buy/sell orders
    - Tests matching engine performance under concentrated trading
    """
    wait_time = between(0.1, 0.5)  # Very fast order submission

    def on_start(self):
        """Initialize user session"""
        self.trader = random.choice(list(TRADERS.values()))
        print(f"[Scenario 1] Started user with trader ID: {self.trader['id']}")

    @task(5)
    def place_buy_order(self):
        """Place a buy order for AAPL"""
        symbol = "AAPL"
        base_price = BASE_PRICES[symbol]

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": "BUY",
            "orderType": "LIMIT",
            "price": round(base_price + random.uniform(-10, 10), 2),
            "quantity": random.randint(10, 100)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[S1] Place Buy Order"
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")

    @task(5)
    def place_sell_order(self):
        """Place a sell order for AAPL"""
        symbol = "AAPL"
        base_price = BASE_PRICES[symbol]

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": "SELL",
            "orderType": "LIMIT",
            "price": round(base_price + random.uniform(-10, 10), 2),
            "quantity": random.randint(10, 100)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name="[S1] Place Sell Order"
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")

    @task(2)
    def check_orders(self):
        """Check order status"""
        self.client.get(
            f"/api/orders/symbol/AAPL",
            name="[S1] Check AAPL Orders"
        )

    @task(1)
    def check_trades(self):
        """Check executed trades"""
        self.client.get(
            "/api/trades",
            name="[S1] Check Trades"
        )


class Scenario2User(HttpUser):
    """
    Scenario 2: Multi-Symbol Distributed Trading
    - Users randomly trade across AAPL, GOOGL, MSFT
    - Tests matching engine's ability to handle multiple order books simultaneously
    """
    wait_time = between(0.2, 1.0)

    def on_start(self):
        """Initialize user session"""
        self.trader = random.choice(list(TRADERS.values()))
        print(f"[Scenario 2] Started user with trader ID: {self.trader['id']}")

    @task(3)
    def place_random_buy_order(self):
        """Place a buy order for random symbol"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": "BUY",
            "orderType": "LIMIT",
            "price": round(base_price + random.uniform(-15, 15), 2),
            "quantity": random.randint(5, 50)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name=f"[S2] Place Buy Order ({symbol})"
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")

    @task(3)
    def place_random_sell_order(self):
        """Place a sell order for random symbol"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": "SELL",
            "orderType": "LIMIT",
            "price": round(base_price + random.uniform(-15, 15), 2),
            "quantity": random.randint(5, 50)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name=f"[S2] Place Sell Order ({symbol})"
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")

    @task(1)
    def check_symbol_orders(self):
        """Check orders for random symbol"""
        symbol = random.choice(SYMBOLS)
        self.client.get(
            f"/api/orders/symbol/{symbol}",
            name=f"[S2] Check Orders ({symbol})"
        )


class Scenario3User(HttpUser):
    """
    Scenario 3: Aggressive Matching - Overlapping Prices
    - Buy orders at higher prices
    - Sell orders at lower prices
    - Ensures immediate matching and tests race conditions
    """
    wait_time = between(0.1, 0.3)

    def on_start(self):
        """Initialize user session"""
        self.trader = random.choice(list(TRADERS.values()))
        self.order_count = 0
        print(f"[Scenario 3] Started user with trader ID: {self.trader['id']}")

    @task(5)
    def place_aggressive_buy(self):
        """Place buy order above market price (should match immediately)"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]

        # Buy at higher price to ensure matching
        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": "BUY",
            "orderType": "LIMIT",
            "price": round(base_price + random.uniform(5, 20), 2),  # Above market
            "quantity": random.randint(10, 50)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name=f"[S3] Aggressive Buy ({symbol})"
        ) as response:
            if response.status_code == 200:
                self.order_count += 1
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")

    @task(5)
    def place_aggressive_sell(self):
        """Place sell order below market price (should match immediately)"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]

        # Sell at lower price to ensure matching
        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": "SELL",
            "orderType": "LIMIT",
            "price": round(base_price - random.uniform(5, 20), 2),  # Below market
            "quantity": random.randint(10, 50)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name=f"[S3] Aggressive Sell ({symbol})"
        ) as response:
            if response.status_code == 200:
                self.order_count += 1
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")

    @task(2)
    def verify_immediate_execution(self):
        """Verify that trades are being executed"""
        with self.client.get(
            "/api/trades",
            catch_response=True,
            name="[S3] Verify Trades"
        ) as response:
            if response.status_code == 200:
                try:
                    trades = response.json()
                    if len(trades) > 0:
                        response.success()
                    else:
                        response.failure("No trades executed yet")
                except:
                    response.failure("Failed to parse trades")


class Scenario4User(HttpUser):
    """
    Scenario 4: Race Condition Test
    - Multiple users try to match the same order simultaneously
    - Tests locking mechanism and concurrent order matching
    """
    wait_time = between(0.05, 0.2)  # Very fast to create race conditions

    def on_start(self):
        """Initialize user session"""
        self.trader = random.choice(list(TRADERS.values()))
        print(f"[Scenario 4] Started user with trader ID: {self.trader['id']}")

    @task(10)
    def rapid_fire_orders(self):
        """Submit orders rapidly to create race conditions"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]
        order_type = random.choice(["BUY", "SELL"])

        # Create tight price ranges to increase matching probability
        if order_type == "BUY":
            price = round(base_price + random.uniform(0, 5), 2)
        else:
            price = round(base_price - random.uniform(0, 5), 2)

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": order_type,
            "orderType": "LIMIT",
            "price": price,
            "quantity": random.randint(5, 20)
        }

        with self.client.post(
            "/api/orders",
            json=order,
            catch_response=True,
            name=f"[S4] Rapid {order_type} ({symbol})"
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Failed: {response.status_code}")


class MixedScenarioUser(HttpUser):
    """
    Mixed Scenario: Realistic Trading Pattern
    - Combines all scenarios with weighted probabilities
    - Most realistic load test
    """
    wait_time = between(0.5, 2.0)

    def on_start(self):
        """Initialize user session"""
        self.trader = random.choice(list(TRADERS.values()))
        print(f"[Mixed] Started user with trader ID: {self.trader['id']}")

    @task(3)
    def normal_trading(self):
        """Normal market orders around base price"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]
        order_type = random.choice(["BUY", "SELL"])

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": order_type,
            "orderType": "LIMIT",
            "price": round(base_price + random.uniform(-10, 10), 2),
            "quantity": random.randint(10, 100)
        }

        self.client.post("/api/orders", json=order, name="[Mixed] Normal Order")

    @task(2)
    def aggressive_trading(self):
        """Aggressive orders that should match immediately"""
        symbol = random.choice(SYMBOLS)
        base_price = BASE_PRICES[symbol]
        order_type = random.choice(["BUY", "SELL"])

        if order_type == "BUY":
            price = round(base_price + random.uniform(10, 25), 2)
        else:
            price = round(base_price - random.uniform(10, 25), 2)

        order = {
            "traderId": self.trader["id"],
            "symbol": symbol,
            "type": order_type,
            "orderType": "LIMIT",
            "price": price,
            "quantity": random.randint(20, 80)
        }

        self.client.post("/api/orders", json=order, name="[Mixed] Aggressive Order")

    @task(1)
    def check_status(self):
        """Check various endpoints"""
        endpoints = [
            "/api/orders",
            "/api/trades",
            f"/api/orders/symbol/{random.choice(SYMBOLS)}"
        ]
        endpoint = random.choice(endpoints)
        self.client.get(endpoint, name="[Mixed] Status Check")


# Event handlers for statistics
@events.request.add_listener
def on_request(request_type, name, response_time, response_length, exception, **kwargs):
    """Track request statistics"""
    if exception:
        print(f"Request failed: {name} - {exception}")


@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    """Print test configuration at start"""
    print("\n" + "="*80)
    print("LOAD TEST STARTED")
    print("="*80)
    print(f"Traders configured: {len(TRADERS)}")
    print(f"Symbols: {SYMBOLS}")
    print(f"Base prices: {BASE_PRICES}")
    print("="*80 + "\n")


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    """Print final statistics"""
    print("\n" + "="*80)
    print("LOAD TEST COMPLETED")
    print("="*80)
    print("Check the Locust web UI for detailed statistics")
    print("Recommended: Check your application logs for matching statistics")
    print("="*80 + "\n")