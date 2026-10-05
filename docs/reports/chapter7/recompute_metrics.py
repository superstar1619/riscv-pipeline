#!/usr/bin/env python3
"""Audit archived evidence and recompute this report's numerical measurements.

Uses only the Python standard library. This audits prior test results; it does
not rerun a DUT simulation or claim new coverage.
"""
import csv
import hashlib
import json
from pathlib import Path
import re
import statistics

HERE = Path(__file__).resolve().parent


def compute():
    evidence = HERE / 'evidence'
    raw = json.loads((evidence / 'validation-results.json').read_text())
    programs = raw['manifest']['programs']
    assert len(programs) == 41 and len(raw['results']) == 123
    assert raw['seeds'] == [1, 17, 2026]
    assert raw['negativeChecks'] == ['signature', 'timeout']
    indexed = {p['name']: p for p in programs}
    artifacts = json.loads((evidence / 'artifact-manifest.json').read_text())
    assert len(indexed) == len(programs)
    seen = set()
    for result in raw['results']:
        key = result['name'], result['seed']
        assert key not in seen
        seen.add(key)
        program = indexed[result['name']]
        assert result['signatureWords'] == program['signatureWords']
        assert 0 < result['cycles'] <= program['cycleLimit']
    assert seen == {(p['name'], seed) for p in programs for seed in raw['seeds']}
    for p in programs:
        reference = evidence / f'{p["name"]}.signature'
        assert hashlib.sha256(reference.read_bytes()).hexdigest() == \
            artifacts['programArtifacts'][p['name']]['reference.signature']
        words = reference.read_text().split()
        assert len(words) == p['signatureWords']
        assert all(re.fullmatch(r'[0-9a-fA-F]{8}', word) for word in words)
        if p['name'].startswith('coremark'):
            assert words == p['crc'] and words[-2:] == ['00000000', '0000000a']
        results = [r for r in raw['results'] if r['name'] == p['name']]
        assert len({r['cycles'] for r in results}) == 1

    # Count the full Scala run from its own log, avoiding stale JUnit files and
    # the two suites subsequently overwritten by a testOnly invocation.
    suite_counts = {}
    suite = None
    for line in (evidence / 'scala-full.log').read_text().splitlines():
        match = re.fullmatch(r'\[info\] (\w+Spec):', line)
        if match:
            suite = match[1]
            suite_counts[suite] = 0
        elif line.startswith('[info] - '):
            suite_counts[suite] += 1
    assert len(suite_counts) == 14 and sum(suite_counts.values()) == 122
    assert 'succeeded 122, failed 0' in (evidence / 'scala-full.log').read_text()
    rtl_passes = re.findall(r'^PASS .*restart=PASS$',
                            (evidence / 'rtl-acceptance.log').read_text(), re.M)
    assert len(rtl_passes) == 12
    log = (evidence / 'validation-all.log').read_text()
    logged = re.findall(r'^PASS (\S+) seed=(\d+) cycles=(\d+) signature_words=(\d+)'
                        r'(?: benchmark_cycles=(\d+))?$', log, re.M)
    actual = {(name, int(seed), int(cycles), int(words), int(bench or 0))
              for name, seed, cycles, words, bench in logged}
    expected = {(r['name'], r['seed'], r['cycles'], r['signatureWords'],
                 r.get('benchmarkCycles', 0)) for r in raw['results']}
    assert len(logged) == 123 and actual == expected
    assert 'PASS all: 41 programs x 3 seeds' in log
    assert 'Ran 4 tests' in log and '\nOK\n' in log

    arch = [p for p in programs if not p['name'].startswith('coremark')]
    arch_results = [r for r in raw['results'] if r['name'] in
                    {p['name'] for p in arch} and r['seed'] == 1]
    metrics = dict(scalaSuites=suite_counts, scalaTests=sum(suite_counts.values()),
                   rtlProcesses=len(rtl_passes), rtlRestartPasses=2 * len(rtl_passes),
                   architecture=dict(programs=len(arch), instNVectors=sum(p['testCases'] for p in arch),
                                     fenceScenarios=1, signatureWordsPerSeed=sum(p['signatureWords'] for p in arch),
                                     runs=len(arch) * 3,
                                     totalCompletionCyclesPerSeed=sum(r['cycles'] for r in arch_results),
                                     medianCompletionCycles=statistics.median(r['cycles'] for r in arch_results)),
                   allSoftwareRuns=len(raw['results']), coremark={})
    for name in ('coremark-performance', 'coremark-validation'):
        runs = [r for r in raw['results'] if r['name'] == name]
        times = [r['benchmarkCycles'] for r in runs]
        assert len(set(times)) == 1
        cycles = times[0]
        total = runs[0]['cycles']
        metrics['coremark'][name] = dict(
            runs=len(runs), iterations=10, completionCycles=total, measuredCycles=cycles,
            cyclesPerIteration=cycles / 10, iterationsPerMillionCycles=10 * 1e6 / cycles,
            outsideMeasuredRegionCycles=total - cycles,
            outsideMeasuredRegionPercent=100 * (total - cycles) / total,
            minCycles=min(times), maxCycles=max(times), populationStddev=statistics.pstdev(times),
            projectedMilliseconds={str(mhz): cycles / (mhz * 1000) for mhz in (50, 100, 200)})
    cm = metrics['coremark']
    metrics['validationRelativeCycleIncreasePercent'] = 100 * (
        cm['coremark-validation']['measuredCycles'] / cm['coremark-performance']['measuredCycles'] - 1)
    with (HERE / 'results.csv').open('w', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=['name', 'seed', 'cycles', 'signatureWords',
                                                  'benchmarkCycles', 'cyclesPerIteration'],
                                lineterminator='\n')
        writer.writeheader()
        writer.writerows(raw['results'])
    (HERE / 'metrics.json').write_text(json.dumps(metrics, indent=2) + '\n')
    return metrics


if __name__ == '__main__':
    result = compute()
    print('PASS evidence audit: Scala 122/122; RTL 12 processes/24 restart passes; '
          'architecture 39 x 3; CoreMark 2 x 3; source inventory 4/4')
    print(json.dumps(result['coremark'], indent=2))
