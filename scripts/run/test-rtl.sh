#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
verilator_bin=${VERILATOR:-verilator}
cd "$project_dir"
export CCACHE_DIR="$project_dir/target/cache/ccache"
rtl_test_dir=${RTL_TEST_DIR:-target/rtl-test}
export RTL_TEST_DIR="$rtl_test_dir"
mkdir -p "$CCACHE_DIR" "$rtl_test_dir"
"$verilator_bin" --version
python3 - <<'PY'
from pathlib import Path
import os
root=Path(os.environ['RTL_TEST_DIR'])
# Hand-checked architectural requests from the textbook and expanded program.
fixed={
 'book':[(96,1,2,15,7),(96,2,2,0,0),(100,1,2,15,25)],
 'expanded':[(508,1,2,15,31),(508,2,2,0,0),(252,1,2,15,7),
   (508,2,2,0,0),(100,1,2,15,31),(104,1,2,15,0x128),(108,1,2,15,31)]}
for name in ('acceptance','acceptance-expanded'):
 rows=[list(map(lambda x:int(x,16),line.split()))
  for line in Path(f'target/acceptance-images/rv32-{name}.trace').read_text().splitlines()
  if line and not line.startswith('#')]
 fixed[name]=[(r[2],r[4],r[5],r[6],r[7]) for r in rows if r[4]]
for name,events in fixed.items():
 (root/f'{name}.events').write_text(''.join(' '.join(f'{v:08x}' for v in e)+'\n'
  for e in events))
PY
status=0
for scenario in book expanded acceptance acceptance-expanded; do
  case "$scenario" in
    book) rtl_file=target/generated/riscv-pipeline/RiscvPipeline.v ;;
    expanded) rtl_file=target/generated/riscv-pipeline128/RiscvPipeline.v ;;
    *) rtl_file="target/generated/rv32-$scenario/RiscvPipeline.v" ;;
  esac
  if [[ -n "${RTL_TEST_SOURCE_DIR:-}" ]]; then
    rtl_file="$RTL_TEST_SOURCE_DIR/$scenario/RiscvPipeline.v"
  fi
  build_dir="$rtl_test_dir/$scenario"
  if [[ "$build_dir" != /* ]]; then build_dir="$project_dir/$build_dir"; fi
  mkdir -p "$build_dir"
  if ! "$verilator_bin" --cc --exe --build -j 2 -Wall \
      -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL \
      --x-initial unique --x-assign unique \
      --top-module RiscvPipeline --prefix VRiscvPipeline --Mdir "$build_dir" \
      -CFLAGS '-std=c++17' "$rtl_file" "$project_dir/src/test/cpp/PipelineRtlTest.cpp" \
      > "$build_dir/build.log" 2>&1; then
    tail -80 "$build_dir/build.log" >&2
    status=1
    echo "BUILD_FAILURE $scenario"
    continue
  fi
  for seed in ${RTL_TEST_SEEDS:-1 17 2026}; do
    "$build_dir/VRiscvPipeline" "$rtl_test_dir/$scenario.events" "$seed" 300 || { status=1; echo "CPU_FAILURE $scenario seed=$seed"; }
  done
done

exit "$status"
