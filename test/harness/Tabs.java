import com.yg.vodtv.Multi;
import com.yg.vodtv.Http;
import com.yg.vodtv.Src;
import com.yg.vodtv.Trace;
import com.yg.vodtv.Vod;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐个验证首页的每个分类（按统一频道号）在多源聚合下是否真能返回数据。
 * 空分类 = 遥控器点进去一片空白，是最容易被忽略的「能开但不好用」问题。
 */
public final class Tabs {

    private static final int[] CH = { Src.CH_REC, Src.CH_4K, Src.CH_ACTION, Src.CH_COMEDY,
            Src.CH_ROMANCE, Src.CH_SCIFI, Src.CH_HORROR, Src.CH_WAR, Src.CH_CNTV, Src.CH_WESTTV,
            Src.CH_KOREA, Src.CH_JAPAN, Src.CH_HK, Src.CH_TW, Src.CH_THAI, Src.CH_DOC,
            Src.CH_CNVAR, Src.CH_JPVAR, Src.CH_HKVAR, Src.CH_WESTVAR, Src.CH_CNANIME,
            Src.CH_JPANIME, Src.CH_WESTANIME, Src.CH_SUSPENSE };
    private static final String[] NAME = { "推荐", "4K电影", "动作", "喜剧", "爱情", "科幻", "恐怖",
            "战争", "国产剧", "欧美剧", "韩剧", "日剧", "港剧", "台剧", "泰剧", "纪录片", "大陆综艺",
            "日韩综艺", "港台综艺", "欧美综艺", "国产动漫", "日韩动漫", "欧美动漫", "悬疑剧" };

    public static void main(String[] args) throws Exception {
        Http.trustAll();
        int bad = 0;
        List<String> problems = new ArrayList<String>();
        int totalItems = 0;
        for (int i = 0; i < CH.length; i++) {
            long t0 = System.currentTimeMillis();
            Multi.Result p = Multi.list(CH[i], 1);
            long ms = System.currentTimeMillis() - t0;
            int n = p.items.size();
            int withPic = 0;
            for (int k = 0; k < n; k++) {
                Vod v = p.items.get(k);
                if (v.pic != null && v.pic.length() > 0) {
                    withPic++;
                }
            }
            totalItems += n;
            boolean ok = n > 0 && withPic > 0;
            if (!ok) {
                bad++;
                String why = n == 0 ? "空页" : "海报全空";
                problems.add(NAME[i] + "(" + CH[i] + ") " + why);
                System.out.println("[FAIL] " + pad(NAME[i]) + " ch=" + CH[i] + "  " + why);
            } else {
                System.out.println("[ OK ] " + pad(NAME[i]) + " ch=" + CH[i] + "  " + n
                        + " 条 / " + p.pagecount + " 页 / " + p.okSources + " 个源 / " + ms
                        + " ms，有海报 " + withPic);
            }
        }
        System.out.println();
        System.out.println("共聚合 " + totalItems + " 条（" + CH.length + " 个分类）");
        if (bad == 0) {
            System.out.println("[PASS] 全部分类都有数据且有海报");
        } else {
            System.out.println("[FAIL] " + bad + " 个分类有问题：" + problems);
        }
        System.out.println();
        System.out.println("---- Trace ----");
        System.out.println(Trace.tail(12));
        System.exit(bad == 0 ? 0 : 1);
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 8) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
