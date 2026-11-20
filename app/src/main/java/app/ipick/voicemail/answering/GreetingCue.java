package app.ipick.voicemail.answering;

import java.util.Arrays;

public final class GreetingCue {
    private static final int RATE = 48_000;
    private static final int GAP_FRAMES = RATE * 160 / 1000;
    private static final int BEEP_FRAMES = RATE * 400 / 1000;
    private static final byte[] ENDING = makeEnding();

    private GreetingCue() {}

    public static byte[] appendTo(byte[] voice) {
        int length = voice.length & ~1;
        byte[] result = Arrays.copyOf(voice, length + ENDING.length);
        System.arraycopy(ENDING, 0, result, length, ENDING.length);
        return result;
    }

    private static byte[] makeEnding() {
        byte[] pcm = new byte[(GAP_FRAMES + BEEP_FRAMES) * 2];
        for (int i = 0; i < BEEP_FRAMES; i++) {
            double fade = Math.min(1.0, Math.min(i, BEEP_FRAMES - 1 - i) / (RATE * 0.01));
            short sample = (short) (6000 * fade * Math.sin(2 * Math.PI * 900 * i / RATE));
            int index = 2 * (GAP_FRAMES + i);
            pcm[index] = (byte) sample;
            pcm[index + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
}
