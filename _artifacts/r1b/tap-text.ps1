param([string]$Text, [switch]$Description)
$qaAdb = 'C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
& $qaAdb -s 0016615BM001167 shell uiautomator dump /sdcard/aurora-r1b-actions.xml | Out-Null
& $qaAdb -s 0016615BM001167 pull /sdcard/aurora-r1b-actions.xml '_artifacts/r1b/actions.xml' 2>$null | Out-Null
[xml]$qaTree = Get-Content -LiteralPath '_artifacts/r1b/actions.xml'
$qaNode = $qaTree.SelectNodes('//node') | Where-Object { $(if ($Description) { $_.'content-desc' } else { $_.text }) -eq $Text } | Select-Object -First 1
if (-not $qaNode) { throw "Missing UI action: $Text" }
$qaBounds = [regex]::Matches($qaNode.bounds, '\d+') | ForEach-Object { [int]$_.Value }
$qaX = [int](($qaBounds[0] + $qaBounds[2]) / 2)
$qaY = [int](($qaBounds[1] + $qaBounds[3]) / 2)
& $qaAdb -s 0016615BM001167 shell input tap $qaX $qaY
