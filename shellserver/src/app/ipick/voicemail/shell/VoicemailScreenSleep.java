package app.ipick.voicemail.shell;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

final class VoicemailScreenSleep {
    private static final int SLEEP_REASON_APPLICATION = 0;
    private static final int SLEEP_FLAG_NO_DOZE = 1;
    private PowerManager.WakeLock audioWakeLock;

    boolean start() {
        if (audioWakeLock != null) return true;
        try {
            PowerManager power = (PowerManager) FakeContext.get().getSystemService(Context.POWER_SERVICE);
            if (power == null) return false;
            audioWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipick:voicemail-audio");
            audioWakeLock.setReferenceCounted(false);
            audioWakeLock.acquire();
            PowerManager.class.getMethod("goToSleep", long.class, int.class, int.class)
                    .invoke(power, SystemClock.uptimeMillis(), SLEEP_REASON_APPLICATION, SLEEP_FLAG_NO_DOZE);
            return true;
        } catch (Exception e) {
            Ln.w("voicemail: screen sleep unavailable: " + e);
            stop();
            return false;
        }
    }

    void stop() {
        PowerManager.WakeLock lock = audioWakeLock;
        audioWakeLock = null;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (RuntimeException e) {
            Ln.w("voicemail: could not release audio wake lock: " + e);
        }
    }
}
