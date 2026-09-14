param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
$dump = & $adb -s 0016615BM001167 shell uiautomator dump /sdcard/aurora-r2b-ui.xml 2>&1
if ($dump -notmatch 'UI hierchary dumped') { throw "UI did not become ready: $dump" }
& $adb -s 0016615BM001167 pull /sdcard/aurora-r2b-ui.xml (Join-Path $PSScriptRoot "$Name.xml")
& $adb -s 0016615BM001167 shell screencap -p /sdcard/aurora-r2b.png
& $adb -s 0016615BM001167 pull /sdcard/aurora-r2b.png (Join-Path $PSScriptRoot "$Name.png")
[xml]$tree = Get-Content (Join-Path $PSScriptRoot "$Name.xml")
$tree.SelectNodes('//node') | Where-Object {$_.text -or $_.'content-desc' -or $_.checkable -eq 'true'} | ForEach-Object {"$($_.text) | $($_.'content-desc') | $($_.bounds) | checked=$($_.checked)"}
