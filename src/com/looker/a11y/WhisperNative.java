package com.looker.a11y;

/** JNI binding to whisper.cpp (libwhisper.so, built from cpp/). */
final class WhisperNative {
    static {
        System.loadLibrary("whisper");
    }

    private WhisperNative() {
    }

    static native long initContext(String modelPath);

    static native String transcribe(long ctx, int threads, float[] audio, String language, String prompt);

    static native void freeContext(long ctx);
}
