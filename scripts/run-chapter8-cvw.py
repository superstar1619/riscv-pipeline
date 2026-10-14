#!/usr/bin/env python3
"""Fresh native CVW cases on both production PrivilegedPipeline configurations."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
from cvw_common import ROOT, Collector, dump, fresh_run, reproduction, save_results, validate_readback, read_evidence, reconcile, fresh_reproduction, process_exit_results


def run(directory):
    directory = Path(directory).resolve()
    seeds = [int(s) for s in os.environ.get('CVW_SEEDS', '1 17 2026').split()]
    if not seeds or 1 not in seeds:
        raise ValueError('CVW seeds must include seed 1 for checker sensitivity baseline')
    collector = Collector(directory)
    result = collector.command('build-programs', [sys.executable, ROOT/'scripts/build-chapter8-cvw.py', '--output', directory/'programs'])
    manifest_path = directory/'programs/manifest.json'
    if not manifest_path.exists():
        rows = [dict(chapter=8, suite='cvw', name='upstream-inventory', status='build_platform_error',
                     reason='shared upstream/build failure', log=result['log'])]
        import runpy
        inventory = runpy.run_path(str(ROOT/'scripts/build-chapter8-cvw.py'))
        for config in ('default','sstc-hpm'):
            for name in inventory['APPLICABLE']:
                base=dict(chapter=8,suite='cvw',name='WALLY-'+name+'-01',config=config)
                if name=='trap' and config=='default':
                    rows.append(dict(base,status='not_applicable',reason='explicit Sstc requirement'))
                else:
                    rows += [dict(base,seed=seed,repeat=repeat,status='not_executed',reason='shared upstream/build failure; '+result['log'])
                             for seed in seeds for repeat in (0,1)]
            rows += [dict(chapter=8,suite='cvw-checker-negative',name=name,config=config,status='not_executed',reason='shared build failure')
                     for name in ('signature','missing','extra','length','completion','observation-window')]
        rows += [dict(chapter=8,suite='cvw',name='WALLY-'+name+'-01',config='all',status='not_applicable',reason=reason)
                 for name,reason in inventory['EXCLUDED'].items()]
        save_results(directory, rows)
        return rows
    programs = json.loads(manifest_path.read_text())
    rows = []
    sbt = os.environ.get('SBT', './scripts/sbt-local.sh')
    verilator = os.environ.get('VERILATOR', 'verilator')
    os.environ['CCACHE_DIR'] = str(ROOT/'.cache/ccache')
    for config, stc, hpm in [('default', 'false', 0), ('sstc-hpm', 'true', 3)]:
        rtl = directory/'rtl'/config
        rtl.mkdir(parents=True, exist_ok=True)
        available = [p for p in programs if p['config'] == config and p['status'] == 'built']
        shared_error = ''
        if available:
            shutil.copyfile(Path(available[0]['directory'])/'program.hex', rtl/'program.hex')
            generation = (f'runMain riscvpipeline.GeneratePrivilegedPipeline 16384 16384 {rtl} '
                          f'program.hex 0 true true {stc} {hpm} 65536')
            record = collector.command(f'{config}-generate', [sbt, generation])
            if record['exit']:
                shared_error = 'RTL generation failed: ' + record['log']
            else:
                record = collector.command(f'{config}-verilate', [verilator, '--cc', '--exe', '--build', '-j', '2',
                       '-Wall', '-Wno-DECLFILENAME', '-Wno-UNUSEDSIGNAL', '--x-initial', 'unique', '--x-assign', 'unique',
                       '--top-module', 'PrivilegedPipeline', '--prefix', 'VCvw', '--Mdir', rtl/'build',
                       '-CFLAGS', '-std=c++17', rtl/'PrivilegedPipeline.v', ROOT/'src/test/cpp/CvwPrivilegedRtlTest.cpp'])
                if record['exit']:
                    shared_error = 'Verilator build failed: ' + record['log']
        for program in [p for p in programs if p['config'] == config]:
            base = dict(chapter=8, suite='cvw', name=program['name'], config=config)
            if program['status'] == 'not_applicable':
                rows.append(dict(base, status='not_applicable', reason=program['reason']))
                continue
            for seed in seeds:
                if program['status'] != 'built' or shared_error:
                    for repeat in (0, 1):
                        rows.append(dict(base, seed=seed, repeat=repeat, status='not_executed',
                                         reason=shared_error or program['reason']))
                    continue
                work = Path(program['directory'])
                shutil.copyfile(work/'program.hex', rtl/'program.hex')
                output = work/f'seed-{seed}.json'
                argv = [rtl/'build/VCvw', work/'reference.signature', str(program['signatureWords']*4),
                        str(seed), '1000000', output]
                record = collector.command(f'{config}/{program["name"]}/seed-{seed}', argv, timeout=180, cwd=work)
                evidence = read_evidence(output)
                expected = [int(w, 16) for w in (work/'reference.signature').read_text().split()]
                first_result = len(rows)
                for repeat in (0, 1):
                    runs = [r for r in evidence.get('runs', []) if r.get('repeat') == repeat]
                    status, reason, cycles = ('timeout' if record['exit'] == 124 else 'build_platform_error'), evidence.get('evidenceError', 'missing simulator evidence'), None
                    if len(runs) == 1:
                        actual = runs[0]
                        cycles = actual.get('cycles')
                        reason = actual.get('failure', 'malformed simulator evidence')
                        status = 'timeout' if 'timeout' in reason else 'cpu_failure' if reason else 'pass'
                        try:
                            validate_readback(expected, program['signatureWords']*4, actual)
                            if not actual.get('pass') or record['exit'] not in (0, 1):
                                status, reason = 'build_platform_error', 'inconsistent simulator exit/evidence'
                        except ValueError as exc:
                            if status == 'pass':
                                status, reason = 'cpu_failure', str(exc)
                        (work/f'actual-seed-{seed}-repeat-{repeat}.signature').write_text(
                            ''.join(f'{v:08x}\n' for v in actual.get('words', [])))
                    rows.append(dict(base, seed=seed, repeat=repeat, status=status, reason=reason,
                                     cycles=cycles, log=record['log'], reproduce=fresh_reproduction('cvw')))
                rows[first_result:] = process_exit_results(rows[first_result:], record['exit'])
                save_results(directory, rows)
        # Exercise the actual binary's rejection paths using the simple MISA test.
        good = next((p for p in available if p['name'] == 'WALLY-misa-01'), None)
        if good and not shared_error:
            work = Path(good['directory'])
            original = (work/'program.hex').read_text()
            expected = (work/'reference.signature').read_text().split()
            valid = read_evidence(work/'seed-1.json')
            done_cycles = [r['cycles']-8 for r in valid['runs'] if r.get('pass') and 'cycles' in r]
            variants = {'signature': (expected.copy(), good['signatureWords']*4, 1000000),
                        'missing': (expected[:-1], good['signatureWords']*4, 1000000),
                        'extra': (expected+['00000000'], good['signatureWords']*4, 1000000),
                        'length': (expected, good['signatureWords']*4+4, 1000000),
                        'completion': (expected, good['signatureWords']*4, 1)}
            variants['signature'][0][0] = f'{int(expected[0],16)^1:08x}'
            if not done_cycles:
                rows.append(dict(chapter=8, suite='cvw-checker-negative', name='observation-window', config=config,
                                 status='not_executed', reason='no successful baseline completion for observation mutation'))
            if done_cycles:
                variants['observation-window'] = (expected, good['signatureWords']*4, done_cycles[0])
            for name, (words, length, limit) in variants.items():
                negative = directory/'negative'/config
                negative.mkdir(parents=True, exist_ok=True)
                signature = negative/(name+'.signature')
                signature.write_text('\n'.join(words)+'\n')
                (rtl/'program.hex').write_text(original)
                output = negative/(name+'.json')
                record = collector.command(f'negative/{config}/{name}', [rtl/'build/VCvw', signature,
                             str(length), '1', str(limit), output], timeout=180, cwd=rtl)
                expected_error = {'signature': 'signature mismatch', 'missing': 'invalid reference length',
                                  'extra': 'invalid reference length', 'length': 'wrong signature length',
                                  'completion': 'missing completion', 'observation-window': 'observation'}[name]
                ok = record['exit'] in (1, 2) and expected_error in Path(record['log']).read_text()
                rows.append(dict(chapter=8, suite='cvw-checker-negative', name=name, config=config,
                                 status='pass' if ok else 'build_platform_error', reason='' if ok else 'checker mutation not rejected as expected', log=record['log']))
    coverage = json.loads((directory/'programs/coverage.json').read_text())
    rows += [dict(chapter=8, suite='cvw', name=r['name'], config='all', status='not_applicable',
                  reason=r['reason']) for r in coverage if not r['applicable']]
    expected = []
    for program in programs:
        if program['status'] != 'not_applicable':
            expected += [dict(chapter=8,suite='cvw',name=program['name'],config=program['config'],seed=s,repeat=r)
                         for s in seeds for r in (0,1)]
    rows = reconcile(rows, expected, 'missing case evidence after shared/case failure')
    save_results(directory, rows)
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    rows = run(args.output or fresh_run()/'chapter8-cvw')
    sys.exit(int(any(r['status'] not in ('pass', 'not_applicable') for r in rows)))
