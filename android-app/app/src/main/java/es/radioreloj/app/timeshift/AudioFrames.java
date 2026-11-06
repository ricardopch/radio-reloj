package es.radioreloj.app.timeshift;

/** Lectura de cabeceras de tramas MP3 y AAC (ADTS) para cortar el audio en trozos exactos. */
final class AudioFrames {

    static final int FORMAT_MP3 = 1;
    static final int FORMAT_ADTS = 2;

    /** Datos de una trama: longitud en bytes, muestras que contiene y frecuencia de muestreo. */
    static final class Frame {
        final int format;
        final int length;
        final int samples;
        final int sampleRate;

        Frame(int format, int length, int samples, int sampleRate) {
            this.format = format;
            this.length = length;
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private static final int[] MPEG1_RATES = {44100, 48000, 32000};
    private static final int[][] BITRATES_V1 = {
            {32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}, // capa I
            {32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},    // capa II
            {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},     // capa III
    };
    private static final int[][] BITRATES_V2 = {
            {32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256}, // capa I
            {8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},      // capas II y III
    };
    private static final int[] ADTS_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private AudioFrames() {
    }

    /** Lee la cabecera en b[off..off+7]; devuelve null si ahí no empieza una trama válida. */
    static Frame parse(byte[] b, int off, int available) {
        if (available < 7) return null;
        int b0 = b[off] & 0xFF;
        int b1 = b[off + 1] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return null;
        int layerBits = (b1 >> 1) & 3;
        if (layerBits == 0) return parseAdts(b, off);
        return parseMpeg(b, off);
    }

    private static Frame parseAdts(byte[] b, int off) {
        int b1 = b[off + 1] & 0xFF;
        if ((b1 & 0xF0) != 0xF0) return null;
        int b2 = b[off + 2] & 0xFF;
        int sfIndex = (b2 >> 2) & 0x0F;
        if (sfIndex >= ADTS_RATES.length) return null;
        int length = ((b[off + 3] & 0x03) << 11) | ((b[off + 4] & 0xFF) << 3) | ((b[off + 5] & 0xE0) >> 5);
        boolean protectionAbsent = (b1 & 1) == 1;
        if (length < (protectionAbsent ? 7 : 9)) return null;
        int blocks = (b[off + 6] & 0x03) + 1;
        return new Frame(FORMAT_ADTS, length, 1024 * blocks, ADTS_RATES[sfIndex]);
    }

    private static Frame parseMpeg(byte[] b, int off) {
        int b1 = b[off + 1] & 0xFF;
        int b2 = b[off + 2] & 0xFF;
        int version = (b1 >> 3) & 3; // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5, 1 = reservado
        int layerBits = (b1 >> 1) & 3; // 3 = I, 2 = II, 1 = III
        if (version == 1) return null;
        int layer = 4 - layerBits; // 1, 2 o 3
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int rateIndex = (b2 >> 2) & 3;
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null;
        int padding = (b2 >> 1) & 1;

        int sampleRate = MPEG1_RATES[rateIndex];
        if (version == 2) sampleRate /= 2;
        else if (version == 0) sampleRate /= 4;

        int kbps;
        if (version == 3) kbps = BITRATES_V1[layer - 1][bitrateIndex - 1];
        else kbps = BITRATES_V2[layer == 1 ? 0 : 1][bitrateIndex - 1];
        int bitrate = kbps * 1000;

        int length;
        int samples;
        if (layer == 1) {
            length = (12 * bitrate / sampleRate + padding) * 4;
            samples = 384;
        } else if (layer == 2) {
            length = 144 * bitrate / sampleRate + padding;
            samples = 1152;
        } else if (version == 3) {
            length = 144 * bitrate / sampleRate + padding;
            samples = 1152;
        } else {
            length = 72 * bitrate / sampleRate + padding;
            samples = 576;
        }
        if (length < 4) return null;
        return new Frame(FORMAT_MP3, length, samples, sampleRate);
    }

    /**
     * Etiqueta ID3 con la marca de tiempo que HLS exige al principio de cada trozo de audio
     * "empaquetado" (AAC o MP3 sin contenedor).
     */
    static byte[] id3Timestamp(long pts90k) {
        byte[] owner = "com.apple.streaming.transportStreamTimestamp".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int frameBody = owner.length + 1 + 8;
        int tagBody = 10 + frameBody;
        byte[] out = new byte[10 + tagBody];
        int p = 0;
        out[p++] = 'I';
        out[p++] = 'D';
        out[p++] = '3';
        out[p++] = 4;
        out[p++] = 0;
        out[p++] = 0;
        p = putSynchsafe(out, p, tagBody);
        out[p++] = 'P';
        out[p++] = 'R';
        out[p++] = 'I';
        out[p++] = 'V';
        p = putSynchsafe(out, p, frameBody);
        out[p++] = 0;
        out[p++] = 0;
        System.arraycopy(owner, 0, out, p, owner.length);
        p += owner.length;
        out[p++] = 0;
        long ts = pts90k & 0x1FFFFFFFFL;
        for (int i = 7; i >= 0; i--) out[p++] = (byte) (ts >>> (8 * i));
        return out;
    }

    private static int putSynchsafe(byte[] out, int p, int value) {
        out[p++] = (byte) ((value >> 21) & 0x7F);
        out[p++] = (byte) ((value >> 14) & 0x7F);
        out[p++] = (byte) ((value >> 7) & 0x7F);
        out[p++] = (byte) (value & 0x7F);
        return p;
    }
}
