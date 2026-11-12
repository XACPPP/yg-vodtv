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

    public void startActivity(Intent it) {
        System.out.println("[stub] startActivity " + it);
    }
}
