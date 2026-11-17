param([string]$DawnRoot = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)")
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$javaPath = Join-Path $DawnRoot 'runtime\bin\javaw.exe'
$gsonPath = Join-Path $DawnRoot 'app\gson-2.11.0.jar'
$classRoot = Join-Path $root ('build\test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classRoot -Force | Out-Null
function Run-TestTool([string[]]$toolArgs, [string]$label) {
    $outPath = Join-Path $classRoot ($label + '.out.log')
    $errPath = Join-Path $classRoot ($label + '.err.log')
    $escaped = @($toolArgs | ForEach-Object { if ($_.Contains('"')) { throw 'Unexpected quote in tool argument.' }; '"' + $_ + '"' })
    $process = Start-Process -FilePath $javaPath -ArgumentList $escaped -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $outPath -RedirectStandardError $errPath
    Get-Content -LiteralPath $outPath
    if ($process.ExitCode -ne 0) { Get-Content -LiteralPath $errPath; throw "$label failed ($($process.ExitCode))." }
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src'),(Join-Path $root 'tests') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
Run-TestTool (@('-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',$gsonPath,'-d',$classRoot) + $sources) 'compile-tests'
foreach ($testName in @('CurseForgeApiTest','CatalogPanelTest','DawnInstallerTest')) {
    Run-TestTool @('-ea','-Djava.awt.headless=true','-cp',($classRoot + ';' + $gsonPath),('local.dawn.curseforge.' + $testName)) $testName
}
& (Join-Path $root 'tests\InstallScriptsTest.ps1')
