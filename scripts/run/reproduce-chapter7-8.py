#!/usr/bin/env python3
"""Replay into a fresh directory, preserving previous results and historical pointers."""
import argparse
import os
import subprocess
import sys
from pathlib import Path
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
from cvw_common import ROOT, fresh_run


def prepare(suite, directory, env):
    env = dict(env)
    env.setdefault('SBT','./scripts/sbt-local.sh')
    env.setdefault('VERILATOR','verilator')
    env['JAVA_TOOL_OPTIONS'] = env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=4 -Xmx3g'
    env['CHAPTER8_RUN_DIR'] = str(directory/'chapter8-existing')
    env.pop('CHAPTER8_CASE_FILTER',None)
    if suite == 'cvw':
        argv = [sys.executable, ROOT/'scripts/run/run-chapter8-cvw.py','--output',directory/'chapter8-cvw']
    elif suite == 'existing-rtl':
        argv = [sys.executable, ROOT/'scripts/run/test-chapter8-rtl.py']
    elif suite == 'validation':
        argv = ['make','test-validation','VALIDATION_SUITE=all','SBT='+env['SBT'],
                'VERILATOR='+env['VERILATOR'],'VALIDATION_BUILD_DIR='+str(directory/'validation'),
                'VALIDATION_RTL_DIR='+str(directory/'validation/rtl-source')]
    elif suite == 'scala':
        argv = [env['SBT'], f'set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", "{directory}/junit")','test']
    else:
        argv = [sys.executable, ROOT/'scripts/run/test-chapter7-8-cvw.py']
    return argv, env


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('suite',choices=['joint','cvw','existing-rtl','validation','scala'])
    args = parser.parse_args()
    directory = fresh_run()
    print('Fresh reproduction directory: '+str(directory),flush=True)
    argv, env = prepare(args.suite,directory,os.environ)
    sys.exit(subprocess.run(list(map(str,argv)),cwd=ROOT,env=env).returncode)
