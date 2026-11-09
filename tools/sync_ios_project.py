"""Register native sources/resources and reproducible SDK tests in the existing project."""
import hashlib
import json
import plistlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'OmniDownloader-iOS'
PROJECT = ROOT / 'OmniDownloader.xcodeproj/project.pbxproj'


def read_pbx(text):
    text = re.sub(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*', lambda m: m[0] if m[0].startswith('"') else '', text, flags=re.S)
    tokens = iter(re.findall(r'"(?:\\.|[^"\\])*"|[{}()=;,]|[^\s{}()=;,]+', text))
    stream = list(tokens)
    pos = 0
    def value():
        nonlocal pos
        token = stream[pos]; pos += 1
        if token == '{':
            result = {}
            while stream[pos] != '}':
                key = value(); assert stream[pos] == '='; pos += 1
                result[key] = value(); assert stream[pos] == ';'; pos += 1
            pos += 1
            return result
        if token == '(':
            result = []
            while stream[pos] != ')':
                result.append(value())
                if stream[pos] == ',': pos += 1
            pos += 1
            return result
        return json.loads(token) if token.startswith('"') else token
    return value()


def render(value, level=0):
    if isinstance(value, dict):
        return '{\n' + ''.join('\t'*(level+1)+render(k)+' = '+render(v,level+1)+';\n' for k,v in value.items())+'\t'*level+'}'
    if isinstance(value, list):
        return '(\n'+''.join('\t'*(level+1)+render(v,level+1)+',\n' for v in value)+'\t'*level+')'
    return json.dumps(str(value), ensure_ascii=False)


def main():
    project = read_pbx(PROJECT.read_text(encoding='utf-8-sig'))
    objects = project['objects']
    def uid(name): return hashlib.sha256(('omni-1719:'+name).encode()).hexdigest()[:24].upper()
    def put(identity, isa, **fields):
        key=uid(identity); objects[key]={'isa':isa,**fields}; return key
    def add(sequence, key):
        if key not in sequence: sequence.append(key)
    app=objects['666666667777888800000001']
    main_group=objects['888888889999AAAA00000001']['children']
    products=objects['888888889999AAAA00000002']['children']
    sources=objects['99999999AAAA000000000001']['files']
    resources=objects['99999999AAAA000000000003']['files']
    def register(path, target_files, kind):
        relative=path.relative_to(ROOT).as_posix()
        existing=next((k for k,v in objects.items() if v.get('isa')=='PBXFileReference' and v.get('path')==relative),None)
        ref=existing or put('file:'+relative,'PBXFileReference',lastKnownFileType=kind,path=relative,sourceTree='<group>')
        add(main_group,ref)
        build=next((k for k,v in objects.items() if v.get('isa')=='PBXBuildFile' and v.get('fileRef')==ref),None)
        add(target_files,build or put('build:'+relative,'PBXBuildFile',fileRef=ref))
    for path in sorted((ROOT/'OmniDownloader').rglob('*.swift')): register(path,sources,'sourcecode.swift')
    for path in sorted((ROOT/'OmniDownloader/Resources').rglob('*')):
        if path.is_file(): register(path,resources,'text')
    for path in sorted((ROOT/'OmniDownloader').rglob('*.xcassets')): register(path,resources,'folder.assetcatalog')
    package=put('ffmpeg-package','XCRemoteSwiftPackageReference',repositoryURL='https://github.com/jaywcjlove/ffmpeg-kit',requirement={'kind':'revision','revision':'0bd85df8f9693061f6f979414662baaf876a3e20'})
    product=put('ffmpeg-product','XCSwiftPackageProductDependency',package=package,productName='ffmpegkit')
    app['packageProductDependencies']=[product]
    add(objects['99999999AAAA000000000002']['files'],put('ffmpeg-link','PBXBuildFile',productRef=product))
    proj=objects['777777778888999900000001'];proj['packageReferences']=[package]
    for key in ['BBBBBBBB0000111100000001','BBBBBBBB0000111100000002']:
        settings=objects[key]['buildSettings'];settings['MARKETING_VERSION']='1.7.19';settings['CURRENT_PROJECT_VERSION']='78'
    targets=[]
    for name, ui in [('OmniDownloaderTests',False),('OmniDownloaderUITests',True)]:
        test_sources=[];test_resources=[]
        for path in sorted((ROOT/name).rglob('*.swift')): register(path,test_sources,'sourcecode.swift')
        for path in sorted((ROOT/name/'Fixtures').glob('*')):
            if path.is_file(): register(path,test_resources,'file')
        binary=put(name+':product','PBXFileReference',explicitFileType='wrapper.cfbundle',includeInIndex='0',path=name+'.xctest',sourceTree='BUILT_PRODUCTS_DIR');add(products,binary)
        phases=[]
        for kind,files in [('Sources',test_sources),('Frameworks',[]),('Resources',test_resources)]:
            phases.append(put(name+':'+kind,'PBX'+kind+'BuildPhase',buildActionMask='2147483647',files=files,runOnlyForDeploymentPostprocessing='0'))
        configs=[]
        for config in ['Debug','Release']:
            settings={'GENERATE_INFOPLIST_FILE':'YES','PRODUCT_BUNDLE_IDENTIFIER':'com.omnidownloader.'+name.lower(),'PRODUCT_NAME':'$(TARGET_NAME)','SWIFT_VERSION':'5.0','IPHONEOS_DEPLOYMENT_TARGET':'15.0','TARGETED_DEVICE_FAMILY':'1,2','CODE_SIGNING_ALLOWED':'NO','LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/Frameworks','@loader_path/Frameworks']}
            if ui: settings['TEST_TARGET_NAME']='OmniDownloader'
            else: settings.update({'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/OmniDownloader.app/OmniDownloader','BUNDLE_LOADER':'$(TEST_HOST)'})
            configs.append(put(name+':'+config,'XCBuildConfiguration',name=config,buildSettings=settings))
        config_list=put(name+':configs','XCConfigurationList',buildConfigurations=configs,defaultConfigurationIsVisible='0',defaultConfigurationName='Release')
        proxy=put(name+':proxy','PBXContainerItemProxy',containerPortal='777777778888999900000001',proxyType='1',remoteGlobalIDString='666666667777888800000001',remoteInfo='OmniDownloader')
        dependency=put(name+':dependency','PBXTargetDependency',target='666666667777888800000001',targetProxy=proxy)
        target=put(name+':target','PBXNativeTarget',buildConfigurationList=config_list,buildPhases=phases,buildRules=[],dependencies=[dependency],name=name,productName=name,productReference=binary,productType='com.apple.product-type.bundle.ui-testing' if ui else 'com.apple.product-type.bundle.unit-test')
        add(proj['targets'],target);targets.append((name,target))
    PROJECT.write_text('// !$*UTF8*$!\n'+render(project)+'\n',encoding='utf-8')
    scheme=ROOT/'OmniDownloader.xcodeproj/xcshareddata/xcschemes/OmniDownloader.xcscheme';scheme.parent.mkdir(parents=True,exist_ok=True)
    def ref(name,key,ext): return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{key}" BuildableName="{name}.{ext}" BlueprintName="{name}" ReferencedContainer="container:OmniDownloader.xcodeproj"/>'
    app_ref=ref('OmniDownloader','666666667777888800000001','app')
    tests=''.join('<TestableReference skipped="NO">'+ref(n,k,'xctest')+'</TestableReference>' for n,k in targets)
    scheme.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3"><BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry></BuildActionEntries></BuildAction><TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>{tests}</Testables></TestAction><LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction><ProfileAction buildConfiguration="Release"/><AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/></Scheme>''',encoding='utf-8')
    plist=ROOT/'OmniDownloader/App/Info.plist'
    info=plistlib.loads(plist.read_bytes());info.update({'CFBundleShortVersionString':'$(MARKETING_VERSION)','CFBundleVersion':'$(CURRENT_PROJECT_VERSION)','CFBundleIdentifier':'$(PRODUCT_BUNDLE_IDENTIFIER)','UIFileSharingEnabled':True,'LSSupportsOpeningDocumentsInPlace':True,'CFBundleURLTypes':[{'CFBundleURLName':'com.omnidownloader.ios','CFBundleURLSchemes':['omnidownloader']}]});info.pop('UIBackgroundModes',None)
    plist.write_bytes(plistlib.dumps(info,sort_keys=False))
    print('Registered native source, resource, package and test targets')


if __name__=='__main__':main()
