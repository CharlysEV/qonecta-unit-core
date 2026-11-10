package com.headqlink.unit.companion.cloud;

import android.content.Context;
import android.util.Log;

/** Registro de la nube de Leapmotor en Companion (logcat). Sin secretos: lo que llega aquí ya viene limpio. */
final class L {
    private static final String TAG = "QonectaCloud";

    private L() {
    }

    static void init(Context ignored) {
        // En Companion basta con logcat.
    }

    static void i(String msg) {
        Log.i(TAG, msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
    }
}
