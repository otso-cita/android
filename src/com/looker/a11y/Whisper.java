package com.looker.a11y;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

/**
 * On-device speech-to-text for the audio captcha, via whisper.cpp
 * ({@link WhisperNative}). Downloads the medium model once (only medium is
 * accurate enough — smaller models confuse b/v and digits), decodes the
 * downloaded MP3 to 16 kHz mono float, and transcribes with Spanish + a
 * spell-out prompt so the letters come out right.
 */
final class Whisper {

    private static final String TAG = "Whisper";
    static final String MODEL_FILE = "ggml-medium-q5_0.bin";
    static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin";
    // Priming whisper with example uppercase letters (NOT in any given captcha)
    // resolves the Spanish b/v homophone so "b" isn't heard as "v". Verified to
    // read the captchas exactly with the medium model.
    static final String PROMPT =
            "Captcha con letras mayúsculas y dígitos, por ejemplo A F G M R 5 9. Escribe el código:";

    private static volatile long ctx = 0;
    private static volatile String status = "idle";

    private Whisper() {
    }

    static String status() {
        return status;
    }

    static File modelFile(Context c) {
        // Prefer an adb-pushed copy (fast to provision for testing); otherwise the
        // app's own external files dir (where the in-app download lands).
        File dev = new File("/data/local/tmp/" + MODEL_FILE);
        if (dev.exists() && dev.length() > 100_000_000L) return dev;
        return new File(c.getExternalFilesDir(null), MODEL_FILE);
    }

    static boolean modelPresent(Context c) {
        File f = modelFile(c);
        return f.exists() && f.length() > 100_000_000L;
    }

    /** Download the model if missing. Blocking; run off the main thread. */
    static synchronized boolean ensureModel(Context c) {
        if (modelPresent(c)) return true;
        File out = modelFile(c);
        File tmp = new File(out.getPath() + ".part");
        HttpURLConnection conn = null;
        try {
            status = "downloading model";
            conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            int total = conn.getContentLength();
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 1 << 16);
                 FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    fo.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct;
                            status = "downloading model " + pct + "%";
                            Log.i(TAG, status);
                        }
                    }
                }
            }
            if (tmp.renameTo(out)) {
                status = "model ready";
                return true;
            }
            status = "rename failed";
            return false;
        } catch (Throwable t) {
            status = "download failed: " + t;
            Log.e(TAG, "model download failed", t);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static synchronized boolean ensureLoaded(Context c) {
        if (ctx != 0) return true;
        if (!ensureModel(c)) return false;
        status = "loading model";
        ctx = WhisperNative.initContext(modelFile(c).getPath());
        status = ctx != 0 ? "ready" : "load failed";
        return ctx != 0;
    }

    /** Transcribe an audio file (mp3/wav/etc.) to the captcha string (alnum, lowercase). */
    static synchronized String readCaptcha(Context c, String audioPath) {
        if (!ensureLoaded(c)) return null;
        try {
            float[] pcm = decodeTo16kMono(audioPath);
            if (pcm == null || pcm.length == 0) return null;
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            String raw = WhisperNative.transcribe(ctx, threads, pcm, "es", PROMPT);
            Log.i(TAG, "raw transcription: [" + raw + "]");
            if (raw == null) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char ch = raw.charAt(i);
                if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "readCaptcha failed", t);
            return null;
        }
    }

    /** Newest audio file in /sdcard/Download modified after `afterMillis`, or
     *  null. Used to pick up the captcha audio the browser just downloaded. */
    static File newestDownloadAudio(long afterMillis) {
        File dir = new File("/sdcard/Download");
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg")
                    || n.endsWith(".m4a"))) continue;
            if (f.lastModified() <= afterMillis) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    // ---------------- audio decode: any format -> 16 kHz mono float [-1,1] ----------------

    private static float[] decodeTo16kMono(String path) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                track = i;
                fmt = f;
                break;
            }
        }
        if (track < 0) {
            ex.release();
            return null;
        }
        ex.selectTrack(track);
        int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        ArrayList<Short> mono = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false;
        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(inIdx);
                    int sz = ex.readSampleData(ib, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(outIdx);
                ob.order(ByteOrder.LITTLE_ENDIAN);
                short[] s = new short[info.size / 2];
                ob.asShortBuffer().get(s);
                // downmix to mono
                for (int i = 0; i + channels - 1 < s.length; i += channels) {
                    int sum = 0;
                    for (int ch = 0; ch < channels; ch++) sum += s[i + ch];
                    mono.add((short) (sum / channels));
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }
        codec.stop();
        codec.release();
        ex.release();

        // resample srcRate -> 16000 (linear)
        int dstRate = 16000;
        int nSrc = mono.size();
        int nDst = (int) ((long) nSrc * dstRate / srcRate);
        float[] out = new float[nDst];
        for (int i = 0; i < nDst; i++) {
            double srcPos = (double) i * srcRate / dstRate;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, nSrc - 1);
            double frac = srcPos - i0;
            double v = mono.get(i0) * (1 - frac) + mono.get(i1) * frac;
            out[i] = (float) (v / 32768.0);
        }
        Log.i(TAG, String.format(Locale.US, "decoded %d src@%dHz/%dch -> %d @16k mono",
                nSrc, srcRate, channels, nDst));
        return out;
    }
}
