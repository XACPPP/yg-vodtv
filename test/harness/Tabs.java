import com.yg.vodtv.Api;
import com.yg.vodtv.Http;
import com.yg.vodtv.Vod;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐个验证首页 27 个分类标签是否真的能返回数据。
 * 空分类 = 遥控器点进去一片空白，是最容易被忽略的「能开但不好用」问题。
 */
public final class Tabs {

    private static final int[] TAB_TYPE = { 0, 62, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 25, 26, 27,
            28, 29, 30, 31, 39, 65, 69, 76 };
    private static final String[] TAB_NAME = { "推荐", "4K电影", "动作", "喜剧", "爱情", "科幻", "恐怖", "战争", "国产剧",
            "欧美剧", "韩剧", "日剧", "港剧", "台剧", "泰剧", "纪录片", "大陆综艺", "日韩综艺", "港台综艺", "欧美综艺", "国产动漫",
            "日韩动漫", "欧美动漫", "动画片", "反转爽剧", "现代都市", "短剧" };

    public static void main(String[] args) throws Exception {
        Http.trustAll();
        int bad = 0;
        List<String> problems = new ArrayList<String>();
        for (int i = 0; i < TAB_TYPE.length; i++) {
            int t = TAB_TYPE[i];
            Api.Page p = Api.list(t, 1);
            int n = p.items.size();
            int withPic = 0;
            for (int k = 0; k < p.items.size(); k++) {
                Vod v = p.items.get(k);
                if (v.pic != null && v.pic.length() > 0) {
                    withPic++;
                }
            }
            boolean ok = n > 0 && withPic > 0;
            if (!ok) {
                bad++;
                String why = n == 0 ? "空页" : "海报全空";
                problems.add(TAB_NAME[i] + "(" + t + ") " + why);
                System.out.println("[FAIL] " + pad(TAB_NAME[i]) + " t=" + t + "  " + why);
            } else {
                System.out.println("[ OK ] " + pad(TAB_NAME[i]) + " t=" + t + "  " + n + " 条 / " + p.pagecount
                        + " 页 / " + p.total + " 部，有海报 " + withPic);
            }
        }
        System.out.println();
        if (bad == 0) {
            System.out.println("[PASS] 27 个分类全部有数据且有海报");
        } else {
            System.out.println("[FAIL] " + bad + " 个分类有问题：" + problems);
        }
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
