param()
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$artifacts = Join-Path $repo 'artifacts'
$runtime = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'companion-runtime.json') -Raw | ConvertFrom-Json
$tools = Join-Path $artifacts 'companion-tools'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
$nodeZip = Join-Path $tools "node-$($runtime.version)-win-x64.zip"
if (-not (Test-Path -LiteralPath $nodeZip)) { Invoke-WebRequest -Uri $runtime.url -OutFile $nodeZip }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $nodeZip).Hash -ne $runtime.sha256) { throw 'Node runtime checksum mismatch.' }
$nodeDirectory = Join-Path $tools "node-$($runtime.version)-win-x64"
if (-not (Test-Path -LiteralPath $nodeDirectory)) { Expand-Archive -LiteralPath $nodeZip -DestinationPath $tools }
$NodePath = Join-Path $nodeDirectory 'node.exe'
$staging = Join-Path $artifacts ('companion-stage-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging -Force | Out-Null
$nodeVersion = (& $NodePath --version).Trim()
if ([int]($nodeVersion.TrimStart('v').Split('.')[0]) -lt 22) { throw 'Build with Node.js 22 or newer.' }
Push-Location (Join-Path $repo 'desktop_export')
$previousBuildPath = $env:PATH
try {
    # npm lifecycle scripts must use the verified runtime even without a global Node installation.
    $env:PATH = $nodeDirectory + [IO.Path]::PathSeparator + $previousBuildPath
    & $NodePath (Join-Path $nodeDirectory 'node_modules/npm/bin/npm-cli.js') ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Companion dependency installation failed.' }
} finally { $env:PATH = $previousBuildPath; Pop-Location }
$ffmpeg = Join-Path $repo 'desktop_export/node_modules/ffmpeg-static/ffmpeg.exe'
if (-not (Test-Path -LiteralPath $ffmpeg)) { throw 'FFmpeg was not installed. Allow the locked ffmpeg-static install script, then rebuild.' }
& $ffmpeg -version | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Bundled FFmpeg could not run.' }
Copy-Item -LiteralPath $NodePath -Destination (Join-Path $staging 'node.exe')
$export = Join-Path $staging 'desktop_export'
New-Item -ItemType Directory -Path $export -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $repo 'desktop_export') -File | Where-Object { $_.Extension -in '.cjs','.ts','.json','.md' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $export }
Copy-Item -LiteralPath (Join-Path $repo 'desktop_export/node_modules') -Destination $export -Recurse
$visualizer = Join-Path $staging 'visualizer'
New-Item -ItemType Directory -Path $visualizer -Force | Out-Null
foreach ($name in @('vendor','version.cjs','verify.cjs')) { Copy-Item -LiteralPath (Join-Path $repo "visualizer/$name") -Destination $visualizer -Recurse }
$fonts = Join-Path $staging 'app/src/main/assets/visualizer'
New-Item -ItemType Directory -Path $fonts -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'app/src/main/assets/visualizer/fonts') -Destination $fonts -Recurse
Copy-Item -LiteralPath (Join-Path $repo 'third-party') -Destination $staging -Recurse
# Include the license and dependency notices for the exact Node executable used by the builder.
Copy-Item -LiteralPath (Join-Path $nodeDirectory 'LICENSE') -Destination (Join-Path $staging 'third-party/Node.txt')
@{ nodeVersion = $nodeVersion; nodeSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $NodePath).Hash; packageLockSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repo 'desktop_export/package-lock.json')).Hash } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $staging 'runtime.json') -Encoding utf8
$archive = Join-Path $artifacts 'companion.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($staging, $archive, [IO.Compression.CompressionLevel]::Optimal, $false)
# Only the newly created staging directory inside this repository is removed.
$resolvedStaging = (Resolve-Path -LiteralPath $staging).Path
if (-not $resolvedStaging.StartsWith($artifacts + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected staging path.' }
Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
Get-Item -LiteralPath $archive | Select-Object FullName,Length
