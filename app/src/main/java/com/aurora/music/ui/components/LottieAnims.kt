package com.aurora.music.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.aurora.music.R

@Composable
fun LottieEqualizer(modifier: Modifier = Modifier, isPlaying: Boolean = true) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.equalizer))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        isPlaying = isPlaying,
    )
    LottieAnimation(composition = composition, progress = { progress }, modifier = modifier)
}

@Composable
fun LottieLoader(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loader))
    val colors = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.COLOR, MaterialTheme.colorScheme.primary.toArgb(), "**"),
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
    )
    LottieAnimation(composition = composition, progress = { progress }, modifier = modifier, dynamicProperties = colors)
}
