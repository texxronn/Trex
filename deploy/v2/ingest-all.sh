#!/usr/bin/env bash
#
# ingest-all.sh — feed the standard statement exports into a running v2 compose stack.
#
#   ingest-all.sh [DIR]        DIR defaults to /opt/trex/statements
#
# Run it on the host, from the compose project directory (default /opt/trex), or set
# TREX_COMPOSE_DIR. Each file goes through the compose `ingest` service (tools profile), so
# evidence and facts land in the stack's own volumes — the sequencer stays on loopback.
#
# The mapping is the one the statements actually use; a file that is not present is skipped.
# Re-running is safe: a re-delivered file appends nothing (whole-observation dedup).
#
# Statements are private: keep them off git. This script is the recipe, not the data.

set -euo pipefail

dir="${1:-/opt/trex/statements}"
compose_dir="${TREX_COMPOSE_DIR:-$(pwd)}"
export TREX_CSV_DIR="$dir"

ing() { # sourceType account filename
    if [ ! -f "$dir/$3" ]; then
        echo "   skip $3 (not present)"
        return 0
    fi
    echo "== $3  ->  $2 ($1)"
    (cd "$compose_dir" && docker compose --profile tools run --rm -T ingest ingest \
        --source-type "$1" --account "$2" \
        --sequencer-url http://sequencer:8080 \
        --evidence /var/lib/trex/evidence "/data/$3")
}

ing ing-csv ing-salary              Salary_Account.csv
ing ing-csv ing-loan-offset         Loan_Offset.csv
ing ing-csv ing-mortgage-simplifier Mortgage_Simplifier.csv
ing ing-csv ing-variable-rate       Variable_Rate.csv
ing ing-csv ing-orange              Orange_Everyday.csv
ing ing-csv ing-credit-card         ING_Credit_Card.csv
ing bw-csv  bw-credit-card          Bankwest_Transactions_full.csv
ing cba-csv cba-smartaccess         CBA_SmartAccess.csv
ing cba-pdf cba-netsaver            CBA_NetSaver_TransactionSummary.pdf
