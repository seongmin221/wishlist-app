# 현재 폴더를 127.0.0.1:8931로 내보내는 미리보기 서버. /_blob/ 요청에는 회색 자리 표시 이미지를 돌려준다.
import http.server, hashlib, os
class H(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/_blob/'):
            h = hashlib.md5(self.path.encode()).hexdigest()
            col = '#' + h[:6]
            svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400" fill="#D8D8D2"/><circle cx="200" cy="200" r="90" fill="{col}" opacity=".45"/></svg>'.encode()
            self.send_response(200); self.send_header('Content-Type','image/svg+xml'); self.send_header('Content-Length',str(len(svg))); self.end_headers(); self.wfile.write(svg); return
        return super().do_GET()
    def log_message(self,*a): pass
http.server.ThreadingHTTPServer(('127.0.0.1', 8931), H).serve_forever()
