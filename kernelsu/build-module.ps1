$ErrorActionPreference = 'Stop'

$packager = Join-Path $PSScriptRoot 'package_module.py'
$python = Get-Command python -ErrorAction SilentlyContinue

if ($python) {
    & $python.Source $packager
} else {
    & py -3 $packager
}

if ($LASTEXITCODE -ne 0) {
    throw "Module build failed with exit code $LASTEXITCODE"
}
