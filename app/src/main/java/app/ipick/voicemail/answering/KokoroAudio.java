package app.ipick.voicemail.answering;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class KokoroAudio {
    public static final String HOST = "or3o-kokoro-fastapi.hf.space";
    private static final int LIMIT = 30 * 48000 * 2 + 8192;

    private KokoroAudio() {}

    public static final int TIMEOUT_SECONDS = 20;

    /** A cancellable network operation; it never writes greeting files. */
    public static final class Request {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) current.disconnect();
        }

        public byte[] execute(String json) throws IOException {
            if (cancelled) throw new InterruptedIOException("Cancelled");
            HttpURLConnection conn = (HttpURLConnection) new URL("https://" + HOST + "/v1/audio/speech").openConnection();
            connection = conn;
            if (cancelled) { conn.disconnect(); throw new InterruptedIOException("Cancelled"); }
            return fetch(conn, json);
        }
    }

    /** Blocking. The caller serializes the API request as UTF-8 JSON. */
    public static byte[] generate(String json) throws IOException {
        return new Request().execute(json);
    }

    private static byte[] fetch(HttpURLConnection conn, String json) throws IOException {
        try {
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(TIMEOUT_SECONDS * 1000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "audio/wav");
            conn.setDoOutput(true);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("Voice service returned HTTP " + code);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream wav = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int n;
                while ((n = in.read(block)) != -1) {
                    if (wav.size() + n > LIMIT) throw new IOException("Greeting too long");
                    wav.write(block, 0, n);
                }
                return decode(wav.toByteArray());
            }
        } finally { conn.disconnect(); }
    }

    /** Kokoro returns mono PCM WAV. Its streaming writer can leave RIFF chunk sizes at 0xffffffff. */
    public static byte[] decode(byte[] wav) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157)
            throw new IOException("Invalid WAV response");
        int rate = 0;
        for (int i = 12; i <= wav.length - 8;) {
            int tag = b.getInt(i), start = i + 8;
            long size = Integer.toUnsignedLong(b.getInt(i + 4));
            if (tag == 0x61746164 && size == 0xffffffffL) size = wav.length - start;
            if (size > wav.length - start) throw new IOException("Incomplete audio response");
            if (tag == 0x20746d66) {
                if (size < 16 || b.getShort(start) != 1 || b.getShort(start + 2) != 1 || b.getShort(start + 14) != 16)
                    throw new IOException("Unsupported audio encoding");
                rate = b.getInt(start + 4);
            } else if (tag == 0x61746164) {
                if ((rate != 24000 && rate != 48000) || size < rate / 2 || size % 2 != 0)
                    throw new IOException("Invalid audio samples");
                if (size > 30L * rate * 2) throw new IOException("Greeting exceeds 30 seconds");
                ByteBuffer out = ByteBuffer.allocate((int)size * (48000 / rate)).order(ByteOrder.LITTLE_ENDIAN);
                int peak = 0;
                for (int j = start; j < start + size; j += 2) {
                    short current = b.getShort(j);
                    peak = Math.max(peak, Math.abs((int)current));
                    out.putShort(current);
                    if (rate == 24000) {
                        short next = j + 2 < start + size ? b.getShort(j + 2) : current;
                        out.putShort((short)((current + next) / 2));
                    }
                }
                if (peak < 32) throw new IOException("Voice service returned silence");
                return out.array();
            }
            i = start + (int)size + (int)(size % 2);
        }
        throw new IOException("Missing WAV samples");
    }
}
