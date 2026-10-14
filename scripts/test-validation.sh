#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_dir"
suite=${1:-all}
case "$suite" in arch|coremark|all) ;; *) echo "Invalid suite: $suite" >&2; exit 1 ;; esac
build_dir="${VALIDATION_BUILD_DIR:-$project_dir/target/validation}/rtl"
export CCACHE_DIR="$project_dir/.cache/ccache"
mkdir -p "$build_dir" "$CCACHE_DIR"
verilator_bin=${VERILATOR:-verilator}
"$verilator_bin" --version
# The shared executable reads a private immutable image from each case's cwd.
# Derive build-local RTL; retain generated production RTL and its original path.
python3 scripts/prepare-validation-rtl.py "${VALIDATION_RTL_FILE:-generated/validation/RiscvPipeline.v}" "$build_dir/RiscvPipeline.v"
if ! "$verilator_bin" --cc --exe --build -j 2 -Wall \
    -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL \
    --x-initial unique --x-assign unique \
    --top-module RiscvPipeline --prefix VRiscvPipeline --Mdir "$build_dir" \
    -CFLAGS '-std=c++17' "$build_dir/RiscvPipeline.v" \
    "$project_dir/src/test/cpp/SignatureRtlTest.cpp" > "$build_dir/build.log" 2>&1; then
  tail -80 "$build_dir/build.log" >&2
  exit 1
fi
python3 scripts/run-validation.py "$suite"
