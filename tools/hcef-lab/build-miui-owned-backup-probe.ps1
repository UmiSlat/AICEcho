param(
    [string]$Sdk = 'C:\Android',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $root 'build\miui-owned-backup-probe' }
$stage = Join-Path $out "classes-$([Guid]::NewGuid().ToString('N'))"
$dex = Join-Path $out 'dex'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$d8 = Join-Path $Sdk 'build-tools\36.1.0\d8.bat'
New-Item -ItemType Directory -Force -Path $stage,$dex | Out-Null
javac '-J-Duser.language=en' '-J-Duser.country=US' --release 17 -encoding UTF-8 -Xlint:all -classpath $androidJar -d $stage "$PSScriptRoot\audit\MiuiOwnedBackupProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'Owned backup probe compilation failed' }
$classes = @(Get-ChildItem -LiteralPath $stage -Filter '*.class' | ForEach-Object FullName)
& $d8 --min-api 30 --lib $androidJar --output $dex @classes
if ($LASTEXITCODE -ne 0) { throw 'Owned backup probe DEX build failed' }
Get-FileHash -Algorithm SHA256 -LiteralPath "$dex\classes.dex"
