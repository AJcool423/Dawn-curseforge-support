param([string]$DawnRoot = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)")
$ErrorActionPreference = 'Stop'
$appRoot = Join-Path $DawnRoot 'app'
$configPath = Join-Path $appRoot 'Dawn (Feather).cfg'
$primaryJar = Join-Path $appRoot 'desktop-desktop-0.2.0.506.jar'
$expectedHash = 'F07EBE30D40FA2041818EDAB2AF03DDf2247A3C765E437F7BE4848ACE24D50A2'
if (!(Test-Path -LiteralPath $primaryJar) -or (Get-FileHash -LiteralPath $primaryJar -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'This add-on has only been checked against Dawn 0.2.0.506. Installation stopped; Dawn was not changed.'
}
$sourceJar = Join-Path $PSScriptRoot 'dawn-curseforge-addon.jar'
if (!(Test-Path -LiteralPath $sourceJar)) { throw 'Build the add-on first with build.ps1.' }
$sourceHash = (Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash
$jarName = 'dawn-curseforge-addon-' + $sourceHash.Substring(0,16).ToLowerInvariant() + '.jar'
$agentOption = 'java-options=-javaagent:$APPDIR\dawn-curseforge\' + $jarName
$optionPattern = '^java-options=-javaagent:\$APPDIR\\dawn-curseforge\\dawn-curseforge-addon(?:-[a-f0-9]{16})?\.jar$'
$config = [IO.File]::ReadAllText($configPath)
if ($config -notmatch '(?m)^\[JavaOptions\]\s*$') { throw 'JavaOptions section missing; installation stopped.' }
$addonRoot = Join-Path $appRoot 'dawn-curseforge'
New-Item -ItemType Directory -Path $addonRoot -Force | Out-Null
$backupPath = Join-Path $addonRoot 'Dawn (Feather).cfg.original'
if (!(Test-Path -LiteralPath $backupPath)) { Copy-Item -LiteralPath $configPath -Destination $backupPath }
$installedJar = Join-Path $addonRoot $jarName
if (!(Test-Path -LiteralPath $installedJar) -or (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash -ne $sourceHash) {
    $stagedJar = Join-Path $addonRoot ('addon-' + [guid]::NewGuid().ToString('N') + '.staged')
    try {
        Copy-Item -LiteralPath $sourceJar -Destination $stagedJar
        if ((Get-FileHash -LiteralPath $stagedJar -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Staged add-on integrity check failed.' }
        if (Test-Path -LiteralPath $installedJar) {
            $previousJar = Join-Path $addonRoot ('addon-' + [guid]::NewGuid().ToString('N') + '.previous.jar')
            [IO.File]::Replace($stagedJar, $installedJar, $previousJar)
        } else { [IO.File]::Move($stagedJar, $installedJar) }
    } finally {
        if (Test-Path -LiteralPath $stagedJar) { Remove-Item -LiteralPath $stagedJar }
    }
}
if (!$config.Contains($agentOption)) {
    $config = (($config -split '\r?\n' | Where-Object { $_ -notmatch $optionPattern }) -join "`r`n")
    $config = $config -replace '(?m)^(\[JavaOptions\])\r?$', ('$1' + "`r`n" + $agentOption)
    $tempPath = $configPath + '.curseforge-new'
    [IO.File]::WriteAllText($tempPath, $config, [Text.UTF8Encoding]::new($false))
    $previousConfig = Join-Path $addonRoot ('config-' + [guid]::NewGuid().ToString('N') + '.previous.cfg')
    [IO.File]::Replace($tempPath, $configPath, $previousConfig)
}
Write-Output 'Installed the local CurseForge add-on. Restart Dawn to load the CurseForge menu.'
Write-Output "Original config backup: $backupPath"
