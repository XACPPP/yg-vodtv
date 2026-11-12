package android.content;

/** 桌面替身：只够 Update.install 编译通过。 */
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;

    private Object data;
    private String type;
    private int flags;

    public Intent(String action) {
    }

    public Intent setDataAndType(Object data, String type) {
        this.data = data;
        this.type = type;
        return this;
    }

    public Intent addFlags(int flags) {
        this.flags |= flags;
        return this;
    }

    public String toString() {
        return "Intent{" + data + ", " + type + "}";
    }
}
