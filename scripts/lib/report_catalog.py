"""Register sealed run reports in target; never publish generated text into docs."""
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
LABELS = {'verification': '统一验证', 'chapter7-8-cvw': '第七、八章联合验证',
          'chapter8-testplan': '第八章详细测试', 'single-cycle': '单周期历史证据',
          'historical': '文档迁移归档'}


def _status(directory):
    try:
        summary = json.loads((directory / 'summary.json').read_text())
        passed = summary.get('passed')
        if isinstance(passed, bool):
            return 'pass' if passed else 'fail'
    except (OSError, ValueError, AttributeError):
        pass
    # Passing rows alone do not establish a complete matrix or successful exit.
    try:
        rows = json.loads((directory / 'results.json').read_text())
        failures = {'cpu_failure', 'timeout', 'build_platform_error', 'not_executed'}
        if isinstance(rows, list) and any(isinstance(row, dict) and not row.get('resolved_by') and
                (row.get('status') in failures or row.get('pass') is False) for row in rows):
            return 'fail'
    except (OSError, ValueError):
        pass
    return 'historical'


def _run_stamp(directory):
    if re.fullmatch(r'\d{8}T\d{12}Z', directory.name):
        return directory.name
    for filename, key in [('session.json', 'started_utc'), ('metadata.json', 'generated_utc')]:
        try:
            value = json.loads((directory / filename).read_text())[key]
            instant = datetime.fromisoformat(value.replace('Z', '+00:00'))
            if instant.tzinfo is not None:
                return instant.astimezone(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            pass
    return datetime.fromtimestamp((directory / 'report.md').stat().st_mtime,
                                  timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')


def _link(path, base):
    return quote(os.path.relpath(path, base), safe='/')


def _label(value):
    return str(value).replace('|', '\\|').replace('[', '\\[').replace(']', '\\]').replace('\n', ' ')


def _atomic_write(path, text):
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, encoding='utf-8', delete=False) as stream:
        name = stream.name
        stream.write(text)
    try:
        os.replace(name, path)
    finally:
        if Path(name).exists():
            Path(name).unlink()


def _render(records, index):
    lines = ['# 运行报告索引', '',
             '此文件自动生成于 target。状态来自本轮汇总或报告器校验；历史资料不推定为通过。',
             '`make clean` 会删除此索引、生成报告及运行证据。长期说明见 [文档导航](../../docs/README.md)。', '']
    for suite in sorted({row['suite'] for row in records}):
        rows = sorted((row for row in records if row['suite'] == suite),
                      key=lambda row: (row['run_utc'], row['directory']), reverse=True)
        lines += ['## ' + LABELS.get(suite, _label(suite)), '']
        for label, selected in [('最近运行', [row for row in rows if row['status'] != 'historical']),
                                ('最近通过', [row for row in rows if row['status'] == 'pass'])]:
            row = selected[0] if selected else None
            lines += [f'{label}：' + (f'[{_label(row["run_id"])}]({_link(Path(row["directory"])/"report.md", index.parent)})'
                                     if row else '无'), '']
        lines += ['| 运行 ID | 状态 | 报告 | 证据 | 重新生成 |', '| --- | --- | --- | --- | --- |']
        for row in rows:
            directory = Path(row['directory'])
            status = {'pass': 'PASS', 'fail': 'FAIL', 'historical': '历史资料 / 状态未确认'}[row['status']]
            lines += [f'| {_label(row["run_id"])} | {status} | [报告]({_link(directory/"report.md", index.parent)}) '
                      f'| [目录]({_link(directory, index.parent)}/) | `{_label(row["reproduce"])}` |']
        lines += ['']
    return '\n'.join(lines) + '\n'


def register_report(directory, suite, reproduce, *, root=ROOT, status=None):
    """Index an existing report without changing any bytes in its sealed run.

    An explicit status is used by report generators only after their existing
    acceptance checks. Unknown historical evidence never becomes a latest pass.
    """
    directory, root = Path(directory).resolve(), Path(root).resolve()
    if not (directory / 'report.md').is_file():
        raise FileNotFoundError(directory / 'report.md')
    status = _status(directory) if status is None else status
    if status not in ('pass', 'fail', 'historical'):
        raise ValueError('report status must be pass, fail, or historical')
    base = root / 'target/reports'
    base.mkdir(parents=True, exist_ok=True)
    catalog, index = base / 'catalog.json', base / 'index.md'
    stamp = _run_stamp(directory)
    record = dict(directory=str(directory), suite=suite, run_id=directory.name,
                  run_utc=stamp, status=status, reproduce=reproduce)
    # Different report entrypoints may finish concurrently. The lock and atomic
    # replacements avoid losing another suite's entry or exposing partial JSON.
    with (base / '.catalog.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        records = json.loads(catalog.read_text()) if catalog.exists() else []
        previous = next((row for row in records if
                         (row['directory'], row['suite']) == (str(directory), suite)), None)
        if previous:
            record['run_utc'] = previous['run_utc']
        records = [row for row in records if (Path(row['directory'])/'report.md').is_file()
                   and (row['directory'], row['suite']) != (str(directory), suite)]
        records.append(record)
        records.sort(key=lambda row: (row['suite'], row['run_utc'], row['directory']))
        _atomic_write(catalog, json.dumps(records, ensure_ascii=False, indent=2) + '\n')
        _atomic_write(index, _render(records, index))
    return index
