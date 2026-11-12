import urllib.request, ssl, json
from urllib.parse import urlparse

UA = 'Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36'
REF = 'https://wsyzy.cc/'
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=ctx))

urls = [
    'https://img1.wsyzy.org/upload/vod/20261111-1/ccc01ea30a25f8c09840ff6d9da5275d.jpg',
    'http://img1.wsyzy.org/upload/vod/20261111-1/ccc01ea30a25f8c09840ff6d9da5275d.jpg',
]
for u in urls:
    r = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': REF, 'Accept': '*/*'})
    try:
        with opener.open(r, timeout=20) as f:
            body = f.read()
            print(u[:8], '->', f.status, f.headers.get('Content-Type'), len(body))
    except urllib.error.HTTPError as e:
        print(u[:8], '-> HTTP', e.code, 'Location:', e.headers.get('Location'))
    except Exception as e:
        print(u[:8], '-> ERR', type(e).__name__, str(e)[:80])
