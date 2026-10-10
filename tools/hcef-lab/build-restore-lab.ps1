param(
    [string]$Sdk = 'C:\Android',
    [string]$DebugKeystore = "$env:USERPROFILE\.android\debug.keystore",
    [string]$Keytool,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $root 'build\restore-path-lab' }
$source = Join-Path $PSScriptRoot 'restore-lab'
$bt = Join-Path $Sdk 'build-tools\36.1.0'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
if (!(Test-Path -LiteralPath $DebugKeystore)) { throw 'Existing debug keystore required' }
if (!$Keytool) {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) {
        $Keytool = $command.Source
    } else {
        $properties = @(java -XshowSettings:properties -version 2>&1 | ForEach-Object { $_.ToString() })
        if ($LASTEXITCODE -ne 0) { throw 'Cannot locate current Java runtime' }
        $javaHomeLine = @($properties | Where-Object { $_ -match '^\s*java\.home\s*=' })
        if ($javaHomeLine.Count -ne 1) { throw 'Specify -Keytool with an existing JDK keytool path' }
        $Keytool = Join-Path $javaHomeLine[0].Split('=', 2)[1].Trim() 'bin\keytool.exe'
    }
}
if (!(Test-Path -LiteralPath $Keytool)) { throw 'Missing keytool; specify -Keytool' }
if (Test-Path -LiteralPath $out) { throw 'Use a new output directory; existing artifacts are preserved' }
$stage = Join-Path $out 'stage'
New-Item -ItemType Directory -Path "$stage\classes","$stage\dex" -Force | Out-Null
function Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command" }
}
Checked { & "$bt\aapt2.exe" link -o "$stage\unsigned.apk" -I $androidJar --manifest "$source\AndroidManifest.xml" }
$sources = @(Get-ChildItem -LiteralPath "$source\src" -Filter '*.java' -Recurse | ForEach-Object FullName)
Checked { javac --release 17 -Xlint:all -cp $androidJar -d "$stage\classes" @sources }
$classes = @(Get-ChildItem -LiteralPath "$stage\classes" -Filter '*.class' -Recurse | ForEach-Object FullName)
Checked { & "$bt\d8.bat" --min-api 30 --lib $androidJar --output "$stage\dex" @classes }
$zip = [IO.Compression.ZipFile]::Open("$stage\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$stage\dex\classes.dex", 'classes.dex') | Out-Null }
finally { $zip.Dispose() }
Checked { & "$bt\zipalign.exe" -f 4 "$stage\unsigned.apk" "$stage\aligned.apk" }
Checked { & "$bt\apksigner.bat" sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$out\RestorePathLab-debug.apk" "$stage\aligned.apk" }
Checked { & "$bt\apksigner.bat" verify --verbose "$out\RestorePathLab-debug.apk" }
Checked { & $Keytool '-J-Duser.language=en' '-J-Duser.country=US' -exportcert -alias androiddebugkey -keystore $DebugKeystore -storepass android -file "$out\signer-public.der" }
Get-FileHash -Algorithm SHA256 -LiteralPath "$out\RestorePathLab-debug.apk","$out\signer-public.der"
