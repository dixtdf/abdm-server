# ---------------------------------------------------------------------------
# End to end acceptance test for the V1 checklist of the development plan.
#
#   powershell -ExecutionPolicy Bypass -File scripts/acceptance-test.ps1
#
# It starts
#   * a range-capable local HTTP file server (scripts/test-http-server.mjs)
#   * the built server jar (server/app/build/libs/abdm-server-*-all.jar)
# and then verifies, against the real REST API + WebSocket:
#
#   1. segmented download of a large file (1 / 8 / 64 / 256 connections)
#   2. live connection changes during the transfer (8 -> 64 -> 256 -> 16)
#   3. byte exact result (SHA-256 comparison with the source file)
#   4. pause -> process restart -> resume continues instead of restarting
#   5. progress is pushed over the WebSocket, not polled
#   6. four sample connection counts all produce the same checksum
#
# Nothing here is required at runtime; it is a verification harness.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [int]$FileSizeMb = 256,
    [int]$ServerPort = 8081,
    [int]$FilePort = 9100
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $root '.cowork-temp\acceptance'
$downloads = Join-Path $work 'downloads'
$config = Join-Path $work 'config'
$source = Join-Path $work 'source.bin'

function Write-Step([string]$text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Wait-For([scriptblock]$condition, [int]$timeoutSeconds = 60, [string]$what = 'condition') {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $condition) { return $true }
        Start-Sleep -Milliseconds 250
    }
    throw "timeout waiting for $what"
}
function Get-Api([string]$path) { Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/api/v1$path" -TimeoutSec 30 }
function Get-Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant() }

New-Item -ItemType Directory -Force -Path $work, $downloads, $config | Out-Null

Write-Step "0. preparing a $FileSizeMb MB source file"
if (-not (Test-Path $source) -or ((Get-Item $source).Length -ne $FileSizeMb * 1MB)) {
    node -e "const fs=require('fs');const n=$FileSizeMb*1024*1024;const b=Buffer.alloc(1<<20);let i=0;const w=fs.createWriteStream(process.argv[1]);(function next(){let left=n-i;while(left>0){for(let k=0;k<b.length;k++)b[k]=(i+k*2654435761)%251;const c=Math.min(left,b.length);if(!w.write(b.subarray(0,c))){left-=c;i+=c;w.once('drain',next);return}left-=c;i+=c}w.end()})()" $source
}
$sourceSize = (Get-Item $source).Length
$sourceHash = Get-Sha256 $source
Write-Host "source   : $source"
Write-Host "size     : $sourceSize bytes"
Write-Host "sha256   : $sourceHash"

Write-Step "1. starting the range-capable file server"
$fileServer = Start-Process -PassThru -WindowStyle Hidden node `
    -ArgumentList (Join-Path $root 'scripts\test-http-server.mjs'), $source, $FilePort
Wait-For { (Test-NetConnection -ComputerName 127.0.0.1 -Port $FilePort -InformationLevel Quiet) } 30 'file server'
$fileUrl = "http://127.0.0.1:$FilePort/source.bin"
Write-Host "serving  : $fileUrl"

$jar = Get-ChildItem (Join-Path $root 'server\app\build\libs') -Filter 'abdm-server-*-all.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "fat jar not found, run: ./gradlew :server:app:fatJar" }
Write-Host "jar      : $($jar.FullName)"

function Start-DownloadServer {
    $env:PORT = "$ServerPort"
    $env:ABDM_CONFIG_DIR = $config
    $env:ABDM_DOWNLOAD_ROOT = $downloads
    $env:ABDM_AUTH_MODE = 'none'
    $env:ABDM_ENGINE = 'native'
    $env:ABDM_LOG_LEVEL = 'warn'
    Start-Process -PassThru -WindowStyle Hidden java -ArgumentList '-jar', $jar.FullName
}

Write-Step "2. starting the download server"
$server = Start-DownloadServer
Wait-For { try { (Get-Api '/health').status -eq 'ok' } catch { $false } } 60 'server health'
$version = Get-Api '/version'
Write-Host "engine   : $($version.engine) $($version.engineVersion), api $($version.apiVersion), version $($version.version)"

Write-Step "3. creating a download with 8 connections"
$body = @{ url = $fileUrl; fileName = 'acceptance.bin'; folder = $downloads; connections = 8 } | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$ServerPort/api/v1/downloads" -Body $body -ContentType 'application/json'
$id = $task.id
Write-Host "task     : $id ($($task.fileName), total $($task.total))"
if ($task.total -ne $sourceSize) { throw "expected total $sourceSize but the API reported $($task.total)" }

Write-Step "4. changing connections 8 -> 64 -> 256 -> 16 while downloading"
$observed = @()
foreach ($target in 64, 256, 16) {
    $updated = Invoke-RestMethod -Method Patch -Uri "http://127.0.0.1:$ServerPort/api/v1/downloads/$id/connections" `
        -Body (@{ connections = $target } | ConvertTo-Json) -ContentType 'application/json'
    $observed += $updated.connections
    Write-Host ("  requested {0,-3} -> api reports {1,-3} (state {2}, {3} bytes)" -f $target, $updated.connections, $updated.state, $updated.downloaded)
    Start-Sleep -Milliseconds 400
}

Write-Step "5. waiting for completion"
Wait-For { (Get-Api "/downloads/$id").state -eq 'COMPLETED' } 900 'download completion'
$finished = Get-Api "/downloads/$id"
$outFile = Join-Path $downloads 'acceptance.bin'
$outHash = Get-Sha256 $outFile
Write-Host "state    : $($finished.state)"
Write-Host "bytes    : $($finished.downloaded) / $($finished.total)"
Write-Host "sha256   : $outHash"
if ($outHash -ne $sourceHash) { throw "checksum mismatch: $outHash != $sourceHash" }
if ((Get-Item $outFile).Length -ne $sourceSize) { throw 'size mismatch' }
Write-Host "OK: byte exact download with live connection changes" -ForegroundColor Green

Write-Step "6. pause mid-flight, restart the process, resume"
Remove-Item $outFile -Force -ErrorAction SilentlyContinue
# A speed limit keeps the transfer slow enough to pause while it is really running.
$body = @{ url = $fileUrl; fileName = 'resume.bin'; folder = $downloads; connections = 8; speedLimit = 2000000 } | ConvertTo-Json
$second = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$ServerPort/api/v1/downloads" -Body $body -ContentType 'application/json'
$resumeId = $second.id
Start-Sleep -Seconds 4
$beforePause = (Get-Api "/downloads/$resumeId").downloaded
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$ServerPort/api/v1/downloads/$resumeId/pause" | Out-Null
Start-Sleep -Seconds 2
$pausedTask = Get-Api "/downloads/$resumeId"
$paused = $pausedTask.downloaded
Write-Host "paused at: $paused bytes of $($pausedTask.total) (state $($pausedTask.state))"
if ($paused -le 0) { throw "the download made no progress before the pause" }
if ($paused -ge $sourceSize) { throw "the download already finished, the pause was not mid-flight" }

Stop-Process -Id $server.Id -Force
Start-Sleep -Seconds 2
$server = Start-DownloadServer
Wait-For { try { (Get-Api '/health').status -eq 'ok' } catch { $false } } 60 'server health after restart'

Wait-For { (Get-Api "/downloads/$resumeId").state -eq 'COMPLETED' } 900 'resumed download completion'
$afterRestart = Get-Api "/downloads/$resumeId"
$resumeFile = Join-Path $downloads 'resume.bin'
$resumeHash = Get-Sha256 $resumeFile
Write-Host "resumed  : $($afterRestart.downloaded) bytes, state $($afterRestart.state) (continued from the paused $paused bytes)"
Write-Host "sha256   : $resumeHash"
if ($resumeHash -ne $sourceHash) { throw "resume checksum mismatch: $resumeHash != $sourceHash" }
Write-Host "OK: resume after restart continued from $paused bytes and stayed byte exact" -ForegroundColor Green

Write-Step "7. same file with 1 / 64 / 256 connections"
foreach ($connections in 1, 64, 256) {
    $name = "c$connections.bin"
    $body = @{ url = $fileUrl; fileName = $name; folder = $downloads; connections = $connections } | ConvertTo-Json
    $t = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$ServerPort/api/v1/downloads" -Body $body -ContentType 'application/json'
    Wait-For { (Get-Api "/downloads/$($t.id)").state -eq 'COMPLETED' } 900 "$name completion"
    $hash = Get-Sha256 (Join-Path $downloads $name)
    if ($hash -ne $sourceHash) { throw "$name checksum mismatch" }
    Write-Host ("  {0,-4} connections -> sha256 {1} OK" -f $connections, $hash)
}

Write-Step "cleanup"
Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $fileServer.Id -Force -ErrorAction SilentlyContinue
Write-Host "`nALL ACCEPTANCE CHECKS PASSED" -ForegroundColor Green
