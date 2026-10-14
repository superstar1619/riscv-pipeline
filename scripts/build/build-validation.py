#!/usr/bin/env python3
"""Build unchanged upstream RV32I tests/CoreMark for the Harvard pipeline platform."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
BUILD = Path(os.environ.get('VALIDATION_BUILD_DIR', str(ROOT / 'target/validation'))).resolve()
CVW = Path(os.environ.get('CVW_ROOT', ROOT.parents[2] / 'cvw'))
ARCH = CVW / 'addins/riscv-arch-test'
CORE = CVW / 'addins/coremark'
IMEM_DEPTH = 524288
DMEM_DEPTH = 32768
BASE = 0x80000000
ARCH_REVISION = 'c8c813af55ea79acb97fe832c5737cc4539af3ee'
CORE_REVISION = 'f3e8f2e0941e42961aadcc52750b1b5577c157c9'
BUILD_ERRORS = []
SOURCE_HASHES = {}
COMMAND_INDEX = 0
CORE_FILES = ('core_list_join.c', 'core_main.c', 'core_matrix.c',
              'core_state.c', 'core_util.c', 'coremark.h')
FLAGS = ['-march=rv32i', '-mabi=ilp32', '-O2', '-ffreestanding', '-fno-builtin',
         '-fno-pic', '-fno-pie', '-fno-stack-protector', '-fno-unwind-tables',
         '-fno-delete-null-pointer-checks',
         '-ffunction-sections', '-fdata-sections', '-msmall-data-limit=0',
         '-mstrict-align', '-mno-relax']


def tool(name):
    prefix = os.environ.get('RISCV_TOOL_PREFIX', 'riscv64-unknown-elf-')
    found = shutil.which(prefix + name)
    if not found:
        raise RuntimeError(f'Missing {prefix + name}')
    return found


def run(args, timeout=120):
    global COMMAND_INDEX
    BUILD.mkdir(parents=True, exist_ok=True)
    COMMAND_INDEX += 1
    argv = list(map(str, args))
    started = time.time()
    try:
        process = subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, cwd=ROOT, timeout=timeout)
        output, code = process.stdout, process.returncode
    except subprocess.TimeoutExpired as exc:
        output, code = str(exc.output or ''), 124
    log = BUILD / f'build-commands/{COMMAND_INDEX:04d}.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(output)
    with (BUILD / 'build-commands.jsonl').open('a') as stream:
        stream.write(json.dumps(dict(argv=argv, cwd=str(ROOT), exit=code,
                                    seconds=time.time()-started, log=str(log)))+'\n')
    if code:
        raise subprocess.CalledProcessError(code, argv, output=output)
    return output


def build_failure(name, exc):
    work = BUILD/name
    work.mkdir(parents=True, exist_ok=True)
    text = str(exc) + '\n' + str(getattr(exc, 'output', ''))
    (work/'build-error.log').write_text(text)
    BUILD_ERRORS.append(dict(name=name, reason=text, log=str(work/'build-error.log')))


def symbols(elf):
    return {f[2]: int(f[0], 16) for line in run([tool('nm'), elf]).splitlines()
            if len(f := line.split()) == 3}


def compile_file(source, obj, includes=(), extra=()):
    run([tool('gcc'), *FLAGS, *extra, *[f'-I{p}' for p in includes], '-c', source, '-o', obj])


def verify_source(repo, source, revision):
    if not source.is_file():
        raise ValueError(f'Missing upstream source: {source}')
    committed = subprocess.check_output(['git', '-C', str(repo), 'show',
                                         f'{revision}:{source.relative_to(repo)}'])
    assert source.read_bytes() == committed, f'Upstream source changed: {source}'
    sha = hashlib.sha256(committed).hexdigest()
    relative = source.relative_to(repo)
    copy = BUILD/'upstream'/repo.name/relative
    copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_bytes(committed)
    SOURCE_HASHES[repo.name+'/'+str(relative)] = sha
    return sha


def pinned_files(repo, directory, suffix, revision):
    paths = run(['git', '-C', repo, 'ls-tree', '-r', '--name-only', revision,
                 directory]).splitlines()
    files = [repo / path for path in paths if path.endswith(suffix)]
    if not files:
        raise ValueError(f'No pinned sources in {directory}')
    return files


def artifact_path(path):
    """Keep existing relative manifests while allowing external run directories."""
    path = Path(path).resolve()
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def build_image(name, objects, entry, extra=(), cases=0):
    work = BUILD / name
    work.mkdir(parents=True, exist_ok=True)
    start, init, elf = work / 'start.o', work / 'init.o', work / 'program.elf'
    compile_file(ROOT / 'programs/validation/start.S', start, extra=[f'-DTEST_ENTRY={entry}'])
    init_source = work / 'init.S'
    init_source.write_text('.option norvc\n.section .initdata,"ax"\n.global init_data\n'
                           'init_data:\n ret\n')
    compile_file(init_source, init)

    def link():
        run([tool('gcc'), *FLAGS, '-nostdlib', '-nostartfiles',
             '-Wl,--gc-sections,--no-relax', f'-Wl,-T,{ROOT / "programs/validation/link.ld"}',
             *extra, start, *objects, init, '-lgcc', '-o', elf])

    link()
    before = symbols(elf)
    datafile = work / 'data.bin'
    run([tool('objcopy'), '-O', 'binary', '--only-section=.data', elf, datafile])
    data = datafile.read_bytes()
    assert before['__data_start'] == BASE + 0x210000
    data += bytes((-len(data)) % 4)
    lines = ['.option norvc', '.option norelax', '.section .initdata,"ax"',
             '.global init_data', 'init_data:', ' li t0,0x80210000']
    for offset in range(0, len(data), 4):
        if offset and offset % 2048 == 0:
            lines.append(' addi t0,t0,2047')
            lines.append(' addi t0,t0,1')
        value = int.from_bytes(data[offset:offset + 4], 'little')
        lines += [f' li t1,0x{value:08x}', f' sw t1,{offset % 2048}(t0)']
    lines.append(' ret')
    init_source.write_text('\n'.join(lines) + '\n')
    compile_file(init_source, init)
    link()
    after = symbols(elf)
    run([tool('objcopy'), '-O', 'binary', '--only-section=.data', elf, work / 'data-final.bin'])
    assert (work / 'data-final.bin').read_bytes() == data[:before['__data_end'] - before['__data_start']], \
        'Data relocated when initialization code was inserted'
    assert after['__bss_end'] < BASE + 0x21c000
    assert after['end_signature'] > after['begin_signature']
    assert (after['end_signature'] - after['begin_signature']) % 4 == 0
    # Audit actual executable instruction encodings, including software libgcc helpers.
    disassembly = run([tool('objdump'), '-d', '-Mno-aliases', elf])
    (work / 'program.disassembly').write_text(disassembly)
    allowed = {'lui', 'auipc', 'jal', 'jalr', 'beq', 'bne', 'blt', 'bge', 'bltu', 'bgeu',
               'lb', 'lh', 'lw', 'lbu', 'lhu', 'sb', 'sh', 'sw', 'addi', 'slti', 'sltiu',
               'xori', 'ori', 'andi', 'slli', 'srli', 'srai', 'add', 'sub', 'sll', 'slt',
               'sltu', 'xor', 'srl', 'sra', 'or', 'and', 'fence'}
    instructions = []
    for line in disassembly.splitlines():
        m = re.match(r'\s*([0-9a-f]+):\s+([0-9a-f]+)\s+(\S+)', line)
        if m:
            assert len(m[2]) == 8, f'Non-32-bit instruction: {line}'
            assert m[3] in allowed, f'Unsupported executable instruction: {line}'
            instructions.append(m[3])
    binary = work / 'code.bin'
    run([tool('objcopy'), '-O', 'binary', '--only-section=.text',
         '--only-section=.initdata', elf, binary])
    raw = binary.read_bytes()
    assert len(raw) <= IMEM_DEPTH * 4 and len(raw) % 4 == 0
    words = [int.from_bytes(raw[i:i + 4], 'little') for i in range(0, len(raw), 4)]
    image = work / 'program.memfile'
    image.write_text(''.join(f'{v:08x}\n' for v in words + [0x13] * (IMEM_DEPTH - len(words))))
    reference = work / 'reference.signature'
    spike = shutil.which(os.environ.get('SPIKE', 'spike'))
    if not spike:
        raise RuntimeError('Missing Spike reference simulator')
    output = run([spike, '--isa=rv32i', '--pc=0x80000000', '-m0x80000000:0x220000',
                  f'+signature={reference}', '+signature-granularity=4', elf], timeout=120)
    (work / 'spike.log').write_text(output)
    expected = [int(w, 16) for w in reference.read_text().split()]
    count = (after['end_signature'] - after['begin_signature']) // 4
    assert len(expected) == count, f'Incomplete Spike signature {len(expected)}/{count}'
    if name.startswith('coremark'):
        known = ([0xe9f5, 0xe714, 0x1fd7, 0x8e3a] if name.endswith('performance') else
                 [0x18f2, 0xe3c1, 0x0747, 0x8d84])
        assert expected[:4] == known and expected[5:] == [0, 10], \
            f'CoreMark official CRC/iteration mismatch: {expected}'
    result = dict(name=name, elf=artifact_path(elf), image=artifact_path(image),
                  reference=artifact_path(reference), sigbase=f'{after["begin_signature"]:08x}',
                  signatureWords=count, cycleLimit=30000000 if name.startswith('coremark') else 1000000,
                  instructionWords=len(instructions), testCases=cases,
                  crc=[f'{v:08x}' for v in expected] if name.startswith('coremark') else None)
    (work / 'metadata.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'{name}: Spike PASS, signature_words={count}, vectors={cases}', flush=True)
    return result


def arch_tests(selected):
    env = BUILD / 'arch-env'
    env.mkdir(parents=True, exist_ok=True)
    # The unprivileged tests need no trap/CSR framework. Disable compressed alignment
    # padding in the upstream platform environment while retaining all vectors/macros.
    upstream = ARCH / 'riscv-test-suite/env'
    for source in pinned_files(ARCH, 'riscv-test-suite/env', '.h', ARCH_REVISION):
        verify_source(ARCH, source, ARCH_REVISION)
        text = source.read_text().replace('.option rvc', '.option norvc')
        destination = env / source.relative_to(upstream)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
    sources = pinned_files(ARCH, 'riscv-test-suite/rv32i_m/I/src', '.S', ARCH_REVISION)
    # Textbook Table 7.10 also lists this JALR bit-0-clearing test. Upstream
    # moved it to privilege/, but this particular case needs no traps or CSR.
    sources.append(ARCH / 'riscv-test-suite/rv32i_m/privilege/src/misalign1-jalr-01.S')
    available = {source.stem for source in sources}
    unknown = set(selected or ()) - available
    if unknown:
        raise ValueError(f'Unknown architecture tests: {sorted(unknown)}')
    for source in sources:
        verify_source(ARCH, source, ARCH_REVISION)
    results = []
    for source in sorted(sources):
        if selected and source.stem not in selected:
            continue
        work = BUILD / source.stem
        work.mkdir(parents=True, exist_ok=True)
        obj = work / 'test.o'
        try:
            compile_file(source, obj, [ROOT / 'programs/validation', env],
                         ['-DXLEN=32', '-DTEST_CASE_1', '-DFLEN=0'])
            cases = len(re.findall(r'^inst_\d+:', source.read_text(), re.M))
            results.append(build_image(source.stem, [obj], 'rvtest_entry_point', cases=cases))
        except Exception as exc:
            build_failure(source.stem, exc)
    assert results or BUILD_ERRORS, 'No selected architecture tests'
    return results


def coremark_tests():
    # coremark.md5 predates later upstream fixes. Compare the six protected files
    # with the pinned Git blobs rather than accepting that stale checksum list.
    for name in CORE_FILES:
        verify_source(CORE, CORE / name, CORE_REVISION)
    results = []
    for validation, mode in [(0, 'performance'), (1, 'validation')]:
        name = f'coremark-{mode}'
        work = BUILD / name
        work.mkdir(parents=True, exist_ok=True)
        try:
            objects = []
            sources = [CORE / f'core_{unit}.c' for unit in
                       ('main', 'list_join', 'matrix', 'state', 'util')]
            sources += [ROOT / 'programs/coremark' / f for f in ('core_portme.c', 'benchmark.c')]
            for source in sources:
                obj = work / (source.stem + '.o')
                extra = [f'-DVALIDATION_RUN={validation}']
                if source.stem == 'core_main':
                    extra.append('-Dmain=coremark_timed_main')
                compile_file(source, obj, [ROOT / 'programs/coremark', CORE], extra)
                objects.append(obj)
            results.append(build_image(name, objects, 'benchmark_main', extra=[
                '-Wl,--defsym,begin_signature=benchmark_signature',
                '-Wl,--defsym,end_signature=benchmark_signature+28']))
        except Exception as exc:
            build_failure(name, exc)
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('suite', choices=['arch', 'coremark', 'all'])
    parser.add_argument('--tests', nargs='*')
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    if args.suite in ('arch', 'all'):
        assert run(['git', '-C', ARCH, 'rev-parse', 'HEAD']).strip() == ARCH_REVISION, \
            'Architecture-test revision differs from the pinned version'
    if args.suite in ('coremark', 'all'):
        assert run(['git', '-C', CORE, 'rev-parse', 'HEAD']).strip() == CORE_REVISION, \
            'CoreMark revision differs from the pinned version'
    results = []
    if args.suite in ('arch', 'all'):
        results += arch_tests(args.tests)
    if args.suite in ('coremark', 'all'):
        results += coremark_tests()
    manifest = dict(platformVersion=2, startupSha256=hashlib.sha256((ROOT/'programs/validation/start.S').read_bytes()).hexdigest(),
                    imemDepth=IMEM_DEPTH, dmemDepth=DMEM_DEPTH, resetVector=f'0x{BASE:x}',
                    coremarkRevision=CORE_REVISION if args.suite in ('coremark', 'all') else None,
                    archRevision=ARCH_REVISION if args.suite in ('arch', 'all') else None,
                    compiler=run([tool('gcc'), '--version']).splitlines()[0],
                    compilerFlags=FLAGS,
                    coremarkSha256={name: hashlib.sha256((CORE / name).read_bytes()).hexdigest()
                                    for name in CORE_FILES}
                                    if args.suite in ('coremark', 'all') else {},
                    programs=results, buildErrors=BUILD_ERRORS, upstreamSha256=SOURCE_HASHES)
    (BUILD / f'{args.suite}.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if results:
        shutil.copyfile(ROOT / results[0]['image'], BUILD / 'program.memfile')
    else:
        (BUILD/'program.memfile').write_text('00000013\n'*IMEM_DEPTH)
    if BUILD_ERRORS:
        print(f'{len(BUILD_ERRORS)} independent builds failed; remaining programs collected; test-validation will fail', flush=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as e:
        raise SystemExit(e.output)
