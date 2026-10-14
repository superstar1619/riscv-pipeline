#!/usr/bin/env python3
"""Run all independent RV32 programs even after a failure; always persist results."""
import json
import os
from pathlib import Path
import re
import shlex
import sys
from cvw_common import ROOT, Collector, dump, reproduction, fresh_reproduction

BUILD = Path(os.environ.get('VALIDATION_BUILD_DIR', str(ROOT / 'target/validation'))).resolve()


def main():
    suite = sys.argv[1]
    manifest = json.loads((BUILD / f'{suite}.json').read_text())
    seeds = [int(s) for s in os.environ.get('VALIDATION_SEEDS', '1 17 2026').split()]
    if not seeds:
        raise ValueError('No random seeds selected')
    executable = BUILD / 'rtl/VRiscvPipeline'
    collector = Collector(BUILD)
    results = [dict(chapter=7, suite='coremark' if p['name'].startswith('coremark') else 'arch',
                    name=p['name'],seed=seed,status='not_executed',reason=p['reason'],log=p['log'],
                    reproduce=fresh_reproduction('validation'))
               for p in manifest.get('buildErrors', []) for seed in seeds]
    negative_checks = []
    if not manifest['programs']:
        dump(BUILD/f'{suite}-results.json',dict(manifest=manifest,seeds=seeds,results=results,negativeChecks=[]))
        return 1
    for index, program in enumerate(manifest['programs']):
        image = (ROOT / program['image']).resolve()
        if image.name != 'program.memfile':
            raise ValueError('Validation image must be named program.memfile')
        case_directory = image.parent
        def simulate(key, reference, seed, limit, actual):
            env = dict(os.environ, SIGNATURE_OUTPUT=str(actual))
            argv = [executable, reference, program['sigbase'], str(seed), str(limit), program['name']]
            return collector.command(key, argv, timeout=600, env=env, cwd=case_directory), argv
        if index == 0:
            words = (ROOT / program['reference']).read_text().split()
            words[0] = f'{int(words[0], 16) ^ 1:08x}'
            poison = BUILD / 'poison.signature'
            poison.write_text('\n'.join(words) + '\n')
            for reason, reference, limit, message in [
                ('signature', poison, program['cycleLimit'], 'Signature word 0 differs'),
                ('timeout', ROOT / program['reference'], 1, 'Cycle limit reached')]:
                check, _ = simulate('negative-'+reason, reference, seeds[0], limit, BUILD/('negative-'+reason+'.actual'))
                accepted = check['exit'] != 0 and message in Path(check['log']).read_text()
                negative_checks.append(dict(name=reason, status='pass' if accepted else 'build_platform_error', **check))
        for seed in seeds:
            actual = BUILD/program['name']/f'actual-seed-{seed}.signature'
            check, argv = simulate(program['name']+f'/rtl-seed-{seed}', ROOT / program['reference'], seed, program['cycleLimit'], actual)
            output = Path(check['log']).read_text()
            match = re.fullmatch(r'PASS (\S+) seed=(\d+) cycles=(\d+) signature_words=(\d+)'
                                 r'(?: benchmark_cycles=(\d+))?\s*', output)
            valid = (check['exit'] == 0 and match and match[1] == program['name'] and
                     int(match[2]) == seed and int(match[4]) == program['signatureWords'])
            status = 'pass' if valid else ('timeout' if check['exit'] == 124 or 'Cycle limit reached' in output
                       else 'cpu_failure' if check['exit'] == 1 else 'build_platform_error')
            result = dict(chapter=7, suite='coremark' if program['name'].startswith('coremark') else 'arch',
                          name=program['name'], seed=seed, status=status, reason='' if valid else output[-2000:],
                          signatureWords=program['signatureWords'], log=check['log'],
                          exit=check['exit'],
                          referenceSignature=str((ROOT / program['reference']).resolve()),
                          storeSignature=str(actual.resolve()),
                          cpuSignature=str(actual.resolve()) + '.cpu',
                          command=list(map(str, argv)), cwd=str(case_directory),
                          reproduce='cd ' + shlex.quote(str(case_directory)) + ' && ' + shlex.join(list(map(str, argv))))
            if match:
                result['cycles'] = int(match[3])
                if match[5]:
                    result['benchmarkCycles'] = int(match[5])
                    result['cyclesPerIteration'] = int(match[5]) / 10
            results.append(result)
            report = dict(manifest=manifest, seeds=seeds, negativeChecks=negative_checks, results=results)
            dump(BUILD / f'{suite}-results.json', report)
    passed = all(r['status'] == 'pass' for r in results + negative_checks)
    print(f'{"PASS" if passed else "FAIL"} {suite}: {len(manifest["programs"])} programs x {len(seeds)} seeds', flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
