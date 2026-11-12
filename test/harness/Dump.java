import com.yg.vodtv.Api;
import com.yg.vodtv.Http;
import com.yg.vodtv.Proxy;
import com.yg.vodtv.Vod;

/**
 * 观察真实源码改写的播放列表头部，确认 #EXT-X-TARGETDURATION / #EXT-X-VERSION /
 * #EXT-X-MEDIA-SEQUENCE / #EXT-X-ENDLIST 这些 NuPlayer 依赖的标签都被保留。
 */
public final class Dump {

    public static void main(String[] args) throws Exception {
        Http.trustAll();
        int port = Proxy.start();
        if (port <= 0) {
            System.out.println("[FAIL] 代理启动失败");
            System.exit(1);
        }
        Api.Page p = Api.list(6, 1);
        if (p == null || p.items.isEmpty()) {
            System.out.println("[FAIL] 列表为空");
            System.exit(1);
        }
        Vod v = Api.detail(p.items.get(0).id);
        if (v == null || v.epUrls.length == 0) {
            System.out.println("[FAIL] 详情无地址");
            System.exit(1);
        }
        String local = Proxy.wrapPlaylist(v.epUrls[0]);
        long t0 = System.currentTimeMillis();
        String text = Http.get(local, Api.REFERER);
        long cost = System.currentTimeMillis() - t0;
        if (text == null) {
            System.out.println("[FAIL] 取列表失败 " + local);
            System.exit(1);
        }
        String[] lines = text.replace("\r", "").split("\n");
        System.out.println("片名=" + v.name + "  集数=" + v.epUrls.length);
        System.out.println("本地列表=" + local);
        System.out.println("行数=" + lines.length + "  字节=" + text.length() + "  耗时=" + cost + "ms");
        int shown = 0;
        for (int i = 0; i < lines.length && shown < 24; i++) {
            String s = lines[i].trim();
            if (s.length() == 0) {
                continue;
            }
            System.out.println("  " + s);
            shown++;
        }
        String[] tags = { "#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
                "#EXT-X-PLAYLIST-TYPE", "#EXT-X-ENDLIST", "#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-DISCONTINUITY" };
        for (int i = 0; i < tags.length; i++) {
            System.out.println("  含 " + tags[i] + " = " + text.contains(tags[i]));
        }
        System.out.println("[DONE]");
        System.exit(0);
    }
}
