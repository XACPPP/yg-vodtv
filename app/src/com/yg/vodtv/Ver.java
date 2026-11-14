package com.yg.vodtv;

/** 版本信息：标题栏会显示，用来确认电视上跑的是哪一版。 */
public final class Ver {

    public static final String NAME = "2.21";

    /** 每次出包手动改这一行。 */
    public static final String BUILD = "2026-11-14 11:40";

    public static final String FULL = "v" + NAME + " (" + BUILD + ")";

    /** 短标签，给首页/底栏这种位置用（"v2.21"）。 */
    public static String label() {
        return "v" + NAME;
    }

    private Ver() {
    }
}
