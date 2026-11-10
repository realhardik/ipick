package app.ipick.voicemail.shortcuts;

/** One physical hold belongs to one ringing-call token and one input device. */
public final class VolumeHold {
    public static final long HOLD_MS = 1000;
    private String token;
    private long pressedAt;
    private int device;
    private boolean fired;

    public boolean down(String currentToken, long time, int deviceId, int repeats) {
        if (currentToken == null || repeats != 0 || token != null) return false;
        token = currentToken;
        pressedAt = time;
        device = deviceId;
        fired = false;
        return true;
    }

    public String fire(String currentToken, long now) {
        if (token == null || fired || !token.equals(currentToken) || now - pressedAt < HOLD_MS) return null;
        fired = true;
        return token;
    }

    public void up(int deviceId) { if (deviceId == device) cancel(); }
    public void cancel() { token = null; fired = false; }
}
