$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dist=(Resolve-Path (Join-Path $workspace 'windows\dist')).Path
$canonical=Join-Path $dist 'OmniDownloader_Web'
$source=(Resolve-Path (Join-Path $dist '1.7.20\OmniDownloader_Web')).Path
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$oldDirectory=Join-Path $dist ('OmniDownloader_Web.backup-1720-'+$stamp)
foreach($path in @($canonical,$source,$oldDirectory)){
    if(-not [IO.Path]::GetFullPath($path).StartsWith($dist+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Directory boundary check failed'}
}
$base='http://127.0.0.1:58000'
$tasks=@((Invoke-RestMethod ($base+'/api/tasks'))|ForEach-Object{$_})
$active=@($tasks|Where-Object{$_.status -in @('PENDING','ANALYZING','DOWNLOADING','PROCESSING','MERGING')})
if($active.Count){throw 'Current service has active tasks; update deferred'}
$settings=Invoke-RestMethod ($base+'/api/settings')
$listeners=@(Get-NetTCPConnection -LocalPort 58000 -State Listen)
if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw 'Unexpected web listener'}
$owner=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listeners[0].OwningProcess)
if($owner.ExecutablePath -ne (Join-Path $canonical 'OmniDownloader_Web.exe')){throw 'Unexpected service executable'}
$backup=Join-Path $workspace ('.local\backups\web-1720-'+$stamp)
New-Item -ItemType Directory -Path $backup -Force|Out-Null
$state=Join-Path $env:LOCALAPPDATA 'OmniDownloader'
if(Test-Path -LiteralPath $state){Copy-Item -LiteralPath $state -Destination (Join-Path $backup 'user-state') -Recurse}
@{tasks=$tasks;settings=$settings}|ConvertTo-Json -Depth 32|Set-Content -LiteralPath (Join-Path $backup 'before.private.json') -Encoding utf8
Stop-Process -Id $owner.ProcessId
for($attempt=0;$attempt -lt 30;$attempt++){if(-not (Get-Process -Id $owner.ProcessId -ErrorAction SilentlyContinue)){break};Start-Sleep -Milliseconds 100}
Move-Item -LiteralPath $canonical -Destination $oldDirectory
Copy-Item -LiteralPath $source -Destination $canonical -Recurse
$process=Start-Process -FilePath (Join-Path $canonical 'OmniDownloader_Web.exe') -ArgumentList '--autostart' -WindowStyle Hidden -PassThru
for($attempt=0;$attempt -lt 40;$attempt++){
    try{$version=(Invoke-RestMethod ($base+'/openapi.json') -TimeoutSec 2).info.version;if($version -eq '1.7.20'){break}}catch{}
    Start-Sleep -Milliseconds 250
}
if($version -ne '1.7.20'){throw ('New service failed startup; old executable directory is retained at '+$oldDirectory)}
$after=@((Invoke-RestMethod ($base+'/api/tasks'))|ForEach-Object{$_})
if((Compare-Object @($tasks|ForEach-Object{$_.id}|Sort-Object) @($after|ForEach-Object{$_.id}|Sort-Object))){throw 'Task history IDs changed'}
$newSettings=Invoke-RestMethod ($base+'/api/settings')
foreach($property in @('downloadDir','proxyUrl','maxConcurrentTasks','theme','relaySites','autoStart')){
    $beforeValue=$settings.$property|ConvertTo-Json -Depth 20 -Compress
    $afterValue=$newSettings.$property|ConvertTo-Json -Depth 20 -Compress
    if($beforeValue -ne $afterValue){throw ('Setting changed: '+$property)}
}
$report=@{version=$version;port=58000;historyCount=$after.Count;historyIDsPreserved=$true;downloadDirectoryPreserved=$true;backup=$backup;previousExecutableDirectory=$oldDirectory;processId=$process.Id}
$report|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $workspace 'release\1.7.20\qa\local-web-update.json') -Encoding utf8
$report|Select-Object version,port,historyCount,historyIDsPreserved,downloadDirectoryPreserved,processId|ConvertTo-Json
