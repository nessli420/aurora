param(
    [Parameter(Mandatory=$true)][string]$Classes,
    [string]$Name = 'phone-tests',
    [switch]$Precision,
    [switch]$Rack,
    [string]$Serial = '0016615BM001167'
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\mw\Documents\projects\apps\reset\.android-sdk\platform-tools\adb.exe'
$stdoutPath = Join-Path $PSScriptRoot "$Name.log"
$stderrPath = Join-Path $PSScriptRoot "$Name.stderr.log"
$runnerArguments = @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$Classes)
if ($Precision) { $runnerArguments += @('-e','precisionOutput','true') }
if ($Rack) { $runnerArguments += @('-e','rackOutput','true') }
$runnerArguments += 'com.aurora.music.test/androidx.test.runner.AndroidJUnitRunner'
$runner = Start-Process -FilePath $adb -ArgumentList $runnerArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
Start-Sleep -Milliseconds 1200
if (!$Precision) { & $adb -s $Serial shell am start -n com.aurora.music/.MainActivity | Out-Null }
$runner.WaitForExit()
Get-Content -LiteralPath $stdoutPath -Tail 45
Get-Content -LiteralPath $stderrPath -Tail 10
$result = Get-Content -LiteralPath $stdoutPath -Raw
if ($runner.ExitCode -ne 0 -or $result -notmatch 'OK \(\d+ tests?\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[34]') { exit 1 }
