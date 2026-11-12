import java.util.List;

/** 临时验证：用 APK 里的真实 Multi.search 查几个当前热播剧，看能不能搜到。 */
public final class Search {

    public static void main(String[] args) throws Exception {
        com.yg.vodtv.Http.trustAll();
        String[] words = args.length > 0 ? args
                : new String[] { "小城良方", "兰香如故", "逐玉", "许我耀眼", "深渊无间", "南部档案", "xiaochengliangfang" };
        for (String kw : words) {
            long t = System.currentTimeMillis();
            List<com.yg.vodtv.Vod> out = com.yg.vodtv.Multi.search(kw);
            long ms = System.currentTimeMillis() - t;
            System.out.println("=== " + kw + "  → " + out.size() + " 条 / " + ms + "ms");
            int n = Math.min(5, out.size());
            for (int i = 0; i < n; i++) {
                com.yg.vodtv.Vod v = out.get(i);
                System.out.println("   " + (i + 1) + ". " + v.name
                        + "  [src=" + v.src + " id=" + v.id
                        + " 集=" + v.epUrls.length
                        + " 简介=" + (v.intro().length() > 0 ? "有" : "无")
                        + " 海报=" + (v.pic.length() > 0 ? "有" : "无") + "]");
            }
        }
        System.out.println();
        System.out.println("---- Trace ----");
        System.out.println(com.yg.vodtv.Trace.tail(24));
    }
}
