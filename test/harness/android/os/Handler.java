package android.os;

/** 桌面替身：Update.check 的回调切主线程，桌面上直接同步跑。 */
public class Handler {
    public Handler(Looper l) {
    }

    public boolean post(Runnable r) {
        r.run();
        return true;
    }

    public void removeCallbacksAndMessages(Object o) {
    }
}
