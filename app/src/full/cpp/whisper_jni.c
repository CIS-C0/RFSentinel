// JNI bridge for WhisperNative.kt: load a model, transcribe 16 kHz mono float samples, free the model.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "RfsWhisper"

JNIEXPORT jlong JNICALL
Java_com_rfsentinel_app_sdr_WhisperNative_load(JNIEnv *env, jobject thiz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return 0;
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (ctx == NULL) __android_log_print(ANDROID_LOG_WARN, TAG, "model failed to load");
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jstring JNICALL
Java_com_rfsentinel_app_sdr_WhisperNative_transcribe(JNIEnv *env, jobject thiz, jlong handle, jfloatArray samples, jint threads) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    if (ctx == NULL || samples == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (pcm == NULL) return NULL;

    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "en";
    wp.translate = false;
    wp.n_threads = threads;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.suppress_blank = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;

    int rc = whisper_full(ctx, wp, pcm, n);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (rc != 0) { __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full failed: %d", rc); return NULL; }

    int segs = whisper_full_n_segments(ctx);
    size_t cap = 1, len = 0;
    for (int i = 0; i < segs; i++) cap += strlen(whisper_full_get_segment_text(ctx, i));
    char *out = malloc(cap);
    if (out == NULL) return NULL;
    out[0] = 0;
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        size_t l = strlen(t);
        memcpy(out + len, t, l);
        len += l;
    }
    out[len] = 0;
    jstring s = (*env)->NewStringUTF(env, out);
    free(out);
    return s;
}

JNIEXPORT void JNICALL
Java_com_rfsentinel_app_sdr_WhisperNative_free(JNIEnv *env, jobject thiz, jlong handle) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    if (ctx != NULL) whisper_free(ctx);
}
