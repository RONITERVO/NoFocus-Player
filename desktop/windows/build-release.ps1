param(
    [string]$OutputDirectory = "$PSScriptRoot\..\..\artifacts\windows-x64"
)

$ErrorActionPreference = "Stop"
$project = Join-Path $PSScriptRoot "NoFocus.Desktop\NoFocus.Desktop.csproj"

dotnet publish $project `
    --configuration Release `
    --runtime win-x64 `
    --self-contained true `
    --output $OutputDirectory `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true

$executable = Join-Path $OutputDirectory "NoFocus Speaker.exe"
if (-not (Test-Path -LiteralPath $executable)) {
    throw "Desktop executable was not produced."
}

Get-Item -LiteralPath $executable | Select-Object FullName, Length, LastWriteTime
