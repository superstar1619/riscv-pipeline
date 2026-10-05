#!/usr/bin/env python3
"""Compare every program's committed signature with Spike, with finite bounds."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'target/validation'


def main():
    suite = sys.argv[1]
    manifest = json.loads((BUILD / f'{suite}.json').read_text())
    seeds = [int(s) for s in os.environ.get('VALIDATION_SEEDS', '1 17 2026').split()]
    assert seeds, 'No random seeds selected'
    executable = BUILD / 'rtl/VRiscvPipeline'
    results = []
    negative_checks = []
    for index, program in enumerate(manifest['programs']):
        # The compiled RTL has a fixed $readmemh filename, reloaded by each process.
        shutil.copyfile(ROOT / program['image'], BUILD / 'program.memfile')

        def simulate(reference, seed, limit):
            return subprocess.run([str(executable), str(reference), program['sigbase'],
                                   str(seed), str(limit), program['name']], cwd=ROOT,
                                  capture_output=True, text=True, timeout=180)

        if index == 0:
            # Prove the checker fails for a wrong signature and for non-completion.
            words = (ROOT / program['reference']).read_text().split()
            words[0] = f'{int(words[0], 16) ^ 1:08x}'
            poison = BUILD / 'poison.signature'
            poison.write_text('\n'.join(words) + '\n')
            for reason, reference, limit, message in [
                ('signature', poison, program['cycleLimit'], 'Signature word 0 differs'),
                ('timeout', ROOT / program['reference'], 1, 'Cycle limit reached')]:
                check = simulate(reference, seeds[0], limit)
                assert check.returncode != 0 and message in check.stderr, \
                    f'Negative {reason} check failed: {check.stdout}{check.stderr}'
                negative_checks.append(reason)
                print(f'PASS checker rejects {reason}', flush=True)

        for seed in seeds:
            check = simulate(ROOT / program['reference'], seed, program['cycleLimit'])
            output = check.stdout + check.stderr
            (BUILD / program['name'] / f'rtl-seed-{seed}.log').write_text(output)
            if check.returncode:
                raise RuntimeError(f'{program["name"]} seed={seed}: {output}')
            match = re.fullmatch(r'PASS (\S+) seed=(\d+) cycles=(\d+) signature_words=(\d+)'
                                 r'(?: benchmark_cycles=(\d+))?\s*', output)
            assert match and match[1] == program['name'] and int(match[2]) == seed, output
            assert int(match[4]) == program['signatureWords'], output
            result = dict(name=program['name'], seed=seed, cycles=int(match[3]),
                          signatureWords=int(match[4]))
            if match[5]:
                result['benchmarkCycles'] = int(match[5])
                result['cyclesPerIteration'] = int(match[5]) / 10
            results.append(result)
            print(output.strip(), flush=True)
    report = dict(manifest=manifest, seeds=seeds, negativeChecks=negative_checks, results=results)
    (BUILD / f'{suite}-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'PASS {suite}: {len(manifest["programs"])} programs x {len(seeds)} seeds', flush=True)


if __name__ == '__main__':
    main()
