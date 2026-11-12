import urllib.request, json, ssl, sys
from urllib.parse import urlparse

ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
UA = 'Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36'
REF = 'https://wsyzy.cc/'


def fetch(u, ref=None, timeout=20):
    h = {'User-Agent': UA}
    if ref:
        h['Referer'] = ref
    r = urllib.request.Request(u, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout, context=ctx) as f:
            b = f.read()
            return f.status, f.headers.get('Content-Type'), len(b), b[:4], b
    except Exception as e:
        return 'ERR', type(e).__name__, str(e)[:70], b'', b''


st, ct, n, head, body = fetch('https://wsyzy.cc/api.php/provide/vod/?ac=detail&t=13&pg=1', REF)
print('list', st, n)
data = json.loads(body.decode('utf-8'))
items = data['list']
print('items', len(items))

hosts = {}
for it in items:
    p = it.get('vod_pic') or ''
    h = urlparse(p).netloc or '(empty)'
    hosts[h] = hosts.get(h, 0) + 1
print('pic hosts', hosts)

for it in items[:3]:
    p = it.get('vod_pic') or ''
    print('---', it.get('vod_name'))
    print('  pic:', p)
    if not p:
        continue
    for v in [p, p.replace('https://', 'http://', 1)]:
        s = fetch(v, REF)
        print('   ', v[:70], '->', s[0], s[1], s[2], s[3])

# 无 Referer 也试一遍
print('\n== no referer ==')
p = items[0].get('vod_pic') or ''
for v in [p, p.replace('https://', 'http://', 1)]:
    s = fetch(v)
    print('   ', v[:70], '->', s[0], s[1], s[2], s[3])
