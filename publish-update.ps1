# Builds a release APK and publishes it to the Drive, where the installed apps find it and offer to update.
#
#   .\publish-update.ps1                    bumps the patch number (0.5.0 -> 0.5.1) and publishes
#   .\publish-update.ps1 -Notes "Faster scrolling"
#   .\publish-update.ps1 -Version 0.6.0     picks the version name (the version code always goes up by one)
#   .\publish-update.ps1 -NoBump            publishes what version.properties says (e.g. after bumping it by hand)
#   .\publish-update.ps1 -NoBuild           publishes the APK already built
#
# Needs 'jsync auth login' on this PC for the same account the phones are signed in with. The APK and a small
# latest.json go to Apps/jsync-android/ in that Drive; the app reads latest.json through the node it already has.
# Every update must be signed with the same key as the installed app (this build uses the debug key of this PC,
# ~/.android/debug.keystore: keep a copy of it, or phones would have to be reinstalled).
param(
    [string]$Notes = '',
    [string]$Version = '',
    [switch]$NoBump,
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$props = Join-Path $root 'app\version.properties'
$folder = 'Apps/jsync-android'

$p = @{}
Get-Content $props | ForEach-Object { if ($_ -match '^\s*(\w+)\s*=\s*(.*)\s*$') { $p[$Matches[1]] = $Matches[2] } }
$code = [int]$p['versionCode']
$name = $p['versionName']
if (-not $NoBump) {
    $code += 1
    if ($Version) { $name = $Version }
    else {
        $parts = $name.Split('.')
        $parts[-1] = [string]([int]$parts[-1] + 1)
        $name = $parts -join '.'
    }
    "versionCode=$code`nversionName=$name" | Set-Content -NoNewline -Encoding ascii $props
}
Write-Host "publishing $name (code $code)"

if (-not $NoBuild) {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10' }
    & (Join-Path $root 'gradlew.bat') -p $root assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }
}
$built = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $built)) { throw "no APK at $built" }

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$apkName = "jsyncdrive-$name.apk"
$apk = Join-Path $dist $apkName
Copy-Item $built $apk -Force
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$manifest = [ordered]@{
    versionCode = $code
    versionName = $name
    apk         = $apkName
    size        = (Get-Item $apk).Length
    sha256      = $sha
    notes       = $Notes
}
$latest = Join-Path $dist 'latest.json'
$manifest | ConvertTo-Json | Set-Content -Encoding utf8 $latest

# The APK first, the manifest last: a phone that sees the new manifest always finds its APK.
& jsync drive mkdir $folder
if ($LASTEXITCODE -ne 0) { throw 'jsync drive mkdir failed (is `jsync auth login` done?)' }
& jsync drive put $apk $folder
if ($LASTEXITCODE -ne 0) { throw 'uploading the APK failed' }
& jsync drive put $latest $folder
if ($LASTEXITCODE -ne 0) { throw 'uploading latest.json failed' }

# Older APKs go to the Drive's trash.
(& jsync drive ls $folder) | ForEach-Object {
    $f = $_.Trim()
    if ($f -like 'jsyncdrive-*.apk' -and $f -ne $apkName) { & jsync drive rm "$folder/$f" | Out-Null }
}
Write-Host "published $apkName ($([math]::Round($manifest.size / 1MB, 1)) MB) sha256 $($sha.Substring(0, 12))…"
