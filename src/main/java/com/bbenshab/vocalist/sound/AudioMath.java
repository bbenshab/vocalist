package com.bbenshab.vocalist.sound;

/** WAV reading/writing and resampling. Mono 16-bit PCM everywhere. */
public final class AudioMath {

    private AudioMath() {
    }

    public record Clip(short[] samples, int sampleRate) {
    }

    /** Wrap raw PCM in a canonical 44-byte-header WAV container. */
    public static byte[] toWav(short[] pcm, int sampleRate) {
        int dataBytes = pcm.length * 2;
        byte[] out = new byte[44 + dataBytes];
        writeAscii(out, 0, "RIFF");
        writeIntLE(out, 4, 36 + dataBytes);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeIntLE(out, 16, 16);
        writeShortLE(out, 20, (short) 1);        // PCM
        writeShortLE(out, 22, (short) 1);        // mono
        writeIntLE(out, 24, sampleRate);
        writeIntLE(out, 28, sampleRate * 2);     // byte rate
        writeShortLE(out, 32, (short) 2);        // block align
        writeShortLE(out, 34, (short) 16);       // bits
        writeAscii(out, 36, "data");
        writeIntLE(out, 40, dataBytes);
        for (int i = 0; i < pcm.length; i++) {
            writeShortLE(out, 44 + i * 2, pcm[i]);
        }
        return out;
    }

    /** Parse a WAV by walking its chunks instead of trusting fixed offsets. */
    public static Clip fromWav(byte[] wav) {
        if (wav.length < 12 || readAscii(wav, 0, 4) != 0x52494646) { // "RIFF"
            throw new IllegalArgumentException("not a RIFF file");
        }
        int pos = 12;
        int rate = 48000;
        short[] samples = new short[0];
        while (pos + 8 <= wav.length) {
            String chunkId = new String(wav, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = readIntLE(wav, pos + 4);
            int body = pos + 8;
            if ("fmt ".equals(chunkId) && chunkSize >= 16) {
                rate = readIntLE(wav, body + 4);
            } else if ("data".equals(chunkId) && chunkSize > 0) {
                int usable = Math.min(chunkSize, wav.length - body) & ~1;
                samples = new short[usable / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) ((wav[body + i * 2] & 0xFF) | (wav[body + i * 2 + 1] << 8));
                }
            }
            pos = body + chunkSize + (chunkSize & 1);
        }
        return new Clip(samples, rate);
    }

    /** Linear-interpolation resampler. */
    public static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate || input.length == 0) {
            return input;
        }
        double step = (double) fromRate / toRate;
        int outLen = (int) (input.length / step);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i * step;
            int i0 = (int) src;
            double frac = src - i0;
            int i1 = Math.min(i0 + 1, input.length - 1);
            out[i] = (short) Math.round(input[i0] * (1.0 - frac) + input[i1] * frac);
        }
        return out;
    }

    public static short[] bytesToPcm(byte[] littleEndian) {
        int n = littleEndian.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((littleEndian[i * 2] & 0xFF) | (littleEndian[i * 2 + 1] << 8));
        }
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static long readAscii(byte[] b, int off, int len) {
        long v = 0;
        for (int i = len - 1; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    private static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void writeShortLE(byte[] b, int off, short v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
