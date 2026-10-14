#!/usr/bin/env python3
"""Build each production configuration once, then execute the shared case manifest."""
import csv
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
BASE_ART = ROOT / 'target/chapter8-testplan'
POINTER = BASE_ART / 'current-run.txt'
ART = ROOT / POINTER.read_text().strip() if POINTER.exists() else BASE_ART

def command(argv, log):
    started = time.time()
    with log.open('w') as stream:
        run = subprocess.run(argv, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    record = {'argv': argv, 'exit': run.returncode, 'seconds': time.time() - started,
              'log': str(log.relative_to(ROOT))}
    with (ART / 'commands.jsonl').open('a') as stream:
        stream.write(json.dumps(record) + '\n')
    if run.returncode:
        print(log.read_text()[-8000:])
        raise SystemExit(run.returncode)


def main():
    ART.mkdir(parents=True, exist_ok=True)
    sbt = os.environ.get('SBT', './scripts/sbt-local.sh')
    verilator = os.environ.get('VERILATOR', 'verilator')
    command([sbt, 'Test / runMain riscvpipeline.ExportChapter8TestPlan'], ART / 'export-rtl.log')
    rows = list(csv.DictReader((ART / 'manifest.tsv').open(), delimiter='\t'))
    configurations = {'default': ('true', 'true', 'false', 0, 0),
                      'minimal': ('false', 'false', 'false', 0, 0),
                      'sstc-hpm': ('true', 'true', 'true', 3, 0),
                      'reset100': ('true', 'true', 'false', 0, 256)}
    generation = []
    for name, (s, be, stc, hpm, reset) in configurations.items():
        directory = ART / f'rtl/{name}'
        directory.mkdir(parents=True, exist_ok=True)
        first = next(row for row in rows if row['config'] == name)
        shutil.copyfile(ROOT / first['image'], directory / 'program.hex')
        generation.append(f'runMain riscvpipeline.GeneratePrivilegedPipeline 2048 2048 '
                          f'{directory} {directory}/program.hex {reset} {s} {be} {stc} {hpm} 24')
    command([sbt, *generation], ART / 'generate-rtl.log')
    env_cache = ROOT / '.cache/ccache'
    env_cache.mkdir(parents=True, exist_ok=True)
    os.environ['CCACHE_DIR'] = str(env_cache)
    for name in configurations:
        directory = ART / f'rtl/{name}'
        options = [verilator, '-Wall', '-Wno-DECLFILENAME', '-Wno-UNUSEDSIGNAL',
                   '--top-module', 'PrivilegedPipeline']
        rtl = str(directory / 'PrivilegedPipeline.v')
        command([*options, '--lint-only', rtl], directory / 'lint.log')
        command([*options, '--cc', '--exe', '--build', '-j', '2', '--x-initial', 'unique',
                 '--x-assign', 'unique', '--prefix', 'VChapter8', '--Mdir', str(directory / 'build'),
                 '-CFLAGS', '-std=c++17', rtl, str(ROOT / 'src/test/cpp/Chapter8RtlTest.cpp')],
                directory / 'build.log')
        # Stable binary path for checker mutation tests.
        shutil.copyfile(directory / 'build/VChapter8', directory / 'VChapter8')
        (directory / 'VChapter8').chmod(0o755)
        for row in (r for r in rows if r['config'] == name):
            shutil.copyfile(ROOT / row['image'], directory / 'program.hex')
            for seed in (1, 17, 2026):
                key = f'{name}-{row["id"]}-s{seed}'
                result = ART / f'rtl-results/{key}.json'
                result.parent.mkdir(parents=True, exist_ok=True)
                command([str(directory / 'VChapter8'), row['events'], str(seed), row['limit'],
                         row['pins'], row['timeInitial'], row['timeRaised'],
                         '1' if row['delayed'] == 'true' else '0', '0', str(result)],
                        result.with_suffix('.log'))
            print(f'{name}-{row["id"]} PASS: 3 seeds × 2 reset runs', flush=True)
    command(['python3', '-m', 'unittest', 'discover', '-s', 'src/test/python',
             '-p', 'test_chapter8_checker.py', '-v'], ART / 'negative-green.log')
    print(f'{len(rows)} RTL cases passed; checker mutations rejected.')

if __name__ == '__main__':
    main()
