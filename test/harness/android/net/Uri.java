package android.net;

import java.io.File;

/** 桌面替身：Update.install 用 Uri.fromFile。 */
public final class Uri {
    private final String s;

    private Uri(String s) {
        this.s = s;
    }

    public static Uri fromFile(File f) {
        return new Uri("file://" + f.getAbsolutePath());
    }

    public static Uri parse(String s) {
        return new Uri(s);
    }

    public String toString() {
        return s;
    }
}
