# -*- coding: utf-8 -*-
"""离线解析已下载的 TS 分片：PMT 流类型 + 视频 NAL 头 + 分辨率。"""
import collections
import glob
import os
import sys

CODEC = {
    0x01: "MPEG-1 视频", 0x02: "MPEG-2 视频", 0x03: "MPEG-1 音频",
    0x04: "MPEG-2 音频", 0x0F: "AAC 音频", 0x10: "MPEG-4 视频", 0x11: "AAC-LATM",
    0x1B: "H.264 / AVC", 0x24: "H.265 / HEVC", 0x42: "AVS", 0xEA: "VC-1",
    0x81: "AC-3 音频", 0x87: "E-AC-3 音频", 0x8A: "DTS 音频", 0x86: "SCTE-35",
}


def packets(buf):
    out = []
    i = 0
    while i + 188 <= len(buf):
        if buf[i] != 0x47:
            i += 1
            continue
        out.append(buf[i:i + 188])
        i += 188
    return out


def payload(p, skip_pointer=False):
    afc = (p[3] >> 4) & 0x03
    if afc == 0 or afc == 2:
        return b""
    q = p[4:]
    if afc == 3:
        alen = q[0]
        q = q[1 + alen:]
    # PSI 段在 PUSI 包里有 1 字节 pointer_field，必须先跳过
    if skip_pointer and (p[1] & 0x40) and q:
        q = q[1 + q[0]:]
    return q


def parse_psi(pkts):
    """返回 ({pid: [stream_type,...]}, pmt_pid)，直接从 PAT/PMT 抽。"""
    pmt_pid = None
    result = {}
    for p in pkts:
        pid = ((p[1] & 0x1F) << 8) | p[2]
        if not (p[1] & 0x40):
            continue
        q = payload(p, skip_pointer=True)
        if len(q) < 8:
            continue
        if q[0] == 0x00 and pmt_pid is None:      # PAT
            slen = ((q[1] & 0x0F) << 8) | q[2]
            body = q[8:3 + slen - 4]
            for k in range(0, len(body) - 3, 4):
                prog = (body[k] << 8) | body[k + 1]
                ppid = ((body[k + 2] & 0x1F) << 8) | body[k + 3]
                if prog != 0:
                    pmt_pid = ppid
                    break
        elif q[0] == 0x02 and pid == pmt_pid:      # PMT
            slen = ((q[1] & 0x0F) << 8) | q[2]
            end = min(3 + slen - 4, len(q))
            pil = ((q[10] & 0x0F) << 8) | q[11]
            k = 12 + pil
            while k + 4 < end:
                st = q[k]
                epid = ((q[k + 1] & 0x1F) << 8) | q[k + 2]
                esl = ((q[k + 3] & 0x0F) << 8) | q[k + 4]
                result.setdefault(epid, []).append(st)
                k += 5 + esl
    return result, pmt_pid


def nal_scan(pkts, vpid):
    """收集该 PID 的 PES 负载，按 Annex-B 找 NAL。"""
    stream = bytearray()
    for p in pkts:
        pid = ((p[1] & 0x1F) << 8) | p[2]
        if pid != vpid:
            continue
        q = payload(p)
        if not q:
            continue
        if p[1] & 0x40:                            # PUSI：跳过 PES 头
            if len(q) < 9 or q[0:3] != b"\x00\x00\x01":
                continue
            hdr_len = q[8]
            q = q[9 + hdr_len:]
        stream += q
    return bytes(stream)


def nal_types(data):
    """返回 [(offset, nal_byte)]，兼容 3 字节/4 字节起始码。"""
    out = []
    i = 0
    n = len(data)
    while i < n - 4:
        if data[i] == 0 and data[i + 1] == 0:
            if data[i + 2] == 1:
                out.append((i, data[i + 3], 3))
                i += 4
                continue
            if data[i + 2] == 0 and data[i + 3] == 1 and i + 4 < n:
                out.append((i, data[i + 4], 4))
                i += 5
                continue
        i += 1
    return out


HEVC_TYPES = {0: "VPS", 1: "SPS", 2: "PPS", 19: "IDR_W_RADL", 20: "IDR_N_LP",
              21: "CRA_NUT", 32: "VPS", 33: "SPS", 34: "PPS", 39: "SEI"}
AVC_TYPES = {1: "非IDR片", 5: "IDR片", 6: "SEI", 7: "SPS", 8: "PPS", 9: "AUD"}


def avc_resolution(sps):
    """从 AVC SPS 解宽高（去掉 emulation prevention byte 后按指数哥伦布读）。"""
    try:
        rbsp = bytearray()
        zeros = 0
        for b in sps:
            if zeros >= 2 and b == 0x03:
                zeros = 0
                continue
            rbsp.append(b)
            zeros = zeros + 1 if b == 0 else 0
        bits = "".join(format(x, "08b") for x in rbsp)
        n = len(bits)
        pos = [0]

        def u(cnt):
            v = int(bits[pos[0]:pos[0] + cnt] or "0", 2)
            pos[0] += cnt
            return v

        def ue():
            z = 0
            while pos[0] < n and bits[pos[0]] == "0":
                z += 1
                pos[0] += 1
            pos[0] += 1
            if z == 0:
                return 0
            v = int(bits[pos[0]:pos[0] + z] or "0", 2)
            pos[0] += z
            return (1 << z) - 1 + v

        def se():
            v = ue()
            return (v + 1) // 2 if v % 2 else -(v // 2)

        prof = u(8)
        u(8)                                       # constraint flags
        u(8)                                       # level_idc
        ue()                                       # seq_parameter_set_id
        chroma = 1
        sep_colour = 0
        if prof in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134):
            chroma = ue()
            if chroma == 3:
                sep_colour = u(1)
            ue()                                   # bit_depth_luma_minus8
            ue()                                   # bit_depth_chroma_minus8
            u(1)                                   # qpprime_y_zero_transform_bypass
            if u(1):                               # seq_scaling_matrix_present
                cnt = 8 if chroma != 3 else 12
                for i in range(cnt):
                    if u(1):
                        size = 16 if i < 6 else 64
                        last = nxt = 8
                        for _ in range(size):
                            if nxt != 0:
                                nxt = (last + ue() + 256) % 256
                            last = nxt if nxt != 0 else last
        ue()                                       # log2_max_frame_num_minus4
        poc = ue()
        if poc == 0:
            ue()
        elif poc == 1:
            u(1)
            ue()
            ue()
            for _ in range(ue()):
                ue()
        ue()                                       # max_num_ref_frames
        u(1)                                       # gaps_in_frame_num_allowed
        w_mbs = ue() + 1
        h_map = se() + 1
        frame_mbs_only = u(1)
        if not frame_mbs_only:
            u(1)                                   # mb_adaptive_frame_field
        u(1)                                       # direct_8x8_inference
        cl = ct = cr = cb = 0
        if u(1):                                   # frame_cropping_flag
            cl, ct, cr, cb = ue(), ue(), ue(), ue()
        sub_w = 1 if chroma in (0,) else 2
        sub_h = 2 if chroma in (0,) else 1
        if sep_colour:
            sub_w = sub_h = 1
        width = w_mbs * 16 - (cl + cr) * sub_w
        height = (2 - frame_mbs_only) * h_map * 16 - (ct + cb) * sub_h * (2 - frame_mbs_only)
        return width, height
    except Exception as e:
        return ("解析失败: %s" % e, 0)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    files = sorted(glob.glob(os.path.join(os.path.dirname(__file__), "samples", "*.ts")))
    if not files:
        print("没有样本，先运行 fetch_sample.py")
        return
    verdicts = []
    for fp in files:
        buf = open(fp, "rb").read()
        pkts = packets(buf)
        print("\n" + "=" * 64)
        print(os.path.basename(fp), "| %d 字节 | %d 个 TS 包" % (len(buf), len(pkts)))
        pids = collections.Counter(((p[1] & 0x1F) << 8) | p[2] for p in pkts)
        print("PID 分布:", ", ".join("0x%X:%d" % (k, v) for k, v in pids.most_common(6)))

        streams, pmt_pid = parse_psi(pkts)
        print("PMT PID:", pmt_pid, "| 流:")
        vpid = None
        for pid, types in sorted(streams.items()):
            for st in types:
                print("  0x%02X  pid=%-6d %s" % (st, pid, CODEC.get(st, "未知")))
                if st in (0x1B, 0x24, 0x01, 0x02, 0x10):
                    vpid = pid
        if vpid is None:
            print("  [!] PMT 没标识视频流")
            continue

        data = nal_scan(pkts, vpid)
        nals = nal_types(data)
        print("  视频 PID=0x%X | PES 负载 %d 字节 | NAL 单元 %d 个" % (vpid, len(data), len(nals)))
        if not nals:
            print("  [!] 没找到 Annex-B 起始码")
            continue

        # 判断 AVC vs HEVC：AVC 的 nal_unit_header 是 1 字节（forbidden_zero_bit=0），
        # HEVC 是 2 字节（forbidden_zero_bit + nal_unit_type 在 bit1-6）
        hdrs = [b for _, b, _ in nals[:40]]
        avc_like = all((h & 0x80) == 0 for h in hdrs)
        types_avc = {h & 0x1F for h in hdrs if (h & 0x80) == 0}
        types_hevc = {(h >> 1) & 0x3F for h in hdrs}
        print("  首 12 个 NAL 头:", " ".join("%02X" % h for h in hdrs[:12]))

        # 用 SPS 是否可解析来定论（AVC SPS 第一字节低 5 位 = 7）
        is_avc = 7 in types_avc and (5 in types_avc or 1 in types_avc)
        if is_avc:
            kind = "H.264 / AVC"
            names = AVC_TYPES
        else:
            kind = "H.265 / HEVC" if (33 in types_hevc or 32 in types_hevc) else "未确定"
            names = HEVC_TYPES
        seen = collections.OrderedDict()
        for _, b, _ in nals:
            key = (b & 0x1F) if is_avc else ((b >> 1) & 0x3F)
            seen.setdefault(key, 0)
            seen[key] += 1
        print("  NAL 类型统计:", ", ".join("%s(%d)" % (names.get(k, "type%d" % k), v)
                                          for k, v in list(seen.items())[:8]))
        print("  >>> 编码判定:", kind)
        verdicts.append((os.path.basename(fp), kind))

        if is_avc:
            for off, b, sc in nals:
                if (b & 0x1F) == 7:
                    sps = data[off + sc + 1: off + sc + 80]
                    r = avc_resolution(sps)
                    print("      SPS 分辨率:", r)
                    break
    print("\n" + "=" * 64)
    print("汇总结论：")
    for f, k in verdicts:
        print(" ", f, "->", k)
    if not verdicts:
        print("  [!] 没有取到有效判定，样本不足或解析失败")
    elif all("H.264" in k for _, k in verdicts):
        print("  [OK] 全部 H.264，海信 Android 5.1 电视可硬解")
    else:
        print("  [WARN] 存在非 H.264 片源，老电视可能无法播放")


if __name__ == "__main__":
    main()
