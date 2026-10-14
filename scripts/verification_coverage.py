"""Event-derived functional coverage; never infer a hit from program source text."""
import json


def category(ins):
    return {3:'load',35:'store',99:'branch',103:'jalr',111:'jal',55:'lui',23:'auipc',
            19:'alu',51:'alu',15:'fence',115:'system'}.get(ins&127,'unknown')


def sources(ins):
    op=ins&127
    return ([('A',(ins>>15)&31)] if op in (3,19,103,51,35,99) else []) + \
           ([('B',(ins>>20)&31)] if op in (51,35,99) else [])


def collect(commits, events):
    bins={}
    def hit(key, evidence):
        if key not in bins: bins[key]=dict(count=0,first=evidence)
        bins[key]['count']+=1
    last_write={}
    with open(commits) as stream:
        for line in stream:
            row=json.loads(line); ins=row['instruction']; kind=category(ins)
            proof={k:row[k] for k in ('cycle','pc','order')}
            hit('instruction.'+kind,proof)
            if kind=='branch':
                hit('branch.'+('taken' if row['branchTaken'] else 'not_taken'),proof)
            for side,rs in sources(ins):
                if rs and rs in last_write:
                    gap=row['order']-last_write[rs]-1
                    if gap<=2: hit(f'dependency.{kind}.{side}.gap{gap}',proof)
            if row['rd']: last_write[row['rd']]=row['order']
            mem=row.get('mem')
            if mem: hit(f'memory.{mem["kind"]}.{mem["width"]}.offset{mem["address"]%4}',proof)
    with open(events) as stream:
        for line in stream:
            row=json.loads(line); proof={'cycle':row['cycle']}
            if row['loadStall']: hit('stall.load_use',proof)
            if row['storeStall']: hit('stall.store_load',proof)
            if not row['advance']: continue
            ins=row['instruction']; kind=category(ins)
            if row['redirect']: hit('redirect.execute',proof)
            for side,rs in sources(ins):
                sel=row['forward'+side]
                if sel: hit(f'forward.{kind}.{side}.{ {1:"W",2:"M"}[sel]}',proof)
                if not rs: hit('forward.x0.none' if sel==0 else 'forward.x0.ERROR',proof)
                if (rs and row.get('validM') and row.get('validW') and row.get('writeM') and
                    row.get('writeW') and not row.get('lateM') and rs==row.get('rdM')==row.get('rdW')):
                    hit('forward.priority.M' if sel==2 else 'forward.priority.ERROR',proof)
    return bins


def required_bins():
    bins=['stall.load_use','stall.store_load','forward.priority.M','forward.x0.none',
          'branch.taken','branch.not_taken','redirect.execute']
    for kind,side in [('alu','A'),('branch','A'),('jalr','A'),('store','A'),('store','B')]:
        bins += [f'dependency.{kind}.{side}.gap{gap}' for gap in range(3)]
        bins += [f'forward.{kind}.{side}.{stage}' for stage in ('M','W')]
    for kind in ('load','store'):
        for width in (1,2,4):
            bins += [f'memory.{kind}.{width}.offset{offset}' for offset in range(0,4,width)]
    return bins
