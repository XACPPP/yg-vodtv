package android.util;

/**
 * 桌面替身：Trace.add 会同时往 logcat 打一份，harness 里没有真 logcat，
 * 这里直接打到 stdout，顺便让「打点有没有漏」在测试输出里肉眼可见。
 */
public final class Log {

    private Log() {
    }

    public static int v(String tag, String msg) {
        return i(tag, msg);
    }

    public static int d(String tag, String msg) {
        return i(tag, msg);
    }

    public static int i(String tag, String msg) {
        System.out.println("[" + tag + "] " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        return i(tag, msg);
    }

    public static int e(String tag, String msg) {
        return i(tag, msg);
    }

    public static int e(String tag, String msg, Throwable t) {
        return i(tag, msg + " : " + t);
    }
}
