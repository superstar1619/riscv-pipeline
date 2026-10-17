#!/usr/bin/env python3
"""Collect Chapter 9 RTL traces; reject missing matrix, CPU LW or bus evidence."""
import argparse
import copy
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import shutil
import hashlib
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / 'target/cache/python')
os.environ['PYTHONPYCACHEPREFIX'] = sys.pycache_prefix
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'lib'))
from cvw_common import ROOT, Collector, dump, save_results, reproduction, manifest, process_exit_results

CASES = ('memory-switch','apb-subword','privilege-endian','interrupt-precise')

def required_runs(cases=CASES, seeds=(1,17,2026)):
    return [dict(chapter=9,suite='soc-rtl',name=case,mode=mode,ramLatency=latency,
                 config=f'{mode}-ram{latency}',resetVector=0 if mode=='mixed' else 0x1000,
                 seed=seed,repeat=repeat)
            for mode in ('bus-only','mixed') for latency in (0,1,2) for case in cases
            for seed in seeds for repeat in (0,1)]

def coverage_points(events):
    points = set()
    fetched_ram = False
    for e in events:
        if e['kind']=='bus':
            k=e['event']
            if k==1: fetched_ram=True; points.add('fetch.ram')
            if k==0 and fetched_ram: points.add('fetch.return-rom')
            if k==5 and e['mask'] in (2,12): points.add('apb.masked')
            if k==4 and e['address']==0x10000004 and e['readData']==3: points.add('apb.count')
        elif e['kind']=='sample':
            if e['bus_wait']: points.add('bus.wait')
            if e['ifu_wait'] and e['lsu_wait']: points.add('bus.lsu-ifu')
        elif e['kind']=='irq' and e['asserted'] and (e['committed_m'] or e['committed_f']): points.add('interrupt.bus-committed')
        elif e['kind']=='trap' and e['cause']==3 and e['interrupt']: points.add('interrupt.machine-software')
        elif e['kind']=='trap' and e['cause']==11 and not e['interrupt']: points.add('trap.ecall')
        elif e['kind']=='commit':
            ins=e['instruction']
            if fetched_ram and e['pc'] < 0x2000: points.add('fetch.return-rom')
            if ins & 0x7f == 3 and (ins>>12)&7 in (0,1,4,5): points.add('memory.subword')
            if ins & 0x7f == 3 and e.get('rd') == 28 and e.get('data')==0x44332211: points.add('endian.big')
        elif e['kind']=='store' and e['address']==0x800: points.add('memory.local-dtim')
    wait_cycles=sorted({e['cycle'] for e in events if e['kind']=='sample' and e['bus_wait']})
    longest=run=0;previous=None
    for cycle in wait_cycles:
        run=run+1 if previous is not None and cycle==previous+1 else 1
        longest=max(longest,run);previous=cycle
    for bound in (1,2):
        if longest>=bound: points.add(f'bus.wait.length{bound}')
    if not wait_cycles and any(e['kind']=='bus' and e['event'] in (1,2,3) for e in events): points.add('bus.zero-wait')
    return points

def configured_oracle(base,ram_latency):
    oracle=copy.deepcopy(base);oracle['ramLatency']=ram_latency
    required=oracle.get('requiredCoverage',[])
    if 'bus.wait' in required:
        if ram_latency==0: oracle['requiredCoverage']=['bus.zero-wait' if b=='bus.wait' else b for b in required]
        else: oracle['requiredCoverage']=required+[f'bus.wait.length{ram_latency}']
    return oracle

def check_irq_causality(oracle,events):
    expected=sum(bool(e['interrupt']) for e in oracle.get('expectedTraps',[]))
    transitions=[e for e in events if e['kind']=='irq']
    if not expected:
        if transitions: raise ValueError('unexpected IRQ pin transitions')
        return
    if expected!=1: raise ValueError('unsupported IRQ schedule cardinality')
    high=False; rise=None; accepted=None; rises=0; falls=0
    for event in events:
        if event['kind']=='irq':
            if event['asserted']:
                rises+=1
                if high or accepted is not None or rises!=1: raise ValueError('extra/late IRQ rise')
                if not (event['committed_m'] or event['committed_f']): raise ValueError('IRQ rise lacks actual committed phase')
                high=True;rise=event['cycle']
            else:
                falls+=1
                if not high or accepted is None or not event.get('afterAcceptedTrapEdge',False):
                    raise ValueError('IRQ fall before accepted trap edge')
                high=False
        elif event['kind']=='trap' and event['interrupt']:
            if not high or rise is None or event['cycle']<=rise or not event['mswint']:
                raise ValueError('interrupt accepted before actual high pin')
            if event['committed_m'] or event['committed_f']:
                raise ValueError('interrupt accepted during committed transaction')
            if accepted is not None: raise ValueError('duplicate interrupt acceptance')
            accepted=event['cycle']
    if rises!=1 or falls!=1 or accepted is None or high: raise ValueError('missing IRQ rise/acceptance/fall')

def check_trace(oracle, events):
    """Strict ordered stores + actual retired LW, never infer reads from shadow RAM."""
    try:
        if not events: raise ValueError('empty trace')
        if sum(e['kind']=='end' for e in events)!=1: raise ValueError('missing/duplicate reset-end event')
        cycles=[e['cycle'] for e in events]
        if cycles != sorted(cycles): raise ValueError('trace cycle order')
        stores=[e for e in events if e['kind']=='store']
        expected=list(oracle['stores'])
        loads=[e for e in events if e['kind']=='commit' and e['pc']==oracle['readbackPC']]
        if len(loads)!=len(oracle['words']): raise ValueError('missing/extra actual CPU LW readback')
        for index,(load,word) in enumerate(zip(loads,oracle['words'])):
            if (load['instruction'] & 0x707f)!=0x2003 or load['rd']!=10 or load['data']!=word:
                raise ValueError(f'CPU LW readback mismatch index {index}')
        for i,word in enumerate(oracle['words']):
            expected.extend([[0x8000ff20,i,15],[0x8000ff24,word,15]])
        expected.extend([[0x8000ff28,len(oracle['words']),15],[0x8000ff00,0x5a,15]])
        actual=[[e['address'],e['data'],e['mask']] for e in stores]
        for i,(want,got) in enumerate(zip(expected,actual)):
            if want!=got: raise ValueError(f'ordered store mismatch index {i}: expected {want}, got {got}')
        if len(actual)!=len(expected): raise ValueError(f'missing/extra store: {len(actual)} != {len(expected)}')
        done=stores[-1]['cycle']
        if events[-1]['kind']!='end' or events[-1]['cycle']<done+8: raise ValueError('incomplete eight-cycle observation window')
        # Readback must precede its value store, not merely appear somewhere in trace.
        protocol=[e for e in stores if e['address']==0x8000ff24]
        for load,value in zip(loads,protocol):
            if load['cycle']>value['cycle']: raise ValueError('CPU LW after reported value')
        if 'expectedTraps' in oracle:
            traps=[{k:e[k] for k in ('cause','interrupt','pc') if k in want} for e,want in zip([e for e in events if e['kind']=='trap'],oracle['expectedTraps'])]
            if len([e for e in events if e['kind']=='trap'])!=len(oracle['expectedTraps']) or traps!=oracle['expectedTraps']:
                raise ValueError('ordered trap events differ')
        check_irq_causality(oracle,events)
        points=coverage_points(events)
        missing=set(oracle.get('requiredCoverage',[]))-points
        if missing: raise ValueError('missing dynamic coverage: '+', '.join(sorted(missing)))
        # Count all physical writes separately; it must match the accepted CPU store stream.
        if oracle.get('requirePhysical',False):
            physical=[e for e in events if e['kind']=='bus' and e['event'] in (3,5)]
            expected_physical=list(oracle['physicalStores'])
            # Indexed readback/length/completion are ordinary little-endian word stores.
            for address,data,mask in expected[len(oracle['stores']):]:
                expected_physical.append(dict(address=address,mask=mask,bytes=list(data.to_bytes(4,'little'))))
            if len(physical)!=len(expected_physical): raise ValueError('missing/extra physical bus writes')
            for index,(want,got) in enumerate(zip(expected_physical,physical)):
                if want['address']!=got['address'] or want['mask']!=got['mask']:
                    raise ValueError(f'physical bus address/mask mismatch {index}')
                for lane,byte in enumerate(want['bytes']):
                    if byte is not None and ((got['data']>>(lane*8))&255)!=byte:
                        raise ValueError(f'physical bus byte mismatch {index} lane{lane}')
        return dict(**{'pass':True},failure='',cycles=events[-1]['cycle'],coverage=sorted(points),
                    words=[e['data'] for e in loads],stores=actual)
    except (ValueError,KeyError,TypeError,IndexError) as exc:
        return dict(**{'pass':False},failure=str(exc),coverage=[])

def read_trace(path):
    events=[]
    try:
        for line_number,line in enumerate(Path(path).read_text().splitlines(),1):
            if not line.strip(): continue
            try:
                event=json.loads(line)
                if not isinstance(event,dict) or event.get('repeat') not in (0,1) or not isinstance(event.get('cycle'),int) or event.get('kind') not in ('commit','trap','bus','store','sample','irq','end'):
                    raise ValueError('malformed event metadata')
                events.append(event)
            except (ValueError,TypeError) as exc: return events,f'trace line {line_number}: {exc}'
        return events,''
    except OSError as exc: return events,str(exc)

def load_module(path,name):
    spec=importlib.util.spec_from_file_location(name,path)
    value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value

def negative_checks(oracle,events,directory):
    """Mutate a genuinely passing RTL trace, preserving each rejected artifact."""
    directory=Path(directory); directory.mkdir(parents=True,exist_ok=False)
    baseline=check_trace(oracle,events)
    if not baseline['pass']:
        dump(directory/'results.json',dict(passed=False,reason='baseline is not a passing real trace',checks=[]))
        return False
    first_store=next(i for i,e in enumerate(events) if e['kind']=='store')
    readback=next(i for i,e in enumerate(events) if e['kind']=='commit' and e['pc']==oracle['readbackPC'])
    variants={}
    bad=copy.deepcopy(events); bad[first_store]['data']^=1; variants['middle-store']=bad
    bad=copy.deepcopy(events); bad.pop(first_store); variants['missing-store']=bad
    bad=copy.deepcopy(events); bad.insert(first_store,copy.deepcopy(bad[first_store])); variants['duplicate-store']=bad
    bad=copy.deepcopy(events); bad[readback]['data']^=1; variants['actual-lw']=bad
    bad=copy.deepcopy(events); bad.pop(readback); variants['missing-lw']=bad
    bad=copy.deepcopy(events); bad[-1]['cycle']-=1; variants['short-window']=bad
    bad=copy.deepcopy(events); bad.insert(-1,dict(kind='store',cycle=bad[-1]['cycle'],address=0x80004000,data=0,mask=15)); variants['late-store']=bad
    checks=[]
    for name,bad in variants.items():
        checked=check_trace(oracle,bad)
        dump(directory/(name+'.json'),dict(events=bad,check=checked))
        checks.append(dict(name=name,passed=not checked['pass'],reason=checked['failure']))
    passed=all(c['passed'] for c in checks)
    dump(directory/'results.json',dict(passed=passed,checks=checks))
    return passed

def negative_irq_checks(oracle,events,directory):
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=False)
    if not check_trace(oracle,events)['pass']:
        dump(directory/'results.json',dict(passed=False,checks=[],reason='IRQ baseline is not a passing actual trace'));return False
    variants={}
    def changed(name,action):
        bad=copy.deepcopy(events);action(bad);variants[name]=sorted(bad,key=lambda e:e['cycle'])
    def rise(rows): return next(e for e in rows if e['kind']=='irq' and e['asserted'])
    def fall(rows): return next(e for e in rows if e['kind']=='irq' and not e['asserted'])
    def trap(rows): return next(e for e in rows if e['kind']=='trap' and e['interrupt'])
    changed('trap-before-rise',lambda rows:trap(rows).update(cycle=max(0,rise(rows)['cycle']-1)))
    changed('rise-after-trap',lambda rows:rise(rows).update(cycle=trap(rows)['cycle']+1))
    changed('missing-rise',lambda rows:rows.remove(rise(rows)))
    changed('missing-fall',lambda rows:rows.remove(fall(rows)))
    changed('premature-fall',lambda rows:fall(rows).update(cycle=max(0,trap(rows)['cycle']-1)))
    changed('fall-before-edge',lambda rows:fall(rows).update(afterAcceptedTrapEdge=False))
    changed('trap-pin-low',lambda rows:trap(rows).update(mswint=False))
    changed('trap-committed',lambda rows:trap(rows).update(committed_m=1))
    checks=[]
    for name,bad in variants.items():
        checked=check_trace(oracle,bad);dump(directory/(name+'.json'),dict(events=bad,check=checked))
        checks.append(dict(name=name,passed=not checked['pass'],reason=checked['failure']))
    passed=all(c['passed'] for c in checks);dump(directory/'results.json',dict(passed=passed,checks=checks));return passed

def spike_reference(output, program, collector):
    """Relocate instruction-only RV32I ELF around Spike's immutable reset ROM.

    No instruction bytes change. Normalize only ROM PCs and link-register writes
    from JALR; every instruction and all data-memory addresses remain exact.
    Mixed local DTIM overlaps Spike's debug module and is explicitly unsupported.
    """
    if program['id']!='memory-switch' or program['mode']!='bus-only':
        return dict(status='not_executed',reason='MMIO/privileged reference uses explicit events; mixed local DTIM overlaps Spike debug region')
    work=Path(program['directory'])/'spike'; work.mkdir()
    tool=os.environ.get('RISCV_TOOL_PREFIX','riscv64-unknown-elf-')
    relocated=work/'program.elf'
    record=collector.command('spike/relocate',[tool+'objcopy','--change-section-address','.text=0x80002000',
          '--set-start=0x80002000',program['elf'],relocated])
    if record['exit']: return dict(status='build_platform_error',reason='Spike relocation failed')
    # Prove that relocation preserved every instruction byte.
    record=collector.command('spike/image',[tool+'objcopy','-O','binary','--only-section=.text',relocated,work/'program.bin'])
    if record['exit'] or (work/'program.bin').read_bytes()!=Path(program['directory'],'program.bin').read_bytes():
        return dict(status='build_platform_error',reason='Spike relocation changed instruction bytes')
    record=collector.command('spike/reference',[os.environ.get('SPIKE','spike'),'--log-commits',
        '--isa=rv32i_zicsr_zifencei','--pc=0x80002000','--instructions=1024','-m0x80000000:0x10000',relocated])
    if record['exit']: return dict(status='build_platform_error',reason='Spike execution failed',log=record['log'])
    from commit_diff import spike_records
    try:
        lines=Path(record['log']).read_text().splitlines()
        lines=[l for l in lines if not l.startswith('warning: tohost and fromhost symbols not in ELF')]
        commits=[]; done=False; signature={}
        for item in spike_records(lines,0x80002000):
            pc=item['pc']; value=item['value']
            if 0x80002000<=pc<0x80003000: pc=pc-0x80002000+program['resetVector']
            if item['instruction']&0x7f in (0x67,0x6f) and item['rd']:
                if value != (item['pc']+4)&0xffffffff:
                    raise ValueError('Spike JAL/JALR link is not raw PC+4')
                if 0x80002000 <= item['pc'] < 0x80003000:
                    value=value-0x80002000+program['resetVector']
            commits.append(dict(pc=pc,instruction=item['instruction'],rd=item['rd'],data=value))
            mem=item['mem']
            if mem and mem['kind']=='store' and 0x80008000<=mem['address']<0x80008080:
                signature[mem['address']]=mem['data']
            if mem and mem['kind']=='store' and mem['address']==0x8000ff00 and mem.get('data')==0x5a:
                done=True;break
        if not done: raise ValueError('Spike completion absent')
        oracle=json.loads(Path(program['oracle']).read_text())
        words=[signature[a] for a in sorted(signature)]
        if words!=oracle['words']: raise ValueError('Spike signature differs from hand-derived oracle')
        reference=dict(status='pass',origin='Spike --log-commits; instruction-byte-identical .text relocation',
            originalEntry=program['resetVector'],referenceEntry=0x80002000,
            relocation='ROM PCs and instruction-derived JAL/JALR PC+4 links normalized only',
            instructionSHA256=hashlib.sha256((work/'program.bin').read_bytes()).hexdigest(),
            originalELFSHA256=hashlib.sha256(Path(program['elf']).read_bytes()).hexdigest(),
            referenceELFSHA256=hashlib.sha256(relocated.read_bytes()).hexdigest(),commits=commits,
            words=words,log=record['log'])
        dump(work/'reference.json',reference)
        return reference
    except Exception as exc: return dict(status='build_platform_error',reason=str(exc),log=record['log'])

def compare_spike(reference,events):
    if reference.get('status')!='pass': return dict(passed=False,reason=reference.get('reason','Spike reference absent'))
    expected=reference['commits']; actual=[e for e in events if e['kind']=='commit']
    # Stop at the independently known completion instruction, never resynchronize.
    if len(actual)<len(expected): return dict(passed=False,reason='truncated RTL commit sequence')
    for index,(want,got) in enumerate(zip(expected,actual)):
        if got['instruction']&0x7f in (0x67,0x6f) and got['rd'] and got['data'] != (got['pc']+4)&0xffffffff:
            return dict(passed=False,reason=f'commit {index} DUT JAL/JALR link is not raw PC+4',actual=got)
        for field in ('pc','instruction','rd','data'):
            if want[field]!=got[field]:
                return dict(passed=False,reason=f'commit {index} {field} mismatch',expected=want,actual=got)
    return dict(passed=True,compared=len(expected),reason='')

def run(output, cases=CASES, seeds=(1,17,2026), build_only=False):
    output=Path(output).resolve(); output.mkdir(parents=True,exist_ok=False)
    os.environ.setdefault('CCACHE_DIR',str(ROOT/'target/cache/ccache'))
    collector=Collector(output)
    owned=[*sorted((ROOT/'programs/chapter9').glob('*')),
           ROOT/'scripts/build/build-chapter9.py',ROOT/'scripts/run/test-chapter9-rtl.py',
           ROOT/'scripts/report/report-chapter9.py',ROOT/'src/test/cpp/chapter9_rtl.cpp',
           *sorted((ROOT/'src/test/python').glob('test_chapter9*.py')),ROOT/'build.sbt',
           ROOT/'scripts/lib/cvw_common.py',ROOT/'scripts/lib/commit_diff.py',ROOT/'scripts/lib/report_catalog.py',
           *sorted((ROOT/'src/main/scala').rglob('*.scala')),
           *sorted((ROOT/'src/test/scala').rglob('*.scala'))]
    hashes={}
    for source in owned:
        if source.is_file():
            relative=source.relative_to(ROOT); dest=output/'source'/relative
            dest.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(source,dest)
            hashes[str(relative)]=hashlib.sha256(source.read_bytes()).hexdigest()
    dump(output/'source-before.json',hashes)
    programs=load_module(ROOT/'scripts/build/build-chapter9.py','chapter9_builder').build(output,cases)
    matrix=required_runs(cases,seeds); dump(output/'manifest.json',dict(schemaVersion=1,cases=programs,requiredRuns=matrix))
    results=[]
    references={p['mode']+'/'+p['id']:spike_reference(output,p,collector) for p in programs if p['status']=='built'}
    dump(output/'spike-references.json',references)
    collector.command('metadata/python',[sys.executable,'--version'])
    collector.command('metadata/verilator',[os.environ.get('VERILATOR','verilator'),'--version'])
    collector.command('metadata/git-head',['git','rev-parse','HEAD'])
    collector.command('metadata/git-status',['git','status','--short'])
    collector.command('metadata/spike',[os.environ.get('SPIKE','spike'),'--help'])
    for mode in ('bus-only','mixed'):
        for latency in (0,1,2):
            for p in [p for p in programs if p['mode']==mode]:
                work=output/'rtl'/f'{mode}-ram{latency}'/p['id']; work.mkdir(parents=True)
                error='build-only: SoC RTL not executed' if build_only else p.get('reason','')
                if p['status']!='built': error='program build failed: '+error
                if not error:
                    image=work/'program.hex'; shutil.copyfile(p['image'],image)
                    sbt=os.environ.get('SBT','./scripts/sbt-local.sh')
                    record=collector.command(f'rtl/{mode}-ram{latency}/{p["id"]}/generate',
                        ['flock',ROOT/'target/chapter9-sbt.lock',sbt,
                         f'Test / runMain riscvpipeline.GenerateChapter9Observer {work} {image} {"mixed" if mode=="mixed" else "bus"} {latency} 1'])
                    if record['exit']: error='observer RTL generation failed'
                if not error:
                    rtl=work/'Chapter9Observer.v'
                    record=collector.command(f'rtl/{mode}-ram{latency}/{p["id"]}/compile',
                        [os.environ.get('VERILATOR','verilator'),'-Wall','-Wno-DECLFILENAME','-Wno-UNUSEDSIGNAL',
                         '--assert','--coverage-line','--coverage-toggle','--cc','--exe','--build','-j','2','--top-module','Chapter9Observer','--prefix','VChapter9',
                         '--x-initial','unique','--x-assign','unique','--Mdir',work/'build','-CFLAGS','-std=c++17',
                         rtl,ROOT/'src/test/cpp/chapter9_rtl.cpp'])
                    if record['exit']: error='Verilator compilation failed'
                for seed in seeds:
                    trace=work/f'trace-s{seed}.jsonl'
                    record=None
                    if not error:
                        run_env=dict(os.environ,COVERAGE_OUTPUT=str(work/f'coverage-s{seed}.dat'))
                        record=collector.command(f'rtl/{mode}-ram{latency}/{p["id"]}/seed{seed}',
                            [work/'build/VChapter9',str(seed),str(p['cycleLimit']),trace,'1' if p['id']=='interrupt-precise' else '0'],cwd=work,timeout=180,env=run_env)
                    events,trace_error=read_trace(trace) if not error else ([],error)
                    for repeat in (0,1):
                        row=next(dict(r) for r in matrix if r['mode']==mode and r['ramLatency']==latency and r['name']==p['id'] and r['seed']==seed and r['repeat']==repeat)
                        oracle=json.loads(Path(p['oracle']).read_text()) if p.get('oracle') else {}
                        oracle=configured_oracle(oracle,latency)
                        oracle['requirePhysical']=True
                        actual=[e for e in events if e['repeat']==repeat]
                        checked=check_trace(oracle,actual) if not error else {'pass':False,'failure':error}
                        if trace_error:
                            checked.update({'pass':False,'failure':'trace parse: '+trace_error})
                        differential=compare_spike(references.get(mode+'/'+p['id'],{}),actual) if mode=='bus-only' and p['id']=='memory-switch' and not error else None
                        if differential is not None and not differential['passed']:
                            checked.update({'pass':False,'failure':'Spike: '+differential['reason']})
                        dump(work/f'actual-s{seed}-r{repeat}.json',dict(events=actual,check=checked,differential=differential))
                        if checked['pass'] and p['id']=='memory-switch' and mode=='bus-only' and not (output/'negative-checks').exists():
                            negative_checks(oracle,actual,output/'negative-checks')
                            changed=copy.deepcopy(actual)
                            transient=next(e for e in changed if e['kind']=='commit' and e['rd'] and e['pc']!=oracle['readbackPC'])
                            transient['data']^=1
                            dump(output/'negative-spike.json',dict(events=changed,signatureCheck=check_trace(oracle,changed),
                                differential=compare_spike(references[mode+'/'+p['id']],changed)))
                        if checked['pass'] and p['id']=='interrupt-precise' and mode=='bus-only' and not (output/'negative-irq').exists():
                            negative_irq_checks(oracle,actual,output/'negative-irq')
                        timed_out=record and (record['exit']==124 or (actual and actual[-1]['cycle']>=p['cycleLimit']))
                        row.update(status='pass' if checked['pass'] else 'not_executed' if error.startswith('build-only:') else 'timeout' if timed_out else 'build_platform_error' if error or trace_error else 'cpu_failure',
                                   reason=checked['failure'],trace=str(trace),evidence=str(work/f'actual-s{seed}-r{repeat}.json'),
                                   exit=record['exit'] if record else None,
                                   reproduce=reproduction([sys.executable,ROOT/'scripts/run/test-chapter9-rtl.py']))
                        results.append(row)
                    if record:
                        results[-2:]=process_exit_results(results[-2:],record['exit'])
                save_results(output,results)
    dump(output/'summary.json',dict(passed=all(r['status']=='pass' for r in results),runs=len(results),
                                   statuses={s:sum(r['status']==s for r in results) for s in sorted({r['status'] for r in results})}))
    if not build_only:
        collect_code_coverage(output,results,collector)
    collector.command('python-platform',[sys.executable,'-m','unittest','discover','-s','src/test/python','-p','test_chapter9*.py','-v'])
    current_paths=set(owned)|set((ROOT/'src/main/scala').rglob('*.scala'))|set((ROOT/'src/test/scala').rglob('*.scala'))|set((ROOT/'src/test/python').glob('test_chapter9*.py'))
    current={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in current_paths if p.is_file()}
    dump(output/'source-after.json',current)
    dump(output/'source-stability.json',dict(passed=current==hashes,changed=sorted(k for k in set(current)|set(hashes) if current.get(k)!=hashes.get(k))))
    accepted=load_module(ROOT/'scripts/report/report-chapter9.py','chapter9_report').report(output)
    manifest(output)
    return accepted

def collect_code_coverage(output,results,collector):
    summaries=[]
    for config in sorted({r['config'] for r in results}):
        files=[]
        for evidence in sorted({Path(r['evidence']).parent for r in results if r['config']==config}):
            for seed in (1,17,2026):
                matches=[r for r in results if Path(r['evidence']).parent==evidence and r['seed']==seed]
                path=evidence/f'coverage-s{seed}.dat'
                if len(matches)==2 and all(r['status']=='pass' for r in matches) and path.exists(): files.append(path)
        directory=output/'coverage'/config;directory.mkdir(parents=True,exist_ok=True)
        row=dict(config=config,passingRunsOnly=True,files=list(map(str,files)),status='not_executed')
        if files:
            merged=directory/'coverage.dat'; info=directory/'coverage.info'
            merge=collector.command('coverage/'+config+'-merge',['verilator_coverage','--write',merged,*files])
            lcov=collector.command('coverage/'+config+'-info',['verilator_coverage','--write-info',info,merged]) if not merge['exit'] else merge
            if not merge['exit'] and not lcov['exit']:
                counts=[int(l.split(',')[1]) for l in info.read_text().splitlines() if l.startswith('DA:')]
                toggles=[int(l.rsplit(' ',1)[1]) for l in merged.read_text().splitlines() if 'v_toggle' in l]
                row.update(status='pass',linePoints=len(counts),hitLinePoints=sum(c>0 for c in counts),
                           togglePoints=len(toggles),hitTogglePoints=sum(c>0 for c in toggles),exclusions=[])
            else: row.update(status='build_platform_error',reason='coverage merge failed')
        dump(directory/'summary.json',row);summaries.append(row)
    dump(output/'code-coverage.json',summaries)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--build-only',action='store_true')
    parser.add_argument('--plan',action='store_true')
    parser.add_argument('--cases',nargs='+',choices=CASES,default=list(CASES))
    parser.add_argument('--seeds',nargs='+',type=int,default=[1,17,2026])
    args=parser.parse_args()
    if args.plan: print(json.dumps(required_runs(args.cases,args.seeds),indent=2)); return 0
    output=args.output or ROOT/'target/chapter9-testplan/runs'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    ok=run(output,args.cases,args.seeds,args.build_only)
    print(f'{"PASS" if ok else "INCOMPLETE/FAIL"}: {output}')
    return 0 if ok else 1
if __name__=='__main__': sys.exit(main())
