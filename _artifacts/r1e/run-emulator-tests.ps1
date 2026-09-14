param(
    [Parameter(Mandatory=$true)][string]$Classes,
    [string]$Name = 'emulator-tests',
    [switch]$Precision,
    [switch]$Rack
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
$artifactDirectory = $PSScriptRoot
$stdoutPath = Join-Path $artifactDirectory "$Name.log"
$stderrPath = Join-Path $artifactDirectory "$Name.stderr.log"
$runnerArguments = @('-s','emulator-5554','shell','am','instrument','-w','-r','-e','class',$Classes)
if ($Precision) { $runnerArguments += @('-e','precisionOutput','true') }
if ($Rack) { $runnerArguments += @('-e','rackOutput','true') }
$runnerArguments += 'com.aurora.music.test/androidx.test.runner.AndroidJUnitRunner'
$runner = Start-Process -FilePath $adb -ArgumentList $runnerArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
# Instrumentation restarts the app. Foreground after that restart for Android 15 audio focus.
Start-Sleep -Milliseconds 1200
if (!$Precision) { & $adb -s emulator-5554 shell am start -n com.aurora.music/.MainActivity | Out-Null }
$runner.WaitForExit()
Get-Content -LiteralPath $stdoutPath -Tail 55
Get-Content -LiteralPath $stderrPath -Tail 10
$testOutput = Get-Content -LiteralPath $stdoutPath -Raw
if ($runner.ExitCode -ne 0 -or $testOutput -notmatch 'OK \(\d+ tests?\)' -or $testOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') { exit 1 }
