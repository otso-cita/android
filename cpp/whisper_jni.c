#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_looker_a11y_WhisperNative_initContext(JNIEnv *env, jclass c, jstring path) {
    (void) c;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    LOG("initContext -> %p", (void *) ctx);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jstring JNICALL
Java_com_looker_a11y_WhisperNative_transcribe(JNIEnv *env, jclass c, jlong ctxPtr,
                                              jint threads, jfloatArray audio,
                                              jstring lang, jstring prompt) {
    (void) c;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ctxPtr;
    jfloat *a = (*env)->GetFloatArrayElements(env, audio, NULL);
    jsize n = (*env)->GetArrayLength(env, audio);
    const char *l = (*env)->GetStringUTFChars(env, lang, NULL);
    const char *pr = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = l;
    params.n_threads = threads;
    params.no_context = false;   // keep prompt tokens in context
    params.single_segment = false;
    params.temperature = 0.0f;
    params.suppress_blank = false;
    if (pr && pr[0]) params.initial_prompt = pr;

    char *out = NULL;
    size_t outlen = 0;
    if (whisper_full(ctx, params, a, n) == 0) {
        int ns = whisper_full_n_segments(ctx);
        for (int i = 0; i < ns; i++) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            if (!t) continue;
            size_t tl = strlen(t);
            char *nb = realloc(out, outlen + tl + 1);
            if (!nb) break;
            out = nb;
            memcpy(out + outlen, t, tl);
            outlen += tl;
            out[outlen] = '\0';
        }
    } else {
        LOG("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio, a, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, lang, l);
    if (pr) (*env)->ReleaseStringUTFChars(env, prompt, pr);

    jstring res = (*env)->NewStringUTF(env, out ? out : "");
    free(out);
    return res;
}

JNIEXPORT void JNICALL
Java_com_looker_a11y_WhisperNative_freeContext(JNIEnv *env, jclass c, jlong ctxPtr) {
    (void) env;
    (void) c;
    if (ctxPtr) whisper_free((struct whisper_context *) (intptr_t) ctxPtr);
}
