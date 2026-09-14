$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-phone-tests.ps1'
$class = 'com.aurora.music.playback.PrecisionPlaybackDeviceTest'
$common = @('liveConvolutionReplacementFormatChangeAndSeekKeepMeasuredStereoGain',
    'crossfadeHandoffRetainsCustomAndConvolutionProcessingAndQueue',
    'bothMixDecksProcessCustomEffectsAndConvolutionThroughSeekAndResume',
    'customShuffleSurvivesCrossfadeAndUnshuffleRestoresTheOriginalOrder')
$rackMethods = (($common + 'liveRackWetBypassAndDisableKeepQueueAndMeasuredGain') | ForEach-Object { "$class#$_" }) -join ','
& $runner -Classes $rackMethods -Name phone-rack-pcm16 -Rack
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$standardFloat = (($common + 'precisionOutputKeepsSpeedAndFallsBackWhenSilenceSkippingIsEnabled') | ForEach-Object { "$class#$_" }) -join ','
& $runner -Classes $standardFloat -Name phone-standard-float -Precision
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$standardPcm = ($common | ForEach-Object { "$class#$_" }) -join ','
& $runner -Classes $standardPcm -Name phone-standard-pcm16
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $runner -Classes 'com.aurora.music.data.ProcessingPresetDeviceTest,com.aurora.music.data.ProcessingPresetBundleDeviceTest,com.aurora.music.playback.PcmMeasurementDeviceTest,com.aurora.music.playback.MeterFormatTransitionDeviceTest,com.aurora.music.playback.ProcessingPresetPlaybackDeviceTest,com.aurora.music.playback.R0PlaybackDeviceTest,com.aurora.music.mix.MixPlaybackDeviceTest' -Name phone-regressions
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $runner -Classes 'com.aurora.music.playback.PrecisionEffectsDeviceTest,com.aurora.music.playback.PrecisionChainDeviceTest,com.aurora.music.playback.PrecisionConvolutionDeviceTest' -Name phone-effects-chain
exit $LASTEXITCODE
