package com.headqlink.unit.companion.cloud;

/**
 * Puente mínimo entre la sesión portada de Qonecta+ (CarCloudSession) y Companion: el último estado leído de la nube
 * (para el clima automático de las órdenes) y los avisos de «algo cambió en la cuenta».
 */
final class CarCloud {
    static final class Snapshot {
        final LeapStatus status;
        final boolean demo = false;

        Snapshot(LeapStatus status) {
            this.status = status;
        }

        boolean hasData() {
            return status != null;
        }
    }

    private static volatile LeapStatus last;
    private static volatile Runnable listener;

    private CarCloud() {
    }

    static Snapshot snapshot() {
        return new Snapshot(last);
    }

    static void publish(LeapStatus s) {
        last = s;
    }

    static void setListener(Runnable r) {
        listener = r;
    }

    /** Sesión, coche o certificado cambiados. */
    static void settingsChanged() {
        CarCloudSession.invalidate();
        refreshUi();
    }

    static void refreshUi() {
        Runnable r = listener;
        if (r != null) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static String safeError(Throwable e) {
        return Http.safeError(e);
    }
}
