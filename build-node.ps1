# Cross-compiles the jsync node (../filesharer/cmd/jsyncnode) into the app's
# native library folders. The app runs it as a program, so it is a plain static
# Go binary named lib*.so (that is the only way Android extracts and lets you
# execute a file from the APK).
#
#   .\build-node.ps1            both ABIs
#   .\build-node.ps1 x86_64     just the emulator
param([string[]]$Abi = @('arm64-v8a', 'x86_64'))
$ErrorActionPreference = 'Stop'
$src = (Resolve-Path (Join-Path $PSScriptRoot '..\filesharer')).Path
$goarch = @{ 'arm64-v8a' = 'arm64'; 'x86_64' = 'amd64' }
foreach ($a in $Abi) {
    $out = Join-Path $PSScriptRoot "app\src\main\jniLibs\$a"
    New-Item -ItemType Directory -Force $out | Out-Null
    $env:GOOS = 'linux'; $env:GOARCH = $goarch[$a]; $env:CGO_ENABLED = '0'
    go build -C $src -trimpath -ldflags '-s -w' -o (Join-Path $out 'libjsyncnode.so') ./cmd/jsyncnode
    if ($LASTEXITCODE -ne 0) { throw "go build failed for $a" }
    Write-Host "built $a"
}
