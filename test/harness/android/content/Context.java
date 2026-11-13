package android.content;

import java.io.File;

/** 桌面替身：只够 Update.download 编译通过。 */
public class Context {
    public File getCacheDir() {
        File d = new File("build/cache");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public Context getApplicationContext() {
        return this;
    }

    /** Trace 会往外部存储镜一份日志；桌面替身落到 build/ 下，别写进源码树。 */
    public File getExternalFilesDir(String type) {
        File d = new File("build/ext");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public File getFilesDir() {
        File d = new File("build/files");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public void startActivity(Intent it) {
        System.out.println("[stub] startActivity " + it);
    }
}
