package com.itemikhaan.nabu;

/** XXTEA with big-endian words, same as Razer's app. Data length must be a multiple of 4 (we use 16). */
public final class Xxtea {
    private Xxtea() {}

    private static int[] words(byte[] b) {
        int[] w = new int[b.length / 4];
        for (int i = 0; i < w.length; i++) {
            w[i] = ((b[4 * i] & 255) << 24) | ((b[4 * i + 1] & 255) << 16)
                    | ((b[4 * i + 2] & 255) << 8) | (b[4 * i + 3] & 255);
        }
        return w;
    }

    private static byte[] bytes(int[] w) {
        byte[] o = new byte[w.length * 4];
        for (int i = 0; i < w.length; i++) {
            o[4 * i] = (byte) (w[i] >>> 24);
            o[4 * i + 1] = (byte) (w[i] >>> 16);
            o[4 * i + 2] = (byte) (w[i] >>> 8);
            o[4 * i + 3] = (byte) w[i];
        }
        return o;
    }

    public static byte[] encrypt(byte[] data, byte[] key) {
        int[] v = words(data);
        int[] k = words(key);
        int n = v.length;
        int rounds = 6 + 52 / n;
        int sum = 0;
        int z = v[n - 1];
        int y, e, p, mx;
        while (rounds-- > 0) {
            sum += 0x9e3779b9;
            e = (sum >>> 2) & 3;
            for (p = 0; p < n - 1; p++) {
                y = v[p + 1];
                mx = (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4))) ^ ((sum ^ y) + (k[(p & 3) ^ e] ^ z));
                z = v[p] += mx;
            }
            y = v[0];
            mx = (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4))) ^ ((sum ^ y) + (k[(p & 3) ^ e] ^ z));
            z = v[n - 1] += mx;
        }
        return bytes(v);
    }
}
