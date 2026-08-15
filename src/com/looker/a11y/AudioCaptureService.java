package com.looker.a11y;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Captures another app's audio (Chrome's, i.e. the audio-captcha playback) via
 * MediaProjection {@link AudioPlaybackCaptureConfiguration} and writes it to a
 * 16 kHz mono WAV — the input for a speech recogniser.
 *
 * MediaProjection needs a one-time user consent (obtained in MainActivity); its
 * token is stashed in {@link #resultCode}/{@link #resultData}.
 */
public class AudioCaptureService extends Service {

    private static final String TAG = "AudioCap";
    private static final int SAMPLE_RATE = 16000;

    // Consent token from MainActivity.
    static volatile int resultCode;
    static volatile Intent resultData;

    // Per-capture coordination for the socket command.
    static volatile CountDownLatch doneLatch;
    static volatile String lastWav;
    static volatile String lastError;

    static boolean hasConsent() {
        return resultData != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        final String path = intent != null ? intent.getStringExtra("path") : null;
        final int secs = intent != null ? intent.getIntExtra("secs", 6) : 6;
        new Thread(() -> record(path, secs)).start();
        return START_NOT_STICKY;
    }

    private void startFg() {
        String ch = "audiocap";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(ch, "Audio capture",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Capturing captcha audio")
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, n);
        }
    }

    private void record(String path, int secs) {
        AudioRecord rec = null;
        MediaProjection mp = null;
        try {
            if (path == null) throw new IllegalStateException("no path");
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            mp = mpm.getMediaProjection(resultCode, resultData);
            if (mp == null) throw new IllegalStateException("no MediaProjection (consent missing?)");

            AudioPlaybackCaptureConfiguration cfg =
                    new AudioPlaybackCaptureConfiguration.Builder(mp)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buf = new byte[minBuf];
            rec.startRecording();
            long end = SystemClock.elapsedRealtime() + secs * 1000L;
            while (SystemClock.elapsedRealtime() < end) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) pcm.write(buf, 0, n);
            }
            rec.stop();
            writeWav(new File(path), pcm.toByteArray());
            lastWav = path;
            Log.i(TAG, "captured " + pcm.size() + " PCM bytes -> " + path);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.e(TAG, "capture failed", t);
        } finally {
            if (rec != null) try {
                rec.release();
            } catch (Throwable ignored) {
            }
            if (mp != null) try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            if (doneLatch != null) doneLatch.countDown();
        }
    }

    private void writeWav(File f, byte[] pcm) throws Exception {
        int rate = SAMPLE_RATE, ch = 1, bits = 16;
        int byteRate = rate * ch * bits / 8;
        int dataLen = pcm.length;
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[]{'R', 'I', 'F', 'F'});
            o.write(intLE(36 + dataLen));
            o.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            o.write(intLE(16));
            o.write(shortLE(1));            // PCM
            o.write(shortLE(ch));
            o.write(intLE(rate));
            o.write(intLE(byteRate));
            o.write(shortLE(ch * bits / 8));
            o.write(shortLE(bits));
            o.write(new byte[]{'d', 'a', 't', 'a'});
            o.write(intLE(dataLen));
            o.write(pcm);
        }
    }

    private static byte[] intLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] shortLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }
}
