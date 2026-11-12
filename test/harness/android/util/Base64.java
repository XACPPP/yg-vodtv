package android.util;

/**
 * 仅用于在桌面 JVM 上编译/运行 Proxy.java 的最小替身。
 * 行为与 Android 的 Base64 对齐（URL_SAFE + NO_WRAP + NO_PADDING）。
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    private Base64() {
    }

    public static String encodeToString(byte[] input, int flags) {
        java.util.Base64.Encoder e = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) {
            e = e.withoutPadding();
        }
        return e.encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        java.util.Base64.Decoder d = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getDecoder();
        return d.decode(str);
    }
}
