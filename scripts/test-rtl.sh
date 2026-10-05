#!/usr/bin/env bash
set -euo pipefail
# Historical single-cycle expectations are archived and must not run as pipeline checks.
echo "Single-cycle Verilator traces require pipeline adaptation; use make test-pipeline." >&2
exit 2
