param([string]$DawnRoot = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)")
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$javaPath = Join-Path $DawnRoot 'runtime\bin\javaw.exe'
$gsonPath = Join-Path $DawnRoot 'app\gson-2.11.0.jar'
if (!(Test-Path -LiteralPath $javaPath) -or !(Test-Path -LiteralPath $gsonPath)) { throw 'Expected Dawn runtime or Gson dependency missing.' }
$buildRoot = Join-Path $taskRoot 'build'
$classRoot = Join-Path $buildRoot ('classes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classRoot -Force | Out-Null
$sourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
function Run-JavaTool([string[]]$toolArgs, [string]$label) {
    $outPath = Join-Path $buildRoot ($label + '.out.log')
    $errPath = Join-Path $buildRoot ($label + '.err.log')
    $escaped = @($toolArgs | ForEach-Object { if ($_.Contains('"')) { throw 'Unexpected quote in tool argument.' }; '"' + $_ + '"' })
    $process = Start-Process -FilePath $javaPath -ArgumentList $escaped -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $outPath -RedirectStandardError $errPath
    if ($process.ExitCode -ne 0) { Get-Content -LiteralPath $errPath; throw "$label failed ($($process.ExitCode))." }
}
Run-JavaTool (@('-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',$gsonPath,'-d',$classRoot) + $sourcePaths) 'compile'
$manifestPath = Join-Path $buildRoot 'MANIFEST.MF'
@('Manifest-Version: 1.0','Premain-Class: local.dawn.curseforge.AddonAgent','Agent-Class: local.dawn.curseforge.AddonAgent','Can-Redefine-Classes: false','Can-Retransform-Classes: false','') | Set-Content -LiteralPath $manifestPath -Encoding ascii
$jarPath = Join-Path $taskRoot 'dawn-curseforge-addon.jar'
Run-JavaTool @('-m','jdk.jartool/sun.tools.jar.Main','--create','--file',$jarPath,'--manifest',$manifestPath,'-C',$classRoot,'.') 'package'
Write-Output "Built $jarPath"
Write-Output "Classes $classRoot"
