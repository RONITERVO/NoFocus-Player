$ErrorActionPreference = 'Stop'
$taskNode = (Get-Command node -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules'))) { throw 'Run start.cmd once to install dependencies before enabling background export.' }
$taskState = if ($env:NOFOCUS_EXPORT_STATE) { $env:NOFOCUS_EXPORT_STATE } else { Join-Path $env:LOCALAPPDATA 'NoFocusExport' }
New-Item -ItemType Directory -Force -Path $taskState | Out-Null
$taskServer = Join-Path $PSScriptRoot 'server.cjs'
Start-Process -FilePath $taskNode -ArgumentList ('"' + $taskServer + '"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskState 'companion.log') -RedirectStandardError (Join-Path $taskState 'companion-error.log')
