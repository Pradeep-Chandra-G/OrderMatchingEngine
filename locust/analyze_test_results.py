import requests
import json
from datetime import datetime
from collections import defaultdict

BASE_URL = "http://localhost:8080"

def print_header(title):
    """Print formatted header"""
    print("\n" + "="*80)
    print(f" {title}")
    print("="*80 + "\n")

def get_data():
    """Fetch all data from API"""
    try:
        orders = requests.get(f"{BASE_URL}/api/orders").json()
        trades = requests.get(f"{BASE_URL}/api/trades").json()
        traders = requests.get(f"{BASE_URL}/api/traders").json()
        return orders, trades, traders
    except Exception as e:
        print(f"Error fetching data: {e}")
        return None, None, None

def analyze_orders(orders):
    """Analyze order statistics"""
    print_header("ORDER ANALYSIS")

    total = len(orders)
    if total == 0:
        print("No orders found.")
        return

    # Status breakdown
    status_count = defaultdict(int)
    for order in orders:
        status_count[order['status']] += 1

    print(f"Total Orders: {total}")
    print(f"\nStatus Breakdown:")
    for status, count in sorted(status_count.items()):
        percentage = (count / total) * 100
        print(f"  {status:12s}: {count:6d} ({percentage:5.1f}%)")

    # Symbol breakdown
    symbol_count = defaultdict(int)
    for order in orders:
        symbol_count[order['symbol']] += 1

    print(f"\nSymbol Distribution:")
    for symbol, count in sorted(symbol_count.items(), key=lambda x: x[1], reverse=True):
        percentage = (count / total) * 100
        print(f"  {symbol:8s}: {count:6d} ({percentage:5.1f}%)")

    # Type breakdown
    buy_count = sum(1 for o in orders if o['type'] == 'BUY')
    sell_count = sum(1 for o in orders if o['type'] == 'SELL')
    print(f"\nOrder Type:")
    print(f"  BUY:  {buy_count} ({buy_count/total*100:.1f}%)")
    print(f"  SELL: {sell_count} ({sell_count/total*100:.1f}%)")

    # Open orders by symbol
    open_orders = [o for o in orders if o['status'] == 'OPEN']
    if open_orders:
        print(f"\nOpen Orders by Symbol:")
        open_by_symbol = defaultdict(lambda: {'buy': 0, 'sell': 0})
        for order in open_orders:
            if order['type'] == 'BUY':
                open_by_symbol[order['symbol']]['buy'] += 1
            else:
                open_by_symbol[order['symbol']]['sell'] += 1

        for symbol in sorted(open_by_symbol.keys()):
            buy = open_by_symbol[symbol]['buy']
            sell = open_by_symbol[symbol]['sell']
            print(f"  {symbol:8s}: {buy:3d} BUY, {sell:3d} SELL")

def analyze_trades(trades, orders):
    """Analyze trade statistics"""
    print_header("TRADE ANALYSIS")

    total_trades = len(trades)
    if total_trades == 0:
        print("No trades found.")
        return

    print(f"Total Trades Executed: {total_trades}")

    # Calculate total volume and value
    total_quantity = sum(t['quantity'] for t in trades)
    total_value = sum(t['quantity'] * t['price'] for t in trades)
    avg_price = sum(t['price'] for t in trades) / total_trades
    avg_quantity = total_quantity / total_trades

    print(f"\nTrade Metrics:")
    print(f"  Total Quantity: {total_quantity:,} shares")
    print(f"  Total Value: ${total_value:,.2f}")
    print(f"  Avg Trade Price: ${avg_price:.2f}")
    print(f"  Avg Trade Size: {avg_quantity:.1f} shares")

    # Trades by symbol
    symbol_trades = defaultdict(lambda: {'count': 0, 'volume': 0, 'value': 0})
    for trade in trades:
        # Get symbol from buy order
        buy_order = next((o for o in orders if o['id'] == trade['buyOrder']['id']), None)
        if buy_order:
            symbol = buy_order['symbol']
            symbol_trades[symbol]['count'] += 1
            symbol_trades[symbol]['volume'] += trade['quantity']
            symbol_trades[symbol]['value'] += trade['quantity'] * trade['price']

    print(f"\nTrades by Symbol:")
    for symbol in sorted(symbol_trades.keys()):
        data = symbol_trades[symbol]
        print(f"  {symbol:8s}: {data['count']:4d} trades, "
              f"{data['volume']:6,} shares, ${data['value']:,.2f}")

    # Price range by symbol
    print(f"\nPrice Ranges:")
    symbol_prices = defaultdict(list)
    for trade in trades:
        buy_order = next((o for o in orders if o['id'] == trade['buyOrder']['id']), None)
        if buy_order:
            symbol_prices[buy_order['symbol']].append(trade['price'])

    for symbol in sorted(symbol_prices.keys()):
        prices = symbol_prices[symbol]
        print(f"  {symbol:8s}: ${min(prices):.2f} - ${max(prices):.2f} "
              f"(avg: ${sum(prices)/len(prices):.2f})")

def analyze_traders(traders, orders, trades):
    """Analyze trader statistics"""
    print_header("TRADER ANALYSIS")

    print(f"Total Traders: {len(traders)}\n")

    for trader in sorted(traders, key=lambda t: t['name']):
        print(f"\nTrader: {trader['name']} ({trader['id']})")
        print(f"  Balance: ${trader['balance']:,.2f}")

        # Count orders
        trader_orders = [o for o in orders if o['trader']['id'] == trader['id']]
        filled = sum(1 for o in trader_orders if o['status'] == 'FILLED')
        open_orders = sum(1 for o in trader_orders if o['status'] == 'OPEN')
        rejected = sum(1 for o in trader_orders if o['status'] == 'REJECTED')

        print(f"  Orders: {len(trader_orders)} total "
              f"({filled} filled, {open_orders} open, {rejected} rejected)")

        # Positions
        if trader['positions']:
            print(f"  Positions:")
            for symbol, qty in sorted(trader['positions'].items()):
                print(f"    {symbol:8s}: {qty:6,} shares")
        else:
            print(f"  Positions: None")

        # Calculate trading activity
        buy_orders = sum(1 for o in trader_orders if o['type'] == 'BUY')
        sell_orders = sum(1 for o in trader_orders if o['type'] == 'SELL')
        print(f"  Activity: {buy_orders} buys, {sell_orders} sells")

def check_data_integrity(orders, trades, traders):
    """Check for data integrity issues"""
    print_header("DATA INTEGRITY CHECKS")

    issues = []

    # Check for negative balances
    for trader in traders:
        if trader['balance'] < 0:
            issues.append(f"⚠️  Trader {trader['name']} has negative balance: ${trader['balance']:.2f}")

    # Check for negative positions
    for trader in traders:
        for symbol, qty in trader['positions'].items():
            if qty < 0:
                issues.append(f"⚠️  Trader {trader['name']} has negative {symbol} position: {qty}")

    # Check for orphaned trades
    order_ids = {o['id'] for o in orders}
    for trade in trades:
        if trade['buyOrder']['id'] not in order_ids:
            issues.append(f"⚠️  Trade {trade['id']} references non-existent buy order")
        if trade['sellOrder']['id'] not in order_ids:
            issues.append(f"⚠️  Trade {trade['id']} references non-existent sell order")

    # Check for filled orders with remaining quantity
    for order in orders:
        if order['status'] == 'FILLED' and order['quantity'] > 0:
            issues.append(f"⚠️  Order {order['id']} marked FILLED but has quantity {order['quantity']}")

    if issues:
        print("Issues Found:")
        for issue in issues:
            print(f"  {issue}")
    else:
        print("✅ No integrity issues found!")

def calculate_match_rate(orders):
    """Calculate match rate"""
    print_header("MATCHING PERFORMANCE")

    total = len(orders)
    if total == 0:
        print("No orders to analyze.")
        return

    filled = sum(1 for o in orders if o['status'] == 'FILLED')
    open_orders = sum(1 for o in orders if o['status'] == 'OPEN')
    rejected = sum(1 for o in orders if o['status'] == 'REJECTED')

    match_rate = (filled / total) * 100 if total > 0 else 0

    print(f"Match Rate: {match_rate:.1f}%")
    print(f"\nBreakdown:")
    print(f"  Successfully Matched: {filled:6d} ({filled/total*100:5.1f}%)")
    print(f"  Awaiting Match:       {open_orders:6d} ({open_orders/total*100:5.1f}%)")
    print(f"  Rejected:             {rejected:6d} ({rejected/total*100:5.1f}%)")

    # Matching by symbol
    symbols = set(o['symbol'] for o in orders)
    print(f"\nMatch Rate by Symbol:")
    for symbol in sorted(symbols):
        symbol_orders = [o for o in orders if o['symbol'] == symbol]
        symbol_filled = sum(1 for o in symbol_orders if o['status'] == 'FILLED')
        symbol_total = len(symbol_orders)
        symbol_rate = (symbol_filled / symbol_total * 100) if symbol_total > 0 else 0
        print(f"  {symbol:8s}: {symbol_rate:5.1f}% ({symbol_filled}/{symbol_total})")

def generate_summary():
    """Generate comprehensive test summary"""
    print("\n" + "="*80)
    print(" LOAD TEST RESULTS SUMMARY")
    print("="*80)
    print(f"\nGenerated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    orders, trades, traders = get_data()

    if orders is None:
        print("\n❌ Failed to fetch data from server. Is it running?")
        return

    # Run all analyses
    analyze_orders(orders)
    analyze_trades(trades, orders)
    calculate_match_rate(orders)
    analyze_traders(traders, orders, trades)
    check_data_integrity(orders, trades, traders)

    # Final summary
    print_header("OVERALL SUMMARY")

    total_orders = len(orders)
    total_trades = len(trades)
    filled_orders = sum(1 for o in orders if o['status'] == 'FILLED')

    print(f"📊 Orders Submitted: {total_orders:,}")
    print(f"✅ Orders Filled: {filled_orders:,}")
    print(f"🤝 Trades Executed: {total_trades:,}")
    print(f"📈 Match Success Rate: {(filled_orders/total_orders*100) if total_orders > 0 else 0:.1f}%")

    if total_trades > 0:
        total_volume = sum(t['quantity'] for t in trades)
        total_value = sum(t['quantity'] * t['price'] for t in trades)
        print(f"💰 Total Volume: {total_volume:,} shares")
        print(f"💵 Total Value: ${total_value:,.2f}")

    print("\n" + "="*80)
    print(" Analysis Complete!")
    print("="*80 + "\n")

if __name__ == "__main__":
    generate_summary()