param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$taskStartup = [Environment]::GetFolderPath('Startup')
$taskShortcut = Join-Path $taskStartup 'NoFocus PC Export.lnk'
if ($Remove) {
    if (Test-Path -LiteralPath $taskShortcut) { Remove-Item -LiteralPath $taskShortcut }
    Write-Output 'NoFocus PC export will no longer start at sign-in. The current companion, if running, is unchanged.'
    return
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules'))) { throw 'Run start.cmd once before enabling startup.' }
$taskShell = New-Object -ComObject WScript.Shell
$taskLink = $taskShell.CreateShortcut($taskShortcut)
$taskLink.TargetPath = (Get-Command powershell.exe).Source
$taskLink.Arguments = '-NoProfile -WindowStyle Hidden -File "' + (Join-Path $PSScriptRoot 'background.ps1') + '"'
$taskLink.WorkingDirectory = $PSScriptRoot
$taskLink.WindowStyle = 7
$taskLink.Description = 'NoFocus paired PC video export'
$taskLink.Save()
Write-Output 'NoFocus PC export will start at Windows sign-in. Run background.ps1 to start it now.'
