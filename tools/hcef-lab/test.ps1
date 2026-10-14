param(
    [string]$OutputDirectory,
    [string]$TraceFixture
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (!$OutputDirectory) {
    $OutputDirectory = Join-Path $root "build\hcef-lab-tests\$([Guid]::NewGuid().ToString('N'))"
}
$classes = Join-Path $OutputDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
function Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command" }
}
$sources = @(
    Get-ChildItem -LiteralPath "$PSScriptRoot\probe","$PSScriptRoot\test" -Filter '*.java' | ForEach-Object FullName
    Join-Path $PSScriptRoot 'src\io\github\umislat\aicecho\hceflab\LabProtocol.java'
)
Checked { javac --release 17 -d $classes @sources }
foreach ($test in @('LabProtocolTest', 'NciTraceTest', 'NciSessionTest', 'NciProbeLockTest', 'NciRecoveryTest')) {
    $arguments = @("-Djava.io.tmpdir=$OutputDirectory", '-cp', $classes, $test)
    if ($test -eq 'NciTraceTest' -and $TraceFixture) { $arguments += (Resolve-Path -LiteralPath $TraceFixture).Path }
    Checked { java @arguments }
}
Write-Output "Test classes: $classes"
