[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Source,
    [Parameter(Mandatory = $true)]
    [string] $Output
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
    throw "Logo source not found: $Source"
}

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$png = [System.IO.File]::ReadAllBytes($sourcePath)
$signature = [byte[]](0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
if ($png.Length -lt 24) {
    throw "Logo source is not a valid PNG: $Source"
}
for ($i = 0; $i -lt $signature.Length; $i++) {
    if ($png[$i] -ne $signature[$i]) {
        throw "Logo source is not a valid PNG: $Source"
    }
}

$outputPath = [System.IO.Path]::GetFullPath($Output)
$parent = Split-Path -Parent $outputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}

# ICO supports PNG payloads on Windows Vista and later. Keep one source image;
# the directory entry advertises its actual 48x48 dimensions to jpackage.
$directory = [byte[]](0, 0, 1, 0, 1, 0, 48, 48, 0, 0, 1, 0, 32, 0)
$directory += [BitConverter]::GetBytes([uint32]$png.Length)
$directory += [BitConverter]::GetBytes([uint32]22)
$ico = [byte[]]($directory + $png)
[System.IO.File]::WriteAllBytes($outputPath, $ico)
Write-Host "Generated $outputPath from $sourcePath"
