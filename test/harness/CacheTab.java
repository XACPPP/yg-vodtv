import android.content.Context;

import com.yg.vodtv.Cache;
import com.yg.vodtv.Multi;
import com.yg.vodtv.Vod;

import java.util.List;

/**
 * v2.4 列表缓存的离线验证：`Multi.encode` / `Multi.decode` 往返 + `Cache` 落盘。
 *
 * 为什么要单独测：这两个方法决定了「切频道不转圈」能不能成立 —— 一旦 encode
 * 少转义一个引号、或者 decode 读错字段，电视上的表现是「有缓存但点进去是空页」，
 * 而且只在特定片名（带引号、换行、表情）时才炸，真机上极难复现。
 *
 * 顺带说明测试用法的边界：这里用的是 harness 的 org.json 替身，所以能通过就
 * 证明「替身语法下的往返正确」；真 org.json 更宽松，只会更好，不会更糟。
 *
 * 跑法：bash test/harness/run.sh cachetab
 */
public final class CacheTab {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("  [OK] " + what);
        } else {
            fail++;
            System.out.println("  [!!] " + what);
        }
    }

    /** 造一条尽可能「脏」的样本：引号、反斜杠、换行、制表、控制字符、中文、emoji。 */
    private static Vod dirty() {
        Vod v = new Vod();
        v.id = 12345;
        v.src = "bfzy";
        v.name = "测试\"片名\\带反斜杠";
        v.pic = "https://img.example/a.jpg?x=1&y=2";
        v.remarks = "更新至 12 集";
        v.typeName = "国产剧";
        v.year = "2026";
        v.area = "中国大陆";
        v.playFrom = "ffm3u8";
        v.playUrl = "https://v.example/1$2$3";
        v.en = "Test";
        v.actor = "张三,李四";
        v.director = "王五";
        v.lang = "汉语普通话";
        v.blurb = "第一行\n第二行\t带制表\r带回车";
        v.content = "简介里有个控制字符\u0001夹在中间";
        return v;
    }

    private static Vod plain() {
        Vod v = new Vod();
        v.id = 1;
        v.src = "dytt";
        v.name = "普通片子";
        v.pic = "https://img.example/b.jpg";
        v.year = "2025";
        return v;
    }

    public static void main(String[] a) {
        Cache.init(new Context());
        System.out.println("[cachetab] 缓存目录 " + new Context().getCacheDir().getAbsolutePath());

        // ---- 1. 空结果不产生缓存
        Multi.Result empty = new Multi.Result();
        check("".equals(Multi.encode(empty)), "空列表 encode 出空串（不会写垃圾文件）");
        check(Multi.decode("", 0) == null, "空串 decode 返回 null");
        check(Multi.decode(null, 0) == null, "null decode 返回 null");
        check(Multi.decode("{\"v\":9,\"items\":[{\"n\":\"x\"}]}", 0) == null,
                "版本号更高时拒读（将来换格式不炸）");
        check(Multi.decode("{\"items\":[]}", 0) == null, "空 items 当作没缓存");

        // ---- 2. 往返：字段一个都不能丢
        Multi.Result src = new Multi.Result();
        src.page = 3;
        src.pagecount = 289;
        src.okSources = 7;
        src.items.add(dirty());
        src.items.add(plain());
        String raw = Multi.encode(src);
        check(raw.length() > 100, "encode 产出了内容（" + raw.length() + " 字节）");
        check(raw.indexOf('\n') < 0, "encode 结果里没有裸换行（否则 JSON 非法）");

        Multi.Result back = Multi.decode(raw, 0);
        check(back != null, "decode 能读回自己写的东西");
        if (back != null) {
            check(back.page == 3 && back.pagecount == 289 && back.okSources == 7,
                    "page/pagecount/okSources 三个整数往返正确");
            check(back.items.size() == 2, "两条都回来了");
            if (back.items.size() == 2) {
                Vod d = back.items.get(0);
                check(d.id == 12345, "id 往返正确");
                check("bfzy".equals(d.src), "src 往返正确");
                check("测试\"片名\\带反斜杠".equals(d.name), "片名里的引号和反斜杠没被吃掉");
                check("https://img.example/a.jpg?x=1&y=2".equals(d.pic), "海报 URL 完整");
                check("更新至 12 集".equals(d.remarks), "备注往返正确");
                check("2026".equals(d.year), "年份往返正确");
                check("ffm3u8".equals(d.playFrom), "线路名往返正确");
                check("https://v.example/1$2$3".equals(d.playUrl), "播放地址里的 $ 没被转义坏");
                check("张三,李四".equals(d.actor), "主演往返正确");
                check(d.blurb != null && d.blurb.indexOf("第一行") >= 0
                        && d.blurb.indexOf("第二行") >= 0, "多行简介换行还原了");
                check(d.content != null && d.content.indexOf('\u0001') < 0,
                        "控制字符被替换（不是原样塞进 JSON）");
                check(plain().name.equals(back.items.get(1).name), "第二条也在");
            }
        }

        // ---- 3. 坏输入一律返回 null，不抛
        String[] bad = {
            "{", "{\"items\":", "[]", "not json at all", "{\"items\":[1,2,3]}",
            "{\"items\":[{\"n\":\"\"}]}", "{\"page\":0,\"items\":[{\"n\":\"ok\"}]}"
        };
        int survived = 0;
        for (int i = 0; i < bad.length; i++) {
            try {
                Multi.Result r = Multi.decode(bad[i], 0);
                // 最后一条是合法内容，应当读得回来且页码修正为 1
                if (i == bad.length - 1) {
                    if (r != null && r.page == 1 && r.items.size() == 1) {
                        survived++;
                    }
                } else if (r == null) {
                    survived++;
                }
            } catch (Throwable t) {
                System.out.println("      坏输入抛异常: " + bad[i] + " → " + t);
            }
        }
        check(survived == bad.length, "7 组坏输入都不抛，且该拒的拒、该收的收（" + survived + "/" + bad.length + "）");

        // ---- 4. Cache 读写
        Cache.clear();
        check(Cache.get(17, 1) == null, "清空后读不到东西");
        check(Cache.bytes() == 0, "清空后占用为 0 字节");

        Cache.put(17, 1, raw);
        String got = Cache.get(17, 1);
        check(got != null && got.equals(raw), "写进去再读出来，字节一致");
        check(Cache.bytes() > 0, "占用字节数变大了（" + Cache.bytes() + "）");
        check(Cache.sameAs(17, 1, raw), "sameAs 认得出内容没变（防闪的判据）");
        check(!Cache.sameAs(17, 1, raw + "x"), "内容变了 sameAs 返回 false");
        check(Cache.get(18, 1) == null, "别的频道不会串（文件名按 ch_pg 分开）");
        check(Cache.get(17, 2) == null, "别的页码不会串");

        Cache.put(17, 1, raw);
        check(Cache.sameAs(17, 1, raw), "同一页重写不会写坏");

        Cache.put(0, 1, "x");
        check(Cache.get(0, 1) == null, "太短的内容被拒写（MIN_LEN 挡垃圾）");

        Cache.clear();
        check(Cache.get(17, 1) == null && Cache.bytes() == 0, "clear 之后干净了");

        System.out.println();
        if (fail == 0) {
            System.out.println("[PASS] 全部通过 " + pass + " 项：列表缓存的序列化往返与落盘都正常");
        } else {
            System.out.println("[FAIL] 未通过 " + fail + " 项，通过 " + pass + " 项");
        }
        if (fail > 0) {
            System.exit(1);
        }
    }
}
