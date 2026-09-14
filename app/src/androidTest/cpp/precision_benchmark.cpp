#include <jni.h>

// Test-only reference kernel. Deliberately includes one JNI transfer in each direction per block.
// No native runtime is shipped in the application and no per-sample JNI calls are made.
extern "C" JNIEXPORT jdouble JNICALL
Java_com_aurora_music_playback_engine_PrecisionNativeBenchmark_process(
        JNIEnv* env, jobject, jdoubleArray samples, jdoubleArray coefficients, jdoubleArray state,
        jint channels, jint frames) {
    const int coefficientCount = env->GetArrayLength(coefficients);
    const int bands = coefficientCount / 5;
    const int sampleCount = channels * frames;
    const int stateCount = channels * bands * 2;
    if (channels < 1 || channels > 2 || frames < 1 || frames > 256 || coefficientCount % 5 ||
        bands < 1 || bands > 64 || env->GetArrayLength(samples) < sampleCount ||
        env->GetArrayLength(state) < stateCount) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Unsupported benchmark block");
        return 0;
    }
    double block[512];
    double coeff[320];
    double history[256];
    env->GetDoubleArrayRegion(samples, 0, sampleCount, block);
    env->GetDoubleArrayRegion(coefficients, 0, coefficientCount, coeff);
    env->GetDoubleArrayRegion(state, 0, stateCount, history);
    for (int frame = 0; frame < frames; ++frame) {
        for (int channel = 0; channel < channels; ++channel) {
            double x = block[frame * channels + channel];
            for (int band = 0; band < bands; ++band) {
                const int c = band * 5;
                const int h = (band * channels + channel) * 2;
                const double y = coeff[c] * x + history[h];
                history[h] = coeff[c + 1] * x - coeff[c + 3] * y + history[h + 1];
                history[h + 1] = coeff[c + 2] * x - coeff[c + 4] * y;
                x = y;
            }
            block[frame * channels + channel] = x;
        }
    }
    env->SetDoubleArrayRegion(samples, 0, sampleCount, block);
    env->SetDoubleArrayRegion(state, 0, stateCount, history);
    return block[sampleCount - 1];
}
