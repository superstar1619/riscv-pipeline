#!/usr/bin/env python3
"""Build production configurations and collect every independent RTL case."""
import csv
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import shutil
from cvw_common import ROOT, Collector, reproduction, save_results, read_evidence, fresh_reproduction, dump, process_exit_results

BASE_ART = ROOT / 'target/chapter8-testplan'
POINTER = BASE_ART / 'current-run.txt'
ART = (Path(os.environ['CHAPTER8_RUN_DIR']) if os.environ.get('CHAPTER8_RUN_DIR') else (ROOT / POINTER.read_text().strip() if POINTER.exists() else BASE_ART)).resolve()


def main():
    ART.mkdir(parents=True, exist_ok=True)
    collector = Collector(ART)
    sbt = os.environ.get('SBT', './scripts/sbt-local.sh')
    verilator = os.environ.get('VERILATOR', 'verilator')
    record = collector.command('export-rtl', [sbt, 'Test / runMain riscvpipeline.ExportChapter8TestPlan'])
    if record['exit']:
        save_results(ART, [dict(chapter=8, suite='existing-rtl', name='export', status='build_platform_error', reason='shared manifest export failure', log=record['log'])])
        return 1
    with (ART / 'manifest.tsv').open() as stream:
        manifest = list(csv.DictReader(stream, delimiter='\t'))
    seeds = [int(s) for s in os.environ.get('CHAPTER8_SEEDS', '1 17 2026').split()]
    if not seeds:
        raise ValueError('No chapter8 seeds selected')
    selected_configs = os.environ.get('CHAPTER8_CONFIGS', '').split()
    selected_ids = os.environ.get('CHAPTER8_CASE_IDS', '').split()
    configurations = {'default': ('true', 'true', 'false', 0, 0),
                      'minimal': ('false', 'false', 'false', 0, 0),
                      'sstc-hpm': ('true', 'true', 'true', 3, 0),
                      'reset100': ('true', 'true', 'false', 0, 256)}
    if selected_configs and set(selected_configs) - configurations.keys():
        raise ValueError('Unknown chapter8 configuration filter')
    if selected_ids and set(selected_ids) - {r['id'] for r in manifest}:
        raise ValueError('Unknown chapter8 case ID filter')
    results = []
    coverage_enabled = os.environ.get('CHAPTER8_COVERAGE') == '1'
    os.environ['CCACHE_DIR'] = str(ROOT / 'target/cache/ccache')
    for name, (s, be, stc, hpm, reset) in configurations.items():
        if selected_configs and name not in selected_configs:
            continue
        passing_coverage = []
        directory = ART / f'rtl/{name}'
        directory.mkdir(parents=True, exist_ok=True)
        selected = [row for row in manifest if row['config'] == name and (not selected_ids or row['id'] in selected_ids)]
        if not selected:
            continue
        shutil.copyfile(ROOT / selected[0]['image'], directory / 'program.hex')
        generation = (f'runMain riscvpipeline.generators.GeneratePrivilegedPipeline 2048 2048 '
                      f'{directory} program.hex {reset} {s} {be} {stc} {hpm} 24')
        record = collector.command(f'{name}-generate', [sbt, generation])
        error = 'shared RTL generation failure' if record['exit'] else ''
        options = [verilator, '-Wall', '-Wno-DECLFILENAME', '-Wno-UNUSEDSIGNAL', '--top-module', 'PrivilegedPipeline']
        rtl = str(directory / 'PrivilegedPipeline.v')
        if not error:
            record = collector.command(f'{name}-lint', [*options, '--lint-only', rtl])
            error = 'RTL lint failure' if record['exit'] else ''
        if not error:
            record = collector.command(f'{name}-build', [*options, *(['--coverage-line', '--coverage-toggle'] if coverage_enabled else []), '--cc', '--exe', '--build', '-j', '2',
                     '--x-initial', 'unique', '--x-assign', 'unique', '--prefix', 'VChapter8',
                     '--Mdir', str(directory / 'build'), '-CFLAGS', '-std=c++17', rtl,
                     str(ROOT / 'src/test/cpp/Chapter8RtlTest.cpp')])
            error = 'shared Verilator build failure' if record['exit'] else ''
        if not error:
            shutil.copyfile(directory / 'build/VChapter8', directory / 'VChapter8')
            (directory / 'VChapter8').chmod(0o755)
        for row in selected:
            work = ART / 'case-runs' / name / row['id']
            work.mkdir(parents=True, exist_ok=True)
            if not error:
                shutil.copyfile(ROOT / row['image'], work / 'program.hex')
            for seed in seeds:
                key = f'{name}-{row["id"]}-s{seed}'
                result = ART / f'rtl-results/{key}.json'
                result.parent.mkdir(parents=True, exist_ok=True)
                argv = [str(directory/'VChapter8'), str(ROOT / row['events']), str(seed), row['limit'], row['pins'],
                        row['timeInitial'], row['timeRaised'], '1' if row['delayed'] == 'true' else '0', '0', str(result)]
                coverage_file = ART / f'coverage/{name}/{key}.dat'
                coverage_file.parent.mkdir(parents=True, exist_ok=True)
                if not error:
                    run_env = dict(os.environ)
                    if coverage_enabled:
                        run_env['COVERAGE_OUTPUT'] = str(coverage_file)
                    record = collector.command('rtl-results/'+key, argv, timeout=180, env=run_env, cwd=work)
                evidence = read_evidence(result)
                first_result = len(results)
                for repeat in (0, 1):
                    actual = next((r for r in evidence.get('runs', []) if r['repeat'] == repeat), None)
                    reason = error or (evidence.get('evidenceError', 'previous reset replay failed') if not actual else actual.get('failure', ''))
                    status = ('not_executed' if error else 'timeout' if not actual and record['exit'] == 124 else 'build_platform_error' if not actual and evidence.get('evidenceError') else 'not_executed' if not actual else 'pass' if actual['pass'] else
                              'timeout' if 'timeout' in reason else 'cpu_failure')
                    results.append(dict(chapter=8, suite='existing-rtl', name=row['id'], config=name,
                                        seed=seed, repeat=repeat, status=status, reason=reason,
                                        cycles=actual.get('cycles') if actual else None, log=record['log'],
                                        reproduce=fresh_reproduction('existing-rtl')))
                results[first_result:] = process_exit_results(results[first_result:], record['exit'])
                if coverage_enabled and not error and record['exit'] == 0 and all(r['status'] == 'pass' for r in results[-2:]):
                    if coverage_file.exists():
                        passing_coverage.append(coverage_file)
                    else:
                        results.append(dict(chapter=8, suite='code-coverage', name=key, config=name,
                            status='not_executed', reason='passing simulator did not write required coverage'))
        if coverage_enabled:
            coverage_dir = ART/'coverage'/name
            coverage_dir.mkdir(parents=True, exist_ok=True)
            if passing_coverage:
                merged = coverage_dir/'coverage.dat'
                info = coverage_dir/'coverage.info'
                merge = collector.command(f'{name}-coverage-merge', ['verilator_coverage','--write',merged,*passing_coverage])
                lcov = collector.command(f'{name}-coverage-info', ['verilator_coverage','--write-info',info,merged]) if not merge['exit'] else merge
                ok = not merge['exit'] and not lcov['exit']
                if ok:
                    counts = [int(line.split(',')[1]) for line in info.read_text().splitlines() if line.startswith('DA:')]
                    toggles = [int(line.rsplit(' ',1)[1]) for line in merged.read_text().splitlines() if 'v_toggle' in line]
                    dump(coverage_dir/'code-coverage.json', dict(configuration=name, passingRunsOnly=True,
                        includedSimulatorRuns=len(passing_coverage), resetRunsPerSimulator=2,
                        linePoints=len(counts), hitLinePoints=sum(c>0 for c in counts),
                        togglePoints=len(toggles), hitTogglePoints=sum(c>0 for c in toggles),
                        exclusions=[], gaps='All unhit line/toggle points retained in coverage.dat/info; no exclusions applied.'))
                results.append(dict(chapter=8, suite='code-coverage', name='line-toggle', config=name,
                    status='pass' if ok else 'build_platform_error', reason='' if ok else 'coverage merge failed', log=lcov['log']))
            else:
                results.append(dict(chapter=8, suite='code-coverage', name='line-toggle', config=name,
                    status='not_executed', reason='no fully passing simulator coverage to merge'))
        save_results(ART, results)
    record = collector.command('negative-green', [sys.executable, '-m', 'unittest', 'discover', '-s', 'src/test/python', '-p', 'test_chapter8_checker.py', '-v'])
    results.append(dict(chapter=8, suite='existing-checker-negative', name='mutations', status='pass' if record['exit'] == 0 else 'build_platform_error', log=record['log']))
    save_results(ART, results)
    return int(any(r['status'] != 'pass' for r in results))


if __name__ == '__main__':
    sys.exit(main())
