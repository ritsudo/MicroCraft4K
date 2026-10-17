# ---------------------------------------------------------------------------
# build.ps1 - build the Minecraft4k J2ME MIDlet with the Sun WTK 2.5.2
#             tool chain (JDK 1.8u131 javac + WTK preverify + jar).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1          # build
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Run     # build + run
# ---------------------------------------------------------------------------
param(
    [switch]$Run,
    [string]$Device = "K800",
    [string]$Version = "1.6"
)

$ErrorActionPreference = "Stop"

$JDK  = "D:\Program Files (x86)\Java\jdk1.8.0_131\bin"
$WTK  = "D:\Program Files\WTK2.5.2_01"
$proj = $PSScriptRoot
$src  = Join-Path $proj "src"
$res  = Join-Path $proj "res"
$cls  = Join-Path $proj "classes"
$pre  = Join-Path $proj "tmpclasses"
$bin  = Join-Path $proj "bin"

$boot = "$WTK\lib\cldcapi11.jar;$WTK\lib\midpapi20.jar"

foreach ($d in @($cls, $pre, $bin)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
    Get-ChildItem $d -Recurse -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
}

Write-Host "[1/4] Compiling (JDK 1.8, -source/-target 1.4, CLDC 1.1 bootclasspath)"
& "$JDK\javac.exe" -source 1.4 -target 1.4 -nowarn -bootclasspath $boot -d $cls `
    "$src\Minecraft4k.java" "$src\GameCanvas.java"
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[2/4] Preverifying (WTK CLDC preverifier)"
& "$WTK\bin\preverify.exe" -d $pre -classpath $boot $cls
if ($LASTEXITCODE -ne 0) { throw "preverify failed ($LASTEXITCODE)" }

Copy-Item "$res\Minecraft4k.png" (Join-Path $pre "Minecraft4k.png") -Force

$manifest = @(
    "MIDlet-1: Minecraft4k, Minecraft4k.png, Minecraft4k"
    "MIDlet-Name: Minecraft4k"
    "MIDlet-Vendor: Unknown"
    "MIDlet-Version: $Version"
    "MicroEdition-Configuration: CLDC-1.1"
    "MicroEdition-Profile: MIDP-2.0"
) -join "`r`n"
[System.IO.File]::WriteAllText((Join-Path $bin "MANIFEST.MF"), $manifest + "`r`n")

Write-Host "[3/4] Packaging JAR"
& "$JDK\jar.exe" cfm (Join-Path $bin "Minecraft4k.jar") (Join-Path $bin "MANIFEST.MF") -C $pre .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }

$size = (Get-Item (Join-Path $bin "Minecraft4k.jar")).Length
$jad = @(
    "MIDlet-1: Minecraft4k, Minecraft4k.png, Minecraft4k"
    "MIDlet-Name: Minecraft4k"
    "MIDlet-Vendor: Unknown"
    "MIDlet-Version: $Version"
    "MicroEdition-Configuration: CLDC-1.1"
    "MicroEdition-Profile: MIDP-2.0"
    "MIDlet-Jar-URL: Minecraft4k.jar"
    "MIDlet-Jar-Size: $size"
) -join "`r`n"
[System.IO.File]::WriteAllText((Join-Path $bin "Minecraft4k.jad"), $jad + "`r`n")

Write-Host "[4/4] Done -> bin\Minecraft4k.jar ($size bytes) + bin\Minecraft4k.jad"
Get-ChildItem $pre | Select-Object Name

if ($Run) {
    Write-Host "Launching emulator (device $Device) ..."
    & "$WTK\bin\emulator.exe" -Xdevice:$Device -Xdescriptor:"$bin\Minecraft4k.jad"
}
