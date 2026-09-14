param([string]$Name, [string]$Serial = '0016615BM001167')
$adb='C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
& $adb -s $Serial shell uiautomator dump /sdcard/aurora-r0-ui.xml
& $adb -s $Serial pull /sdcard/aurora-r0-ui.xml "_artifacts/r1a/$Name.xml"
& $adb -s $Serial shell screencap -p /sdcard/aurora-r0.png
& $adb -s $Serial pull /sdcard/aurora-r0.png "_artifacts/r1a/$Name.png"
[xml]$tree=Get-Content "_artifacts/r1a/$Name.xml"
$tree.SelectNodes('//node') | Where-Object {$_.text -or $_.'content-desc' -or $_.checkable -eq 'true'} | ForEach-Object {"$($_.text) | $($_.'content-desc') | $($_.bounds) | checked=$($_.checked)"}

