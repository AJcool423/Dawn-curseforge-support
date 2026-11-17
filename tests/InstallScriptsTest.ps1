$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $root ('build\install-test-' + [guid]::NewGuid().ToString('N'))
$app = Join-Path $fixture 'app'
New-Item -ItemType Directory -Path $app -Force | Out-Null
$primary = 'desktop-desktop-0.2.0.506.jar'
New-Item -ItemType HardLink -Path (Join-Path $app $primary) -Target (Join-Path $env:LOCALAPPDATA "Dawn\Dawn (Feather)\app\$primary") | Out-Null
$config = Join-Path $app 'Dawn (Feather).cfg'
$original = "[Application]`r`napp.mainclass=fixture`r`n[JavaOptions]`r`njava-options=-Xmx384m`r`n"
[IO.File]::WriteAllText($config, $original)
& (Join-Path $root 'install.ps1') -DawnRoot $fixture | Out-Null
& (Join-Path $root 'install.ps1') -DawnRoot $fixture | Out-Null
$sourceHash = (Get-FileHash -LiteralPath (Join-Path $root 'dawn-curseforge-addon.jar') -Algorithm SHA256).Hash
$agentOption = 'java-options=-javaagent:$APPDIR\dawn-curseforge\dawn-curseforge-addon-' + $sourceHash.Substring(0,16).ToLowerInvariant() + '.jar'
$installed = [IO.File]::ReadAllText($config)
if (@($installed -split '\r?\n' | Where-Object { $_ -eq $agentOption }).Count -ne 1) { throw 'Agent option not idempotent.' }
if ([IO.File]::ReadAllText((Join-Path $app 'dawn-curseforge\Dawn (Feather).cfg.original')) -ne $original) { throw 'Original config backup differs.' }
[IO.File]::AppendAllText($config, "java-options=-Dfixture.preserve=true`r`n")
& (Join-Path $root 'uninstall.ps1') -DawnRoot $fixture | Out-Null
$removed = [IO.File]::ReadAllText($config)
if ($removed.Contains($agentOption) -or !$removed.Contains('java-options=-Dfixture.preserve=true')) { throw 'Uninstall did not preserve unrelated edits.' }
& (Join-Path $root 'install.ps1') -DawnRoot $fixture | Out-Null
if (![IO.File]::ReadAllText($config).Contains($agentOption)) { throw 'Reinstall failed.' }
$invalid = Join-Path $root ('build\unsupported-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $invalid 'app') -Force | Out-Null
$failed = $false
try { & (Join-Path $root 'install.ps1') -DawnRoot $invalid | Out-Null } catch { $failed = $true }
if (!$failed) { throw 'Unsupported build was accepted.' }
Write-Output 'PASS: fresh install, idempotency, backup, uninstall preserves edits, reinstall, unsupported-build refusal.'
