# -*- coding: utf-8 -*-
"""检测片源 TS 分片里的视频编码，判断老电视（Android 5.1）能不能硬解。

TS 结构：188 字节定长包，包头 0x47。PMT 里 stream_type 决定编码：
  0x1B = H.264/AVC      0x24 = H.265/HEVC      0x01/0x02 = MPEG-2
  0x0F = AAC(ADTS)      0x81 = AC-3           0x87 = E-AC-3
顺带解析 SPS 拿分辨率，看是不是 4K。
"""
import json
import sys
import time
import urllib.request

SITE = "https://wsyzy.cc"
API = "https://api.wsyzy.net/api.php/provide/vod/"
UA = ("Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/78.0.3904.108 Mobile Safari/537.36")

CODEC = {
    0x01: "MPEG-1 视频", 0x02: "MPEG-2 视频", 0x03: "MPEG-1 音频",
    0x0F: "AAC 音频", 0x10: "MPEG-4 视频", 0x11: "AAC-LATM",
    0x1B: "H.264 / AVC", 0x24: "H.265 / HEVC", 0x42: "AVS",
    0x81: "AC-3 音频", 0x87: "E-AC-3 音频", 0x8A: "DTS 音频",
    0x86: "SCTE-35", 0x06: "私有数据(PES)",
}


def get(url, binary=False, retry=3):
    last = None
    for i in range(retry):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": SITE + "/"})
            data = urllib.request.urlopen(req, timeout=25).read()
            return data if binary else data.decode("utf-8", "replace")
        except Exception as e:
            last = e
            time.sleep(1.5 * (i + 1))
    raise last


def parse_ts_packets(buf):
    """按 188 字节切包，首个 0x47 对齐。"""
    off = buf.find(b"\x47")
    if off < 0:
        return []
    out = []
    i = off
    while i + 188 <= len(buf):
        if buf[i] != 0x47:
            i += 1
            continue
        out.append(buf[i:i + 188])
        i += 188
    return out


def find_pmt_streams(pkts):
    """扫出所有 PMT 声明的 stream_type。"""
    types = {}
    for p in pkts:
        pusi = bool(p[1] & 0x40)
        pid = ((p[1] & 0x1F) << 8) | p[2]
        afc = (p[3] >> 4) & 0x03
        if afc not in (1, 3):
            continue
        payload = p[4:]
        if not pusi or len(payload) < 8:
            continue
        if payload[0] != 0x00:          # table_id 0x00 = PAT
            continue
        # PAT -> 找 PMT PID
        sec_len = ((payload[1] & 0x0F) << 8) | payload[2]
        body = payload[8:3 + sec_len - 4]
        pmt_pids = []
        for k in range(0, len(body) - 3, 4):
            prog = (body[k] << 8) | body[k + 1]
            ppid = ((body[k + 2] & 0x1F) << 8) | body[k + 3]
            if prog != 0:
                pmt_pids.append(ppid)
        for ppid in pmt_pids:
            for q in pkts:
                qpid = ((q[1] & 0x1F) << 8) | q[2]
                if qpid != ppid or not (q[1] & 0x40):
                    continue
                qpl = q[4:]
                if len(qpl) < 12 or qpl[0] != 0x02:   # table_id 0x02 = PMT
                    continue
                slen = ((qpl[1] & 0x0F) << 8) | qpl[2]
                end = 3 + slen - 4
                pi_len = ((qpl[10] & 0x0F) << 8) | qpl[11]
                k = 12 + pi_len
                while k + 4 < end and k + 4 < len(qpl):
                    st = qpl[k]
                    epid = ((qpl[k + 1] & 0x1F) << 8) | qpl[k + 2]
                    esl = ((qpl[k + 3] & 0x0F) << 8) | qpl[k + 4]
                    types[st] = epid
                    k += 5 + esl
    return types


def sps_resolution(buf, hevc=False):
    """从码流里粗找 SPS 并解分辨率（只处理最常见的 profile）。"""
    try:
        if not hevc:
            i = buf.find(b"\x00\x00\x00\x01\x67")
            if i < 0:
                i = buf.find(b"\x00\x00\x01\x67")
                if i < 0:
                    return None
                sps = buf[i + 3:]
            else:
                sps = buf[i + 4:]
            if len(sps) < 8:
                return None
            rbsp = bytearray()
            zeros = 0
            for b in sps[:64]:
                if zeros >= 2 and b == 0x03:
                    zeros = 0
                    continue
                rbsp.append(b)
                zeros = zeros + 1 if b == 0 else 0
            bits = "".join(format(x, "08b") for x in rbsp)
            pos = [0]

            def ue():
                z = 0
                while pos[0] < len(bits) and bits[pos[0]] == "0":
                    z += 1
                    pos[0] += 1
                if pos[0] >= len(bits):
                    return 0
                pos[0] += 1
                v = int(bits[pos[0]:pos[0] + z] or "0", 2)
                pos[0] += z
                return (1 << z) - 1 + v

            prof = int(bits[0:8], 2)
            pos[0] = 8
            ue()                       # constraint flags + level 等
            pos[0] = 24
            ue()                       # seq_parameter_set_id
            if prof in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134):
                fmt = ue()
                if fmt == 3:
                    pos[0] += 1
                ue()
                ue()
                pos[0] += 1
                if pos[0] < len(bits) and bits[pos[0]] == "1":
                    n = 8 if fmt != 3 else 12
                    for _ in range(n):
                        if bits[pos[0]] == "1":
                            last, nxt = 8, 8
                            for _ in range(16):
                                if last != 0:
                                    last = (last + nxt + 256) % 256
                                last = (last + nxt + 256) % 256
                        pos[0] += 1
                pos[0] += 1
            ue()                       # log2_max_frame_num
            poc = ue()
            if poc == 0:
                ue()
            elif poc == 1:
                pos[0] += 1
                ue()
                ue()
                for _ in range(ue()):
                    ue()
            ue()                       # max_num_ref_frames
            pos[0] += 1
            w_mbs = ue() + 1
            h_map = ue() + 1
            frame_mbs_only = bits[pos[0]] if pos[0] < len(bits) else "1"
            pos[0] += 1
            if frame_mbs_only == "0":
                pos[0] += 1
            crop = bits[pos[0]:pos[0] + 1]
            pos[0] += 1
            cl = ct = cr = cb = 0
            if crop == "1":
                cl, ct, cr, cb = ue(), ue(), ue(), ue()
            h = h_map * (1 if frame_mbs_only == "1" else 2)
            w = w_mbs * 16
            hh = h * 16
            w = w - (cl + cr) * 2
            hh = hh - (ct + cb) * 2 * (1 if frame_mbs_only == "1" else 2)
            return w, hh
        # HEVC：只做粗判断，找 VPS/SPS 的 profile_idc
        return None
    except Exception:
        return None


def check(name, raw_m3u8):
    print("\n" + "=" * 60)
    print("检测:", name)
    master = get(raw_m3u8)
    sub = [l.strip() for l in master.split("\n")
           if l.strip() and not l.strip().startswith("#")]
    if sub:
        base = raw_m3u8.rsplit("/", 1)[0]
        ref = sub[0]
        if ref.startswith("http"):
            media_url = ref
        elif ref.startswith("/"):
            media_url = "https://" + raw_m3u8.split("://", 1)[1].split("/")[0] + ref
        else:
            media_url = base + "/" + ref
        media = get(media_url)
        print("  变体列表:", sub[0])
    else:
        media_url = raw_m3u8
        media = master
        print("  直接是媒体列表")

    lines = [l.strip() for l in media.split("\n") if l.strip()]
    segs = [l for l in lines if not l.startswith("#")]
    print("  分片数:", len(segs))
    seg = segs[0]
    if not seg.startswith("http"):
        seg = media_url.rsplit("/", 1)[0] + "/" + seg

    buf = get(seg, binary=True)
    print("  首个分片: %d 字节" % len(buf))
    pkts = parse_ts_packets(buf)
    print("  TS 包数:", len(pkts))
    types = find_pmt_streams(pkts)
    if not types:
        print("  [!] 没解析到 PMT，可能不是标准 TS")
        return
    print("  流类型:")
    for st, pid in sorted(types.items()):
        print("   0x%02X  pid=%-5d  %s" % (st, pid, CODEC.get(st, "未知")))
    vids = [st for st in types if st in (0x1B, 0x24, 0x01, 0x02, 0x10, 0x42)]
    if 0x24 in vids:
        print("  >>> 结论：H.265/HEVC —— Android 5.1 电视硬解通常不支持！")
    elif 0x1B in vids:
        print("  >>> 结论：H.264/AVC —— 老电视可硬解，安全")
        r = sps_resolution(buf)
        if r:
            print("      分辨率推断: %dx%d" % r)
    elif 0x02 in vids:
        print("  >>> 结论：MPEG-2 —— 老电视可硬解")
    else:
        print("  >>> 结论：其他编码，需实测")


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    # 用两个不同类型的片源交叉验证
    samples = []
    q = json.loads(get(SITE + "/index.php/ajax/suggest?mid=1&wd=fanhua"))
    if q.get("list"):
        samples.append((q["list"][0]["name"], q["list"][0]["id"]))
    page = json.loads(get(API + "?ac=detail&pg=1&t=13"))
    for it in page["list"][:1]:
        samples.append((it["vod_name"], it["vod_id"]))

    for name, vid in samples:
        d = json.loads(get(API + "?ac=detail&ids=%d" % vid))["list"][0]
        first = d["vod_play_url"].split("$$$")[0].split("#")[0]
        raw = first.split("$", 1)[1]
        check("%s (id=%d)" % (name, vid), raw)


if __name__ == "__main__":
    main()
