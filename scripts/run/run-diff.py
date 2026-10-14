#!/usr/bin/env python3
"""Offline RV32I commit differential using one simulator per configuration/options."""
import argparse
from datetime import datetime, timezone
import importlib.util
import hashlib
import subprocess
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import shlex
import shutil
from cvw_common import ROOT, Collector, dump, manifest as hashes
from commit_diff import compare_files
from diff_sensitivity import exercise
from verification_coverage import collect, required_bins


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--validation-dir', type=Path)
    parser.add_argument('--profile', choices=['smoke','full'],default='smoke')
    args=parser.parse_args()
    directory=(args.output or ROOT/'target/verification-diff'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')).resolve()
    directory.mkdir(parents=True,exist_ok=False)
    collector=Collector(directory); rows=[]; programs=[]; successful_cov=[]; merged={}
    sensitivity_done=False
    seeds=[1] if args.profile=='smoke' else [1,17,2026]
    env=dict(os.environ)
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=4 -Xmx3g'
    env['CCACHE_DIR']=str(ROOT/'target/cache/ccache')
    sbt=env.get('SBT','./scripts/sbt-local.sh'); verilator=env.get('VERILATOR','verilator')
    def command(key,argv,**kw): return collector.command(key,argv,env=env,**kw)
    def check(record):
        if record['exit']: raise RuntimeError(f'{record["key"]}: exit {record["exit"]}; {record["log"]}')
    try:
        from verify import sources
        before=sources()
        dump(directory/'source-before.json',before)
        for name,argv in [('verilator',[verilator,'--version']),('spike',[env.get('SPIKE','spike'),'--help'])]:
            command('metadata/'+name,argv)
        check(command('sensitivity',[sys.executable,'-m','unittest','discover','-s','src/test/python','-p','test_commit_diff.py','-v']))
        rows.append(dict(chapter=7,suite='diff-negative',name='mutations',status='pass',log=str(directory/'sensitivity.log')))
        validation=args.validation_dir
        if validation:
            manifests=[validation/'all.json',validation/'arch.json']
            manifest=next((p for p in manifests if p.exists()),None)
            if not manifest: raise RuntimeError('reused validation manifest missing')
        else:
            validation=directory/'validation'
            buildenv=dict(env,VALIDATION_BUILD_DIR=str(validation))
            argv=[sys.executable,ROOT/'scripts/build/build-validation.py','arch']
            if args.profile=='smoke': argv+=['--tests','addi-01','jalr-01','lw-align-01']
            check(collector.command('build-architecture',argv,env=buildenv))
            manifest=validation/'arch.json'
        data=json.loads(manifest.read_text())
        if data.get('platformVersion') != 2 or data.get('startupSha256') != hashlib.sha256((ROOT/'programs/validation/start.S').read_bytes()).hexdigest():
            raise RuntimeError('reused ELF platform is not the current deterministic initialization/readback platform')
        programs=[p for p in data['programs'] if not p['name'].startswith('coremark')]
        expected=3 if args.profile=='smoke' else 39
        if len(programs)!=expected: raise RuntimeError(f'architecture inventory {len(programs)} != required {expected}')
        for e in data.get('buildErrors',[]):
            if not e['name'].startswith('coremark'): raise RuntimeError(f'architecture build failed: {e}')
        spec=importlib.util.spec_from_file_location('diff_builder',ROOT/'scripts/build/build-validation.py')
        builder=importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
        expected_names=({'addi-01','jalr-01','lw-align-01'} if args.profile=='smoke' else
            {Path(p).stem for p in subprocess.check_output(['git','-C',str(builder.ARCH),'ls-tree','-r','--name-only',
             builder.ARCH_REVISION,'riscv-test-suite/rv32i_m/I/src'],text=True).splitlines() if p.endswith('.S')} | {'misalign1-jalr-01'})
        if {p['name'] for p in programs} != expected_names:
            raise RuntimeError('required architecture IDs missing or replaced')
        if data.get('archRevision') != builder.ARCH_REVISION:
            raise RuntimeError('architecture reference revision differs')
        builder.BUILD=directory/'directed'; work=builder.BUILD/'dependencies'; work.mkdir(parents=True)
        obj=work/'test.o'
        builder.compile_file(ROOT/'programs/verification/dependencies.S',obj)
        programs.append(builder.build_image('dependencies',[obj],'verification_entry'))
        for p in programs:
            elf=ROOT/p.get('elf',str(Path(p['image']).with_name('program.elf')))
            p['elf']=str(elf)
        dump(directory/'manifest.json',dict(profile=args.profile,seeds=seeds,repeats=2,programs=programs,
             config='rv32i-2MiB-128KiB',check='strict-commit-diff',completion='word store 0x8021ff00=1; observe eight cycles'))
        # Relative ROM name: every process has an immutable per-case working directory.
        rtl=directory/'rtl'
        check(command('generate',[sbt,f'Test / runMain riscvpipeline.GenerateVerificationObserver {rtl} program.memfile']))
        build=directory/'simulator'
        check(command('compile',[verilator,'--cc','--exe','--build','-j','2','--assert',
            '--coverage-line','--coverage-toggle','--x-initial','unique','--x-assign','unique',
            '-Wno-fatal','--top-module','VerificationObserver','--Mdir',build,
            '-CFLAGS','-std=c++17',rtl/'VerificationObserver.v',ROOT/'src/test/cpp/CommitRtlTest.cpp']))
        for p in programs:
            work=directory/'cases'/p['name'];work.mkdir(parents=True)
            shutil.copyfile(ROOT/p['image'],work/'program.memfile')
            shutil.copyfile(p['elf'],work/'program.elf')
            # Keep the expected signature alongside both execution traces.
            shutil.copyfile(ROOT/p['reference'],work/'reference.signature')
            spike=command('cases/'+p['name']+'/spike',[env.get('SPIKE','spike'),'--log-commits',
                '--isa=rv32i','--pc=0x80000000','-m0x80000000:0x220000',work/'program.elf'],timeout=120)
            nm=command('cases/'+p['name']+'/symbols',[builder.tool('nm'),'-n',work/'program.elf'])
            check(nm)
            for seed in seeds:
                out=work/f'seed-{seed}';out.mkdir()
                argv=[build/'VVerificationObserver',str(seed),str(p['cycleLimit']),out]
                record=command(f'cases/{p["name"]}/seed-{seed}/simulator',argv,cwd=work,timeout=300)
                for repeat in range(2):
                    prefix=out/f'reset-{repeat}'
                    trace=prefix.with_suffix('.jsonl');events=prefix.with_suffix('.events.jsonl')
                    diff=prefix.with_suffix('.diff.json')
                    row=dict(chapter=7,suite='diff',name=p['name'],config='rv32i',seed=seed,repeat=repeat,
                             status='not_executed',exit=record['exit'],log=record['log'],
                             reproduce='cd '+shlex.quote(str(work))+' && '+shlex.join(list(map(str,argv))))
                    if trace.exists() and spike['exit']==0:
                        comparison=compare_files(spike['log'],trace,diff,elf_symbols=nm['log'])
                        row.update(status='pass' if comparison['passed'] and record['exit']==0 else 'cpu_failure',
                                   reason=comparison['reason'],compared=comparison['compared'],diff=str(diff))
                        cov=collect(trace,events)
                        dump(prefix.with_suffix('.functional.json'),dict(passed=row['status']=='pass',bins=cov))
                        if row['status']=='pass':
                            if not sensitivity_done:
                                negatives=exercise(spike['log'],trace,directory/'real-sensitivity',symbols=nm['log'])
                                rows.extend(dict(chapter=7,suite='diff-real-sensitivity',**item) for item in negatives)
                                sensitivity_done=True
                            for key,value in cov.items():
                                if key not in merged: merged[key]=dict(count=0,first=dict(case=p['name'],seed=seed,repeat=repeat,**value['first']))
                                merged[key]['count']+=value['count']
                            successful_cov.append(prefix.with_suffix('.coverage.dat'))
                    else: row['reason']='simulator/reference failed before this reset; inspect raw command log'
                    rows.append(row);dump(directory/'results.json',rows)
        missing=sorted(set(required_bins())-set(merged))
        dump(directory/'functional-coverage.json',dict(bins=merged,required=required_bins(),missing=missing,
            sampling='commits for architectural bins; actual E advance for forwarding; actual hazard for stalls',
            mapping={key:dict(book='§7.2.2 pp.321–324; §7.4 pp.329–332',stimulus='dependencies / RV32I arch',
                             checker='commit_diff + VerificationObserver assertions') for key in required_bins()}))
        rows.append(dict(chapter=7,suite='functional-coverage',name='required-bins',status='cpu_failure' if missing else 'pass',reason=', '.join(missing)))
        if successful_cov:
            check(command('coverage-merge',['verilator_coverage','--write',directory/'coverage.dat',*successful_cov]))
            check(command('coverage-lcov',['verilator_coverage','--write-info',directory/'coverage.info',directory/'coverage.dat']))
            lines=(directory/'coverage.info').read_text().splitlines()
            counts=[int(line.split(',')[1]) for line in lines if line.startswith('DA:')]
            raw=(directory/'coverage.dat').read_text().splitlines()
            toggles=[int(line.rsplit(' ',1)[1]) for line in raw if 'v_toggle' in line]
            dump(directory/'code-coverage.json',dict(configuration='rv32i observer + production parity instance',
                 verilatorOptions=['--coverage-line','--coverage-toggle'],linePoints=len(counts),
                 hitLinePoints=sum(c>0 for c in counts),togglePoints=len(toggles),hitTogglePoints=sum(c>0 for c in toggles),
                 unhitPolicy='Retain all unhit points in coverage.dat/info; no exclusions applied. Privileged RTL absent in this configuration.',
                 passingRunsOnly=True))
    except Exception as exc:
        rows.append(dict(chapter=7,suite='diff-platform',name='runner',status='build_platform_error',reason=str(exc)))
    finally:
        # A shared compile/parse failure must not silently remove later required resets.
        observed={(r.get('name'),r.get('seed'),r.get('repeat')) for r in rows if r.get('suite')=='diff'}
        for program in programs:
            for seed in seeds:
                for repeat in range(2):
                    if (program['name'],seed,repeat) not in observed:
                        rows.append(dict(chapter=7,suite='diff',name=program['name'],config='rv32i',
                            seed=seed,repeat=repeat,status='not_executed',reason='required reset not executed after shared runner failure; see command logs'))
        if 'before' in locals():
            after=sources();dump(directory/'source-after.json',after)
            if before!=after: rows.append(dict(chapter=7,suite='integrity',name='source-stability',status='build_platform_error',reason='sources changed during differential run'))
        dump(directory/'results.json',rows)
        hashes(directory)
        print('DIFF REPORT '+str(directory/'results.json'),flush=True)
    return int(not rows or any(r['status']!='pass' for r in rows))

if __name__=='__main__': sys.exit(main())
