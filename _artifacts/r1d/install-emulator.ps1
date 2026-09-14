$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$signer = 'C:\Users\mw\Documents\projects\apps\reset\.android-sdk\build-tools\35.0.0\apksigner.bat'
$adb = 'C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
& $signer sign --ks 'C:\Users\mw\.android\debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out (Join-Path $PSScriptRoot 'app-emulator.apk') app/build/outputs/apk/release/app-release.apk
if ($LASTEXITCODE -ne 0) { throw 'App signing failed' }
& $signer sign --ks 'C:\Users\mw\.android\debug.keystore' --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out (Join-Path $PSScriptRoot 'app-emulator-tests.apk') app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Test signing failed' }
& $adb devices -l
& $adb -s emulator-5554 install -r (Join-Path $PSScriptRoot 'app-emulator.apk')
if ($LASTEXITCODE -ne 0) { throw 'App install failed' }
& $adb -s emulator-5554 install -r (Join-Path $PSScriptRoot 'app-emulator-tests.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
