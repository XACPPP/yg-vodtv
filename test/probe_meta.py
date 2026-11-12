import urllib.request, json, ssl

UA = 'Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36'
REF = 'https://wsyzy.cc/'
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE


def fetch(u, ref=REF):
    r = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': ref, 'Accept': '*/*'})
    with urllib.request.urlopen(r, timeout=25, context=ctx) as f:
        return f.read()


body = fetch('https://wsyzy.cc/api.php/provide/vod/?ac=detail&t=13&pg=1')
data = json.loads(body.decode('utf-8'))
it = data['list'][0]
print('== detail item keys ==')
for k in sorted(it.keys()):
    v = it[k]
    s = str(v)
    print('%-18s %s' % (k, s[:90].replace('\n', ' ')))

print()
print('== 各字段长度 ==')
for k in ['vod_blurb', 'vod_content', 'vod_actor', 'vod_director', 'vod_score', 'vod_lang', 'vod_pubdate', 'vod_total', 'vod_serial']:
    v = it.get(k)
    print('%-14s' % k, 'MISSING' if v is None else 'len=%d' % len(str(v)), str(v)[:60].replace('\n', ' '))
