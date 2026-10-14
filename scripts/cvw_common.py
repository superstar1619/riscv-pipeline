"""Shared bounded command collection and independent evidence validation."""
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def fresh_run():
    path = ROOT / 'target/chapter7-8-cvw/runs' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    path.mkdir(parents=True, exist_ok=False)
    return path


def two_runs_pass(result):
    runs = result.get('runs', [])
    return len(runs) == 2 and all(r.get('pass') is True and r.get('repeat') == i
                                 for i, r in enumerate(runs))



def process_exit_results(rows, exit_code):
    """Keep partial reset successes, reject an all-pass report from a failed process."""
    result = [dict(row, exit=exit_code) for row in rows]
    if exit_code != 0 and result and all(row['status'] == 'pass' for row in result):
        for row in result:
            row.update(evidenceStatus=row['status'], status='build_platform_error',
                       reason=f'passing reset evidence contradicts simulator exit {exit_code}')
    return result

def validate_readback(expected, length, actual):
    if actual.get('words') != expected:
        raise ValueError('wrong/missing/extra signature words')
    if actual.get('length') != length:
        raise ValueError('wrong signature length')
    if actual.get('guard') != 0xcafef00d or actual.get('canary') != 0:
        raise ValueError('signature sentinel corrupted')
    if actual.get('done') is not True:
        raise ValueError('missing completion marker')
    if actual.get('postCycles') != 8 or actual.get('postStores') != 0:
        raise ValueError('incomplete observation window or side effect')


class Collector:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.commands = []

    def command(self, key, argv, timeout=3600, env=None, cwd=None):
        log = self.directory / (key + '.log')
        log.parent.mkdir(parents=True, exist_ok=True)
        started = time.time()
        error = ''
        with log.open('w') as stream:
            try:
                proc = subprocess.Popen(list(map(str, argv)), cwd=cwd or ROOT, stdout=stream,
                                        stderr=subprocess.STDOUT, env=env, start_new_session=True)
                try:
                    code = proc.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    import signal
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait()
                    code, error = 124, 'host timeout'
            except OSError as exc:
                code, error = 127, str(exc)
                stream.write(error + '\n')
        record = dict(key=key, argv=list(map(str, argv)), cwd=str(cwd or ROOT), exit=code, error=error,
                      startedUTC=datetime.fromtimestamp(started, timezone.utc).isoformat(),
                      seconds=time.time() - started, log=str(log))
        self.commands.append(record)
        with (self.directory / 'commands.jsonl').open('a') as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + '\n')
        print(f'{key}: exit={code} ({record["seconds"]:.1f}s)', flush=True)
        return record


def save_results(directory, rows):
    dump(directory / 'results.json', rows)
    fields = ['chapter', 'suite', 'name', 'config', 'seed', 'repeat', 'status', 'reason',
              'cycles', 'benchmarkCycles', 'log', 'reproduce']
    with (directory / 'results.csv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def manifest(directory):
    lines = []
    for path in sorted(directory.rglob('*')):
        if path.is_file() and path.name != 'SHA256SUMS':
            lines.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(directory)}\n')
    (directory / 'SHA256SUMS').write_text(''.join(lines))


def reproduction(argv):
    return 'cd ' + shlex.quote(str(ROOT)) + ' && ' + shlex.join(list(map(str, argv)))


def read_evidence(path):
    try:
        result = json.loads(Path(path).read_text())
        if not isinstance(result, dict) or not isinstance(result.get('runs'), list):
            raise ValueError('expected object containing runs')
        for row in result['runs']:
            if (not isinstance(row, dict) or row.get('repeat') not in (0, 1) or
                    not isinstance(row.get('pass'), bool) or not isinstance(row.get('cycles'), int) or
                    not isinstance(row.get('failure', ''), str)):
                raise ValueError('malformed reset-run record')
        return result
    except (OSError, ValueError) as exc:
        return dict(runs=[], evidenceError=str(exc))


def reconcile(rows, expected, reason):
    keys = ('chapter', 'suite', 'name', 'config', 'seed', 'repeat')
    def key(row):
        return tuple(row.get(field) for field in keys)
    result = list(rows)
    for case in expected:
        found = [row for row in result if key(row) == key(case)]
        if not found:
            result.append(dict(case, status='not_executed', reason=reason))
        elif len(found) != 1:
            for row in found:
                row.update(status='build_platform_error', reason='duplicate matrix evidence')
    return result


def fresh_reproduction(suite, env=None):
    import sys
    env = os.environ if env is None else env
    settings = [k + '=' + env[k] for k in ('CVW_ROOT', 'SBT', 'VERILATOR', 'SPIKE', 'RISCV_TOOL_PREFIX') if k in env]
    return reproduction(['env', *settings, sys.executable, ROOT/'scripts/reproduce-chapter7-8.py', suite])
