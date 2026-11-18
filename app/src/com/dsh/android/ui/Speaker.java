package com.dsh.android.ui;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * 系统 TTS 的薄封装。
 *
 * <p>用 framework 自带 TextToSpeech，不引入任何第三方 SDK。
 * 初始化是异步的：传进来的请求在就绪前排队，就绪后立刻朗读。
 */
public final class Speaker {

    private static TextToSpeech tts;
    private static boolean ready = false;
    private static boolean failed = false;
    private static String pending;

    private Speaker() {}

    /** 应用启动时调用一次即可。 */
    public static void init(final Context ctx) {
        if (tts != null || failed) return;
        try {
            tts = new TextToSpeech(ctx.getApplicationContext(),
                    new TextToSpeech.OnInitListener() {
                        public void onInit(int status) {
                            if (status != TextToSpeech.SUCCESS) {
                                failed = true;
                                return;
                            }
                            try {
                                Locale loc = tts.getDefaultLanguage();
                                if (loc != null) tts.setLanguage(loc);
                            } catch (Throwable ignored) {}
                            ready = true;
                            if (pending != null) {
                                String p = pending;
                                pending = null;
                                speak(p);
                            }
                        }
                    });
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onDone(String id) {}
                public void onError(String id) {}
            });
        } catch (Throwable t) {
            failed = true;
        }
    }

    /** 是否可用（设备可能没有可用的语音引擎）。 */
    public static boolean available() {
        return !failed;
    }

    /** 朗读一段文字；超长内容截断，避免引擎报错。 */
    public static void speak(String text) {
        if (text == null || text.trim().length() == 0) return;
        if (failed) return;
        String body = text.trim();
        if (body.length() > 4000) body = body.substring(0, 4000);
        if (!ready || tts == null) {
            pending = body;
            return;
        }
        try {
            stop();
            tts.speak(body, TextToSpeech.QUEUE_FLUSH, null, "dsh-" + System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    public static void stop() {
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {}
        tts = null;
        ready = false;
    }
}
