# Builds the Solid desktop installer for Windows (spec 060).
#
#   powershell -ExecutionPolicy Bypass -File ops\desktop\build.ps1
#   powershell -ExecutionPolicy Bypass -File ops\desktop\build.ps1 -Type app-image
#
# Needs JDK 21 (for jpackage) and Node on PATH. WiX 3 must be installed for an .msi; without it, ask for
# -Type exe. jpackage cannot cross-build, so this has to run on Windows.
param([string]$Type = "msi")

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$pom = Get-Content (Join-Path $root "backend\pom.xml") -Raw
$version = ([regex]::Matches($pom, "<version>(.*?)</version>")[1].Groups[1].Value) -replace "-SNAPSHOT", ""
$out = Join-Path $root "backend\target\installer"
$stage = Join-Path $root "backend\target\jpackage-input"

Write-Host "==> Building the web UI"
Push-Location (Join-Path $root "frontend"); npm ci; npm run build; Pop-Location

Write-Host "==> Building the application (bundling PostgreSQL for Windows)"
Push-Location (Join-Path $root "backend"); .\mvnw.cmd -B -DskipTests "-Ddesktop.os=windows" package; Pop-Location

$jar = Get-ChildItem (Join-Path $root "backend\target\solid-*.jar") |
       Where-Object { $_.Name -notlike "*original*" } | Select-Object -First 1
Remove-Item $stage, $out -Recurse -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $stage, $out | Out-Null
Copy-Item $jar.FullName (Join-Path $stage "solid.jar")

Write-Host "==> jpackage ($Type)"
jpackage `
  --name Solid `
  --app-version $version `
  --vendor "AE Software Solutions" `
  --description "Business and personal accounting with US tax" `
  --input $stage `
  --main-jar solid.jar `
  --main-class org.springframework.boot.loader.launch.JarLauncher `
  --java-options "-Dspring.profiles.active=desktop" `
  --java-options "-Xmx1g" `
  --dest $out `
  --type $Type `
  --win-dir-chooser --win-menu --win-menu-group Solid --win-shortcut

Write-Host ""
Write-Host "Built: $((Get-ChildItem $out).Name)"
Write-Host "It carries its own Java and its own PostgreSQL; the person installing needs neither."
