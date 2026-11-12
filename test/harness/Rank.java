import com.yg.vodtv.Api;
import com.yg.vodtv.Http;
import com.yg.vodtv.Vod;

import java.util.List;

/** 验证 Api.suggest 的贴合度排序：搜全拼时，正主应该排在「返还」类噪声前面。 */
public final class Rank {

    public static void main(String[] a) {
        Http.trustAll();
        int bad = 0;
        bad += check("fanhua", new String[]{"繁花"}, 3);
        bad += check("qingyunian", new String[]{"庆余年"}, 3);
        bad += check("kuangbiao", new String[]{"狂飙"}, 3);
        bad += check("sanguo", new String[]{"三国"}, 3);
        System.out.println(bad == 0 ? "[PASS] 搜索结果排序符合预期" : "[FAIL] 有 " + bad + " 组排序不理想");
        System.exit(bad == 0 ? 0 : 1);
    }

    /** 要求前 topN 条里至少出现一次期望词。 */
    private static int check(String kw, String[] want, int topN) {
        List<Vod> list = Api.suggest(kw);
        System.out.println("=== " + kw + "  " + list.size() + " 条");
        for (int i = 0; i < list.size() && i < 6; i++) {
            Vod v = list.get(i);
            System.out.println("   " + (i + 1) + "  " + v.id + "  " + v.name + "   [" + v.en + "]");
        }
        int n = Math.min(topN, list.size());
        for (int i = 0; i < n; i++) {
            String nm = list.get(i).name;
            for (String w : want) {
                if (nm != null && nm.startsWith(w)) {
                    System.out.println("   [ OK ] 前 " + topN + " 条内含「" + w + "」，第 " + (i + 1) + " 位");
                    return 0;
                }
            }
        }
        System.out.println("   [WARN] 前 " + topN + " 条里没有以 " + java.util.Arrays.toString(want) + " 开头的片名");
        return 1;
    }
}
