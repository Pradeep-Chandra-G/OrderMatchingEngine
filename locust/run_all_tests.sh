#!/bin/bash

# Order Matching Engine - Automated Load Test Suite
# This script runs all test scenarios sequentially and generates reports

set -e  # Exit on error

# Configuration
HOST="http://localhost:8080"
RESULTS_DIR="test_results_$(date +%Y%m%d_%H%M%S)"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================"
echo "Order Matching Engine - Load Test Suite"
echo -e "========================================${NC}\n"

# Create results directory
mkdir -p "$RESULTS_DIR"
echo -e "${GREEN}✓${NC} Created results directory: $RESULTS_DIR\n"

# Function to run a test scenario
run_test() {
    local scenario_name=$1
    local user_class=$2
    local num_users=$3
    local spawn_rate=$4
    local duration=$5

    echo -e "${YELLOW}Running: $scenario_name${NC}"
    echo "  Users: $num_users, Spawn Rate: $spawn_rate, Duration: $duration"

    locust -f locustfile.py $user_class \
        --host="$HOST" \
        --users=$num_users \
        --spawn-rate=$spawn_rate \
        --run-time=$duration \
        --headless \
        --html="$RESULTS_DIR/${scenario_name}_report.html" \
        --csv="$RESULTS_DIR/${scenario_name}" \
        --only-summary

    echo -e "${GREEN}✓${NC} Completed: $scenario_name\n"

    # Wait between tests
    echo "Cooling down for 10 seconds..."
    sleep 10
}

# Function to analyze results
analyze_results() {
    echo -e "${BLUE}Analyzing results...${NC}"
    python3 analyze_test_results.py > "$RESULTS_DIR/analysis.txt"
    echo -e "${GREEN}✓${NC} Analysis saved to: $RESULTS_DIR/analysis.txt\n"
}

# Check if server is running
echo "Checking if server is running..."
if curl -s "$HOST/api/traders" > /dev/null; then
    echo -e "${GREEN}✓${NC} Server is running at $HOST\n"
else
    echo -e "${RED}✗${NC} Server is not responding at $HOST"
    echo "Please start your Spring Boot application and try again."
    exit 1
fi

# Prompt user for confirmation
echo -e "${YELLOW}This will run the following test scenarios:${NC}"
echo "1. Baseline Test (5 users, 2min)"
echo "2. Scenario 1: Single Symbol High Frequency (10 users, 5min)"
echo "3. Scenario 2: Multi-Symbol Distributed (20 users, 5min)"
echo "4. Scenario 3: Aggressive Matching (15 users, 5min)"
echo "5. Scenario 4: Race Condition Test (30 users, 3min)"
echo "6. Mixed Scenario (25 users, 5min)"
echo "7. Stress Test (50 users, 5min)"
echo ""
echo -e "${YELLOW}Total estimated time: ~35 minutes${NC}"
echo ""
read -p "Continue? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
fi

# Start timestamp
START_TIME=$(date +%s)

echo -e "\n${BLUE}========================================"
echo "Starting Test Suite"
echo -e "========================================${NC}\n"

# Run all test scenarios
run_test "01_baseline" "MixedScenarioUser" 5 1 "2m"
run_test "02_scenario1_single_symbol" "Scenario1User" 10 2 "5m"
run_test "03_scenario2_multi_symbol" "Scenario2User" 20 5 "5m"
run_test "04_scenario3_aggressive" "Scenario3User" 15 3 "5m"
run_test "05_scenario4_race_conditions" "Scenario4User" 30 10 "3m"
run_test "06_mixed_scenario" "MixedScenarioUser" 25 5 "5m"
run_test "07_stress_test" "MixedScenarioUser" 50 10 "5m"

# Analyze final results
analyze_results

# Calculate duration
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
MINUTES=$((DURATION / 60))
SECONDS=$((DURATION % 60))

# Generate summary report
echo -e "\n${BLUE}========================================"
echo "Test Suite Complete!"
echo -e "========================================${NC}\n"

echo "Duration: ${MINUTES}m ${SECONDS}s"
echo "Results saved to: $RESULTS_DIR"
echo ""
echo "Generated files:"
ls -lh "$RESULTS_DIR" | tail -n +2 | awk '{print "  - " $9}'
echo ""

# Display quick summary
echo -e "${BLUE}Quick Summary:${NC}"
tail -n 20 "$RESULTS_DIR/analysis.txt"

echo ""
echo -e "${GREEN}✓${NC} All tests completed successfully!"
echo ""
echo "To view detailed reports:"
echo "  1. Open HTML reports in browser:"
echo "     open $RESULTS_DIR/*_report.html"
echo ""
echo "  2. View full analysis:"
echo "     cat $RESULTS_DIR/analysis.txt"
echo ""
echo "  3. Compare CSV statistics:"
echo "     ls $RESULTS_DIR/*.csv"
echo ""