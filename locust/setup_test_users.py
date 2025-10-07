import requests
import json

# Configuration
BASE_URL = "http://localhost:8080"  # Update with your server URL

# Test traders to create
TEST_TRADERS = [
    {
        "name": "Alice",
        "balance": 1000000.0,  # $1M starting balance
        "positions": {
            "AAPL": 1000,
            "GOOGL": 1000,
            "MSFT": 1000
        }
    },
    {
        "name": "Bob",
        "balance": 1000000.0,
        "positions": {
            "AAPL": 1000,
            "GOOGL": 1000,
            "MSFT": 1000
        }
    },
    {
        "name": "Charlie",
        "balance": 1000000.0,
        "positions": {
            "AAPL": 1000,
            "GOOGL": 1000,
            "MSFT": 1000
        }
    }
]


def create_traders():
    """Create test traders via API"""
    print("\n" + "="*80)
    print("CREATING TEST TRADERS")
    print("="*80 + "\n")

    created_traders = []

    for trader_data in TEST_TRADERS:
        try:
            response = requests.post(
                f"{BASE_URL}/api/traders",
                json=trader_data,
                headers={"Content-Type": "application/json"}
            )

            if response.status_code in [200, 201]:
                trader = response.json()
                created_traders.append(trader)
                print(f"✓ Created trader: {trader['name']}")
                print(f"  ID: {trader['id']}")
                print(f"  Balance: ${trader['balance']:,.2f}")
                print(f"  Positions: {trader['positions']}")
                print()
            else:
                print(f"✗ Failed to create trader {trader_data['name']}")
                print(f"  Status: {response.status_code}")
                print(f"  Response: {response.text}")
                print()

        except Exception as e:
            print(f"✗ Error creating trader {trader_data['name']}: {str(e)}\n")

    return created_traders


def display_locust_config(traders):
    """Display the configuration to update in locustfile.py"""
    print("\n" + "="*80)
    print("UPDATE YOUR LOCUSTFILE.PY WITH THESE TRADER IDs")
    print("="*80 + "\n")

    print("TRADERS = {")
    for i, trader in enumerate(traders):
        name = trader['name'].lower()
        print(f'    "{name}": {{')
        print(f'        "id": "{trader["id"]}",')
        print(f'        "initial_balance": {trader["balance"]},')
        print(f'        "initial_positions": {json.dumps(trader["positions"])}')
        print(f'    }}{("," if i < len(traders) - 1 else "")}')
    print("}")
    print()


def verify_setup():
    """Verify traders were created successfully"""
    print("\n" + "="*80)
    print("VERIFYING SETUP")
    print("="*80 + "\n")

    try:
        response = requests.get(f"{BASE_URL}/api/traders")
        if response.status_code == 200:
            traders = response.json()
            print(f"✓ Total traders in system: {len(traders)}")

            for trader in traders:
                print(f"\n  Trader: {trader['name']} ({trader['id']})")
                print(f"  Balance: ${trader['balance']:,.2f}")
                print(f"  Positions: {trader['positions']}")
        else:
            print(f"✗ Failed to verify traders: {response.status_code}")

    except Exception as e:
        print(f"✗ Error verifying setup: {str(e)}")


def main():
    print("\n" + "="*80)
    print("ORDER MATCHING ENGINE - LOAD TEST SETUP")
    print("="*80)
    print(f"Target: {BASE_URL}")
    print("="*80)

    # Create traders
    created_traders = create_traders()

    if created_traders:
        # Display configuration
        display_locust_config(created_traders)

        # Verify setup
        verify_setup()

        print("\n" + "="*80)
        print("SETUP COMPLETE!")
        print("="*80)
        print("\nNext steps:")
        print("1. Update the TRADERS dictionary in locustfile.py with the IDs above")
        print("2. Install Locust: pip install locust")
        print("3. Run load tests:")
        print("   - Scenario 1: locust -f locustfile.py --users 10 --spawn-rate 2 --tags scenario1")
        print("   - Scenario 2: locust -f locustfile.py --users 20 --spawn-rate 5 --tags scenario2")
        print("   - Scenario 3: locust -f locustfile.py --users 15 --spawn-rate 3 --tags scenario3")
        print("   - Scenario 4: locust -f locustfile.py --users 30 --spawn-rate 10 --tags scenario4")
        print("   - Mixed: locust -f locustfile.py --users 25 --spawn-rate 5")
        print("4. Access web UI: http://localhost:8089")
        print("="*80 + "\n")
    else:
        print("\n✗ No traders were created. Please check your server and try again.\n")


if __name__ == "__main__":
    main()