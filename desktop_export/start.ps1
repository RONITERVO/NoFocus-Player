$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 22 or newer, then run this launcher again.' }
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Could not install the PC export dependencies.' }
    }
    & node server.cjs --open
    if ($LASTEXITCODE -ne 0) { throw 'The export companion stopped with an error.' }
} finally { Pop-Location }
