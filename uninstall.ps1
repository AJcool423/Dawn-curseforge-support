param([string]$DawnRoot = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)")
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $DawnRoot 'app\Dawn (Feather).cfg'
$optionPattern = '^java-options=-javaagent:\$APPDIR\\dawn-curseforge\\dawn-curseforge-addon(?:-[a-f0-9]{16})?\.jar$'
$config = [IO.File]::ReadAllText($configPath)
$lines = $config -split '\r?\n' | Where-Object { $_ -notmatch $optionPattern }
$updated = $lines -join "`r`n"
if ($updated -ne $config) {
    $tempPath = $configPath + '.curseforge-remove'
    [IO.File]::WriteAllText($tempPath, $updated, [Text.UTF8Encoding]::new($false))
    $previousConfig = $configPath + '.curseforge-previous-' + [guid]::NewGuid().ToString('N')
    [IO.File]::Replace($tempPath, $configPath, $previousConfig)
}
Write-Output 'CurseForge add-on disabled. Restart Dawn to finish. Profiles and downloaded content were kept.'
