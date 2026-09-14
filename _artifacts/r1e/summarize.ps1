$ErrorActionPreference = 'Stop'
$sources = @(
    @('final-data-adapter-stress.log', 'base'),
    @('final-rack-stress.log', 'base'),
    @('final-backup-restore.log', 'base'),
    @('final-rack-service-pcm16.log', 'rack-pcm16'),
    @('final-rack-service-float-settled.log', 'rack-float'),
    @('final-standard-service-pcm16.log', 'standard-pcm16'),
    @('final-standard-service-float.log', 'standard-float'),
    @('final-regressions.log', 'base')
)
$passed = @{}
foreach ($source in $sources) {
    $testClass = ''; $testMethod = ''
    foreach ($line in Get-Content (Join-Path $PSScriptRoot $source[0])) {
        if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') { $testClass = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') { $testMethod = $Matches[1] }
        if ($line -eq 'INSTRUMENTATION_STATUS_CODE: 0') {
            $key = "$($source[1]):${testClass}#${testMethod}"
            $passed[$key] = [ordered]@{ variant=$source[1]; class=$testClass; method=$testMethod; log=$source[0] }
        }
    }
}
$xmls = Get-ChildItem app/build/test-results/testReleaseUnitTest/TEST-*.xml | ForEach-Object { [xml](Get-Content -LiteralPath $_.FullName) }
$jvm = [ordered]@{tests=($xmls.testsuite.tests | Measure-Object -Sum).Sum; failures=($xmls.testsuite.failures | Measure-Object -Sum).Sum; errors=($xmls.testsuite.errors | Measure-Object -Sum).Sum; suites=$xmls.Count}
$jvm | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'jvm-summary.json')
Copy-Item app/build/test-results/testReleaseUnitTest/TEST-*.xml -Destination $PSScriptRoot
$hashes = Get-FileHash app/build/outputs/apk/release/app-release.apk,(Join-Path $PSScriptRoot 'app-emulator.apk'),(Join-Path $PSScriptRoot 'app-emulator-tests.apk') | Select-Object Path,Hash
$summary = [ordered]@{
    date='2026-09-09'; phase='R1e'; device='emulator-5554 / ResetGuardApi35 / Android 15 API 35 / x86_64'; phoneUsed=$false
    jvm=$jvm; emulatorPassedScenarios=$passed.Count; emulatorChecks=@($passed.Values | Sort-Object variant,class,method)
    resolvedTestIssues=@('Stress reset reference now uses fresh same-rack output; inherited asymmetric saturation maps silence to nonzero samples.', 'Speed regression measures a full settled interval after asynchronous AudioTrack speed adoption.')
    visualChecks=@('Processing rack dock and navigation','Add/edit/reorder/remove stage','Low shelf -3.5 dB, Q 1 persisted','Band editor at font scales 1.0 and 1.3; restored to 1.0','Backup picker and successful ZIP export')
    artifacts=$hashes
    limits=@('Emulator and synthetic CPU measurements do not validate phone/DAC routes or sustained underrun, thermal or battery behavior.','Studio retains a PCM16 boundary after per-clip DSP.','Initial legacy-to-rack activation drains partial input; subsequent graph changes use 20 ms matched-input blending.','Long convolution histories/tails are not copied across edits; EOS truncates at source duration.','Unused IR garbage collection and a cross-store crash journal are not implemented.')
}
$summary | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $PSScriptRoot 'validation-summary.json')
[pscustomobject]@{JvmTests=$jvm.tests;JvmFailures=$jvm.failures;EmulatorPassedScenarios=$passed.Count;ArtifactHashes=$hashes} | ConvertTo-Json -Depth 4
