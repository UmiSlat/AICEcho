param(
    [string]$Sdk = 'C:\Android',
    [string]$DebugKeystore = "$env:USERPROFILE\.android\debug.keystore",
    [string]$OutputDirectory,
    [switch]$ProbeOnly
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $root 'build\hcef-lab' }
$stage = Join-Path $out "stage-$([Guid]::NewGuid().ToString('N'))"
$bt = Join-Path $Sdk 'build-tools\36.1.0'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
if (!$ProbeOnly -and !(Test-Path -LiteralPath $DebugKeystore)) { throw "Missing debug keystore: $DebugKeystore" }
New-Item -ItemType Directory -Force -Path $out,"$stage\classes","$out\dex","$stage\generated","$stage\probe-classes","$out\probe-dex" | Out-Null
function Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command" }
}
& "$PSScriptRoot\test.ps1" -OutputDirectory "$stage\tests"
$probeSources = @(Get-ChildItem "$PSScriptRoot\probe" -Filter '*.java' | ForEach-Object FullName)
Checked { javac --release 17 -d "$stage\probe-classes" @probeSources }
$probeClasses = @(Get-ChildItem "$stage\probe-classes" -Filter '*.class' | ForEach-Object FullName)
Checked { & "$bt\d8.bat" --min-api 30 --lib $androidJar --output "$out\probe-dex" @probeClasses }
if ($ProbeOnly) {
    Get-FileHash -Algorithm SHA256 -LiteralPath "$out\probe-dex\classes.dex"
    return
}
Checked { & "$bt\aapt2.exe" compile --dir "$PSScriptRoot\res" -o "$stage\resources.zip" }
Checked { & "$bt\aapt2.exe" link -o "$stage\unsigned.apk" -I $androidJar --manifest "$PSScriptRoot\AndroidManifest.xml" --java "$stage\generated" "$stage\resources.zip" }
$sources = @(Get-ChildItem "$PSScriptRoot\src" -Filter '*.java' -Recurse | ForEach-Object FullName)
Checked { javac --release 17 -cp $androidJar -d "$stage\classes" @sources }
$classes = @(Get-ChildItem "$stage\classes" -Filter '*.class' -Recurse | ForEach-Object FullName)
Checked { & "$bt\d8.bat" --min-api 30 --lib $androidJar --output "$out\dex" @classes }
$archive = [IO.Compression.ZipFile]::Open("$stage\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, "$out\dex\classes.dex", 'classes.dex') | Out-Null }
finally { $archive.Dispose() }
Checked { & "$bt\zipalign.exe" -f 4 "$stage\unsigned.apk" "$stage\aligned.apk" }
Checked { & "$bt\apksigner.bat" sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$out\HcefLab-debug.apk" "$stage\aligned.apk" }
Checked { & "$bt\apksigner.bat" verify --verbose "$out\HcefLab-debug.apk" }
Get-FileHash -Algorithm SHA256 -LiteralPath "$out\HcefLab-debug.apk"
