package android.os;

import java.io.File;

/** 桌面替身：Update 下载目录走外部存储。 */
public class Environment {
    public static File getExternalStorageDirectory() {
        File d = new File("build/sdcard");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }
}
