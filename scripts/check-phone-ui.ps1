param([string]$Serial)

$ErrorActionPreference = 'Stop'
$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }
function Invoke-Adb {
    $output = & adb @deviceArgs @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
    return ($output -join "`n").Trim()
}

$size = Invoke-Adb shell wm size
$density = Invoke-Adb shell wm density
$originalSize = if ($size -match 'Override size: (\d+x\d+)') { $Matches[1] } else { 'reset' }
$originalDensity = if ($density -match 'Override density: (\d+)') { $Matches[1] } else { 'reset' }
$originalFont = Invoke-Adb shell settings get system font_scale
$originalRotation = Invoke-Adb shell settings get system user_rotation
$originalAutoRotate = Invoke-Adb shell settings get system accelerometer_rotation
$runner = 'dev.nofocus.folderplayer.test/dev.nofocus.folderplayer.CompactUiTest'

function Test-Layout([string]$Name, [bool]$Landscape = $false) {
    $orientationArgs = if ($Landscape) { @('-e', 'orientation', 'landscape') } else { @() }
    $result = Invoke-Adb shell am instrument -w @orientationArgs $runner
    if ($result -notmatch '(?m)^PASS:') { throw "$Name`n$result" }
    Write-Output "$Name — $result"
}

try {
    Test-Layout 'Original display'
    Invoke-Adb shell wm size 640x960 | Out-Null
    Invoke-Adb shell wm density 320 | Out-Null
    foreach ($scale in @('1.0', '1.5', '2.0')) {
        Invoke-Adb shell settings put system font_scale $scale | Out-Null
        foreach ($rotation in @(0, 1)) {
            Invoke-Adb shell wm user-rotation lock $rotation | Out-Null
            Test-Layout "320 × 480 dp; font $scale; rotation $rotation" ($rotation -eq 1)
        }
    }
} finally {
    Invoke-Adb shell wm size $originalSize | Out-Null
    Invoke-Adb shell wm density $originalDensity | Out-Null
    if ($originalFont -eq 'null') { Invoke-Adb shell settings delete system font_scale | Out-Null }
    else { Invoke-Adb shell settings put system font_scale $originalFont | Out-Null }
    Invoke-Adb shell wm user-rotation lock $originalRotation | Out-Null
    Invoke-Adb shell settings put system accelerometer_rotation $originalAutoRotate | Out-Null
}
