import com.yg.vodtv.Live;

import java.util.List;

/**
 * 直播表的离线验收（不联网）。
 *
 * 为什么单独一套：Live.load() 是「远端 JSON → App 里的分组」，它出错的方式不是抛异常，
 * 而是「表看起来正常但少了一半台」或者「远端一坏就把内置表也冲掉」。
 * 这种错只能靠喂脏数据断言出来，靠真机看是看不出的。
 *
 * 与 SrcTab 是同一套思路：Src 管点播源，Live 管直播源，两个都要六层容错。
 */
public final class LiveTab {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("==== LiveTab：直播表容错与分组解析 ====");
        System.out.flush();

        testBuiltin();
        testJiangxi();
        testNormal();
        testBadJson();
        testMissingGroups();
        testBadItems();
        testUrlsCleaning();
        testSnapshot();

        System.out.println();
        if (fail == 0) {
            System.out.println("[PASS] 全部通过 " + pass + " 项：直播表的解析与容错都正常");
            System.exit(0);
        } else {
            System.out.println("[FAIL] " + fail + " 项失败，通过 " + pass + " 项");
            System.exit(1);
        }
    }

    /** 内置兜底：远端从没拉到过的时候，也得有分组可画。 */
    private static void testBuiltin() {
        int g = Live.builtinCount();
        ok("内置表有分组", g >= 3);
        ok("内置表有台", Live.channelCount() > 0 || Live.groupCount() > 0);
        String st = Live.status();
        ok("status 含「内置」", st.indexOf("内置") >= 0);
        ok("status 含「当前」", st.indexOf("当前") >= 0);
    }

    /** 正常表：两组三台，组名/台名/多线路都要原样进来。 */
    private static void testNormal() {
        String json = "{\"version\":1,\"total\":3,\"groups\":["
                + "{\"id\":\"cctv\",\"name\":\"央视\",\"chs\":["
                + "{\"name\":\"CCTV-1\",\"logo\":\"\",\"urls\":[\"http://a/1.m3u8\",\"http://a/2.m3u8\"]},"
                + "{\"name\":\"CCTV-5\",\"logo\":\"\",\"urls\":[\"http://a/3.m3u8\"]}]},"
                + "{\"id\":\"sat\",\"name\":\"卫视\",\"chs\":["
                + "{\"name\":\"湖南卫视\",\"logo\":\"\",\"urls\":[\"http://b/1.m3u8\"]}]}]}";

        int seq = Live.loadSeq();
        Live.load(json);

        ok("load 后 seq 自增", Live.loadSeq() == seq + 1);
        List<Live.Group> gs = remote();
        ok("两组", gs.size() == 2);
        ok("第一组 id", "cctv".equals(gs.get(0).id));
        ok("第一组名", "央视".equals(gs.get(0).name));
        ok("第一组两台", gs.get(0).chs.size() == 2);
        ok("台名正确", "CCTV-1".equals(gs.get(0).chs.get(0).name));
        ok("多线路都进来", gs.get(0).chs.get(0).urls.size() == 2);
        int rc = 0;
        for (int i = 0; i < gs.size(); i++) {
            rc += gs.get(i).chs.size();
        }
        // 参与统计的还有内置江西电信的 165 台，所以总数是 3 + 内置台数
        ok("channelCount 三台", rc == 3 && Live.channelCount() == 3 + builtinJxChannels());
        ok("groupCount 两组", Live.groupCount() == 2 + builtinJxGroups());
    }

    /** 坏 JSON：解析抛异常，但表不能被冲掉。 */
    private static void testBadJson() {
        String keep = firstGroupName();
        int size = Live.groupCount();
        Live.load("{{{{ this is not json");
        ok("坏 JSON 不改组数", Live.groupCount() == size);
        ok("坏 JSON 不改组名", keep.equals(firstGroupName()));
        Live.load(null);
        ok("null 不改组数", Live.groupCount() == size);
        Live.load("   ");
        ok("空白不改组数", Live.groupCount() == size);
        Live.load("{\"version\":1}");
        ok("只有 version 不改组数", Live.groupCount() == size);
    }

    /** 缺 groups / 空 groups：也该整体放弃。 */
    private static void testMissingGroups() {
        int size = Live.groupCount();
        Live.load("{\"version\":1,\"total\":0}");
        ok("缺 groups 不改组数", Live.groupCount() == size);
        Live.load("{\"version\":1,\"groups\":[]}");
        ok("空 groups 不改组数", Live.groupCount() == size);
        Live.load("[1,2,3]");
        ok("根是数组不改组数", Live.groupCount() == size);
    }

    /** 坏项：只丢坏的那一条，好的要留下。 */
    private static void testBadItems() {
        String json = "{\"version\":1,\"groups\":["
                + "{\"id\":\"\",\"name\":\"没 id\",\"chs\":["
                + "{\"name\":\"X\",\"urls\":[\"http://x/1.m3u8\"]}]},"
                + "{\"id\":\"ok\",\"name\":\"正常组\",\"chs\":["
                + "{\"name\":\"\",\"urls\":[\"http://y/1.m3u8\"]},"
                + "{\"name\":\"有名字\",\"urls\":[\"http://y/2.m3u8\"]}]},"
                + "{\"id\":\"empty\",\"name\":\"空组\",\"chs\":[]}]}";

        Live.load(json);
        List<Live.Group> gs = remote();
        ok("无效组被跳过", gs.size() == 1);
        ok("留下的是 ok 组", "ok".equals(gs.get(0).id));
        ok("空名台被跳过", gs.get(0).chs.size() == 1);
        ok("留下的是有名字的台", "有名字".equals(gs.get(0).chs.get(0).name));

        // 全坏：一条都留不下时必须整体放弃，不能变成空表
        int before = Live.groupCount();
        Live.load("{\"version\":1,\"groups\":[{\"id\":\"\",\"chs\":[]}]}");
        ok("全坏项不改组数", Live.groupCount() == before);
    }

    /** URL 清洗：IPv6 要滤掉，http/https 都留，URL 里的逗号不能被切坏。 */
    private static void testUrlsCleaning() {
        String json = "{\"version\":1,\"groups\":["
                + "{\"id\":\"t\",\"name\":\"测试\",\"chs\":["
                + "{\"name\":\"混合\",\"urls\":[\"http://ok/1.m3u8\","
                + "\"http://[2409:8a00::1]/live.m3u8\",\"https://ok/2.m3u8\"]},"
                + "{\"name\":\"只有IPv6\",\"urls\":[\"http://[::1]/x.m3u8\"]}]}]}";

        Live.load(json);
        List<Live.Group> gs = remote();
        ok("一组", gs.size() == 1);
        // v2.8 改：线路全废的台不再整台丢掉，而是保留台名 + 空 urls。
        // 丢台会让「湖南卫视」这种老台从抽屉里消失，用户以为软件坏了。
        ok("空源台也保留", gs.get(0).chs.size() == 2);
        ok("空源台 urls 为空", gs.get(0).chs.get(1).urls.isEmpty());
        List<String> u = gs.get(0).chs.get(0).urls;
        ok("剩两条线路", u.size() == 2);
        ok("http 保留", u.get(0).indexOf("http://") == 0);
        ok("https 保留", u.get(1).indexOf("https://") == 0);

        // URL 里带逗号：不能被当成两个地址切开
        Live.load("{\"version\":1,\"groups\":[{\"id\":\"t\",\"name\":\"t\",\"chs\":["
                + "{\"name\":\"A\",\"urls\":[\"http://h/x.m3u8?a=1,2&b=3\"]}]}]}");
        gs = remote();
        ok("带逗号的地址仍是一条", gs.get(0).chs.get(0).urls.size() == 1);
        ok("逗号没丢", gs.get(0).chs.get(0).urls.get(0).indexOf("a=1,2") > 0);
    }

    /** 快照：groups() 必须返回副本，否则后台换表会撞上 UI 遍历。 */
    private static void testSnapshot() {
        List<Live.Group> s1 = Live.groups();
        List<Live.Group> s2 = Live.groups();
        ok("两次返回不同对象", s1 != s2);
        ok("内容一致", s1.size() == s2.size());

        // 改副本不能影响本体
        if (!s1.isEmpty()) {
            s1.clear();
            ok("清空副本不影响本体", Live.groupCount() > 0);
        }

        // 换表之后旧快照还是旧的，新快照才是新的
        List<Live.Group> old = Live.groups();
        int n = old.size();
        Live.load("{\"version\":1,\"groups\":[{\"id\":\"n1\",\"name\":\"新一\",\"chs\":["
                + "{\"name\":\"台\",\"urls\":[\"http://z/1.m3u8\"]}]},"
                + "{\"id\":\"n2\",\"name\":\"新二\",\"chs\":["
                + "{\"name\":\"台\",\"urls\":[\"http://z/2.m3u8\"]}]}]}");
        ok("换表后组数变了", remote().size() == 2);
        ok("旧快照仍是旧的", old.size() == n);
        ok("新快照是新的", remote().get(0).id.startsWith("n"));
        int s3 = Live.loadSeq();
        Live.load("{\"version\":1,\"groups\":[]}");
        ok("被放弃的 load 不动 seq", Live.loadSeq() == s3);
    }


    /** ==================== 江西电信内置组（v2.11） ==================== */

    /** 组的 id 以 jx 开头即视为内置的江西电信组（永远存在，不受 load 影响）。 */
    private static boolean isJx(Live.Group g) {
        return g != null && g.id != null && g.id.startsWith("jx");
    }

    /** 剔除内置江西电信组之后的「远端表部分」——原来那些 load 断言都针对这一部分。 */
    private static List<Live.Group> remote() {
        List<Live.Group> out = new java.util.ArrayList<Live.Group>();
        List<Live.Group> all = Live.groups();
        for (int i = 0; i < all.size(); i++) {
            if (!isJx(all.get(i))) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    private static int builtinJxGroups() {
        int n = 0;
        List<Live.Group> all = Live.groups();
        for (int i = 0; i < all.size(); i++) {
            if (isJx(all.get(i))) {
                n++;
            }
        }
        return n;
    }

    private static int builtinJxChannels() {
        int n = 0;
        List<Live.Group> all = Live.groups();
        for (int i = 0; i < all.size(); i++) {
            if (isJx(all.get(i))) {
                n += all.get(i).chs.size();
            }
        }
        return n;
    }

    /**
     * 江西电信内置表：必须排在最前、五个分组、165 台、每条线路都带 ?fmt=ts2hls。
     *
     * 这组是写死在源码里的内网单播源（CI 在公网，巡检必然判死，只能内置），
     * 所以这里断言的是「代码没被改坏」，不是「线路一定活着」。
     * v2.14 起从 12 台扩到 165 台，「(备)」那几路已经并成同一台的第 2 条线路，
     * 所以断言也要跟着改成「≥ 而不是 ==」，并单独验一次台内合并。
     */
    private static void testJiangxi() {
        List<Live.Group> all = Live.groups();
        ok("江西电信组排在最前", !all.isEmpty() && isJx(all.get(0)));
        ok("江西电信五个分组", builtinJxGroups() == 5);
        ok("第一组是江西本地", "jxitv".equals(all.get(0).id) && "江西本地".equals(all.get(0).name));
        // v2.16：播放页只看 playerGroups()。内置表先摆，远端公共表合并成一个「公共源」组挂最后。
        java.util.List<Live.Group> play = Live.playerGroups();
        ok("播放页第一组仍是江西本地", !play.isEmpty() && "jxitv".equals(play.get(0).id));
        ok("播放页不会把远端拆成多组", play.size() <= builtinJxGroups() + 1);
        if (play.size() > builtinJxGroups()) {
            ok("远端并成末尾一个公共源组", "remote".equals(play.get(play.size() - 1).id));
        } else {
            ok("远端并成末尾一个公共源组", true);
        }
        int chs = 0;
        int urls = 0;
        boolean allFmt = true;
        boolean allHost = true;
        boolean multi = false;
        for (int i = 0; i < all.size(); i++) {
            Live.Group g = all.get(i);
            if (!isJx(g)) {
                continue;
            }
            for (int j = 0; j < g.chs.size(); j++) {
                Live.Ch c = g.chs.get(j);
                chs++;
                if (c.urls.size() > 1) {
                    multi = true;
                }
                for (int k = 0; k < c.urls.size(); k++) {
                    urls++;
                    String u = c.urls.get(k);
                    if (u.indexOf("fmt=ts2hls") < 0) {
                        allFmt = false;
                    }
                    if (u.indexOf("/PLTV/88888888/224/") < 0) {
                        allHost = false;
                    }
                }
            }
        }
        ok("江西电信 165 台", chs == 165);
        ok("江西电信 174 条线路", urls == 174);
        ok("每条线路都带 fmt=ts2hls", allFmt);
        ok("每条线路都走 PLTV 单播口", allHost);
        ok("备用线路已并入同一个台", multi);
        ok("台名没有残留(备)", noBeiyong(all));
    }

    /** 「(备)」「(备用)」这类后缀应该已经在数据里并掉，不能当成独立台。 */
    private static boolean noBeiyong(List<Live.Group> all) {
        for (int i = 0; i < all.size(); i++) {
            Live.Group g = all.get(i);
            if (!isJx(g)) {
                continue;
            }
            for (int j = 0; j < g.chs.size(); j++) {
                String n = g.chs.get(j).name;
                if (n.indexOf("(备") >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** ==================== 断言工具 ==================== */

    private static String firstGroupName() {
        List<Live.Group> gs = remote();
        return gs.isEmpty() ? "" : String.valueOf(gs.get(0).name);
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  [OK] " + what);
        } else {
            fail++;
            System.out.println("  [!!] " + what);
        }
    }
}
