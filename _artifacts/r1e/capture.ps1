param([Parameter(Mandatory=$true)][string]$Name)
$adb='C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 shell uiautomator dump /sdcard/aurora-r1c-ui.xml
& $adb -s emulator-5554 pull /sdcard/aurora-r1c-ui.xml (Join-Path $PSScriptRoot "$Name.xml")
& $adb -s emulator-5554 shell screencap -p /sdcard/aurora-r1c.png
& $adb -s emulator-5554 pull /sdcard/aurora-r1c.png (Join-Path $PSScriptRoot "$Name.png")
[xml]$tree=Get-Content (Join-Path $PSScriptRoot "$Name.xml")
$tree.SelectNodes('//node') | Where-Object {$_.text -or $_.'content-desc' -or $_.checkable -eq 'true'} | ForEach-Object {"$($_.text) | $($_.'content-desc') | $($_.bounds) | checked=$($_.checked)"}
