"""Check device Mach-O dependency closure, including FAT framework slices."""
import argparse
import json
import posixpath
import struct
import zipfile
import plistlib

def macho(data):
    if data[:4]==b'\xca\xfe\xba\xbe':
        slices=[struct.unpack_from('>IIIII',data,8+i*20) for i in range(struct.unpack_from('>I',data,4)[0])]
        match=next((x for x in slices if x[0]==0x100000c),None)
        if match is None: raise ValueError('Framework has no ARM64 device slice')
        data=data[match[2]:match[2]+match[3]]
    if data[:4]!=b'\xcf\xfa\xed\xfe' or struct.unpack_from('<I',data,4)[0]!=0x100000c:
        raise ValueError('Not an ARM64 Mach-O')
    deps=[];paths=[];minimum=0;platform=None;offset=32
    for _ in range(struct.unpack_from('<I',data,16)[0]):
        cmd,size=struct.unpack_from('<II',data,offset)
        if size<8 or offset+size>len(data):raise ValueError('Invalid load command')
        if cmd in (12,0x8000001f,0x8000001c):
            start=struct.unpack_from('<I',data,offset+8)[0]
            value=data[offset+start:offset+size].split(b'\0')[0].decode()
            (paths if cmd==0x8000001c else deps).append(value)
        if cmd==0x32:platform,minimum=struct.unpack_from('<II',data,offset+8)
        if cmd==0x25:platform,minimum=2,struct.unpack_from('<I',data,offset+8)[0]
        offset+=size
    return {'dependencies':deps,'rpaths':paths,'platform':platform,'minimum':minimum}

def audit(file):
    errors=[]
    with zipfile.ZipFile(file) as z:
        names=set(z.namelist());plist=next(n for n in names if n.count('/')==2 and n.endswith('.app/Info.plist'))
        root=posixpath.dirname(plist);info=plistlib.loads(z.read(plist));main=root+'/'+info['CFBundleExecutable']
        binaries={main:macho(z.read(main))}
        for n in sorted(names):
            if '.framework/' in n and n.rsplit('/',1)[-1]==n.rsplit('/',2)[-2].removesuffix('.framework'):
                binaries[n]=macho(z.read(n))
        system={d for b in binaries.values() for d in b['dependencies'] if d.startswith(('/usr/lib/','/System/Library/'))}
        def expand(path,loader):
            return posixpath.normpath(path.replace('@executable_path',root).replace('@loader_path',posixpath.dirname(loader)))
        for name,binary in binaries.items():
            if binary['platform']!=2:errors.append(name+': not an iOS device binary')
            if binary['minimum']>binaries[main]['minimum']:errors.append(name+': minimum OS exceeds app minimum')
            paths=[expand(p,main) for p in binaries[main]['rpaths']]+[expand(p,name) for p in binary['rpaths']]
            for dep in binary['dependencies']:
                if dep.startswith(('/usr/lib/','/System/Library/')):continue
                candidates=[p+'/'+dep[7:] for p in paths] if dep.startswith('@rpath/') else [expand(dep,name)]
                if not any(p in names or p in system for p in candidates):errors.append(name+': unresolved '+dep)
        return {'version':info['CFBundleShortVersionString'],'binaryCount':len(binaries),'appRpaths':binaries[main]['rpaths'],'errors':errors}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('ipa');parser.add_argument('--output');args=parser.parse_args()
    report=audit(args.ipa);encoded=json.dumps(report,indent=2)
    if args.output:
        from pathlib import Path
        Path(args.output).write_text(encoded+'\n',encoding='utf-8')
    print(encoded)
    raise SystemExit(bool(report['errors']))
