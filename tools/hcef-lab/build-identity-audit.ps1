param(
    [string]$Sdk = 'C:\Android',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $root 'build\hcef-identity-audit' }
$stage = Join-Path $out "classes-$([Guid]::NewGuid().ToString('N'))"
$dex = Join-Path $out 'dex'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$d8 = Join-Path $Sdk 'build-tools\36.1.0\d8.bat'
New-Item -ItemType Directory -Force -Path $stage,$dex | Out-Null
javac --release 17 -Xlint:all -d $stage "$PSScriptRoot\audit\NfcIdentityAudit.java"
if ($LASTEXITCODE -ne 0) { throw 'Identity audit compilation failed' }
$classes = @(Get-ChildItem -LiteralPath $stage -Filter '*.class' | ForEach-Object FullName)
& $d8 --min-api 30 --lib $androidJar --output $dex @classes
if ($LASTEXITCODE -ne 0) { throw 'Identity audit DEX build failed' }
Get-FileHash -Algorithm SHA256 -LiteralPath "$dex\classes.dex"
