package myau.inject;

import myau.Myau;

public final class Bootstrap {
    private static volatile boolean requested;
    private static volatile boolean started;
    private Bootstrap() {
    }
    public static void requestStart() {
        requested = true;
    }

    public static void tick() {
        if (!requested || started) {
            return;
        }
        started = true;
        try {
            log("constructing client on the game thread");
            new Myau();
            log("client ready");
        } catch (Throwable t) {
            Log.throwable("client failed to start", t);
        }
    }
    public static boolean isStarted() {
        return started;
    }
    private static void log(String message) {
        Log.line(message);
    }
}
