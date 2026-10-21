#!/usr/bin/env python3
"""Local password-protected iPhone remote for an Android receiver.
No root operations, no arbitrary shell endpoint, no filesystem modifications.
"""
import base64
import hmac
import json
import os
import re
import secrets
import socket
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

PORT = int(os.environ.get("S16_PORT", "8765"))
SERIAL = "127.0.0.1:5555"
USER = "admin"
PASSWORD = os.environ.get("S16_PASS") or secrets.token_urlsafe(18)
PACKAGE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$")
KEYS = {
    "home": "KEYCODE_HOME", "back": "KEYCODE_BACK",
    "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN",
    "left": "KEYCODE_DPAD_LEFT", "right": "KEYCODE_DPAD_RIGHT",
    "ok": "KEYCODE_DPAD_CENTER", "menu": "KEYCODE_MENU",
    "volume_up": "KEYCODE_VOLUME_UP", "volume_down": "KEYCODE_VOLUME_DOWN",
}

def run(args):
    try:
        process = subprocess.run(args, capture_output=True, text=True,
                                 timeout=8, check=False)
        return (process.stdout + process.stderr).strip()[:12000], process.returncode
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return str(exc), 1

def adb(*args):
    return run(["adb", "-s", SERIAL, *args])

PAGE = """<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>S16 Remote</title>
<style>
:root{font-family:system-ui,-apple-system,Arial;background:#101622;color:#eff4ff}
body{max-width:580px;margin:auto;padding:16px}
h1{font-size:24px;margin-bottom:6px}small{color:#b5c3dc}
section{background:#1d2a40;border:1px solid #344963;border-radius:18px;padding:16px;margin:14px 0}
button{background:#356bcb;color:white;border:0;border-radius:12px;padding:13px 8px;font-size:16px;cursor:pointer}
button:active{opacity:.65}.sub{background:#4b607e}.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:9px}
.pad button{height:60px}input{box-sizing:border-box;width:100%;padding:12px;border-radius:9px;
color:white;background:#132039;border:1px solid #536480;margin:10px 0}
pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#101a2d;padding:12px;border-radius:9px;
font-size:12px;max-height:210px;overflow:auto}.wide{width:100%}.good{color:#81f6b2}.bad{color:#ffb5a5}
#pkgs{font-size:13px;max-height:250px;overflow:auto;direction:ltr}
.pitem{display:flex;gap:8px;align-items:center;margin:6px 0}
.pitem button{font-size:12px;padding:6px 10px}
</style>
<h1>📺 S16 Remote Control</h1><small>الآيفون والرسيفر على نفس شبكة Wi-Fi المنزلية</small>
<section><strong>حالة الاتصال</strong><p id="status">جارٍ الفحص…</p>
<button class="sub wide" onclick="callAction('connect')">🔄 إعادة اتصال ADB</button></section>
<section><strong>الريموت</strong><div class="pad">
<button onclick="callAction('key','menu')">القائمة</button>
<button onclick="callAction('key','up')">▲</button>
<button onclick="callAction('key','home')">الرئيسية</button>
<button onclick="callAction('key','left')">◀</button>
<button onclick="callAction('key','ok')">OK</button>
<button onclick="callAction('key','right')">▶</button>
<button onclick="callAction('key','back')">رجوع</button>
<button onclick="callAction('key','down')">▼</button>
<button onclick="callAction('settings')">الإعدادات</button>
<button onclick="callAction('key','volume_down')">صوت −</button>
<button onclick="callAction('key','volume_up')">صوت +</button>
<button onclick="showPackages('user')">التطبيقات</button>
</div></section>
<section><strong>التطبيقات</strong><p><small>عرض وتشغيل البرامج فقط، دون حذف أو Root.</small></p>
<button onclick="showPackages('user')">برامج المستخدم</button>
<button class="sub" onclick="showPackages('system')">برامج النظام</button>
<input dir="ltr" id="pkg" placeholder="com.android.settings">
<button class="wide" onclick="callAction('launch',document.getElementById('pkg').value)">تشغيل التطبيق المحدد</button>
<div id="pkgs"></div></section>
<section><strong>آخر نتيجة</strong><pre id="result">جاهز</pre></section>
<script>
async function api(url,body){
 try{
  let res=await fetch(url,{method:body?'POST':'GET',
    headers:{'Content-Type':'application/json'},
    body:body?JSON.stringify(body):undefined});
  let obj=await res.json();
  if(!res.ok)throw Error(obj.message||obj.error||res.status);
  return obj;
 }catch(e){return {ok:false,message:String(e)}}
}
async function status(){
 let r=await api('/api/status');
 document.getElementById('status').innerHTML=r.connected?
 '<span class="good">● ADB متصل</span>':
 '<span class="bad">● ADB غير متصل. فعّل ADB over Network على الرسيفر إذا كان متاحاً</span>';
 document.getElementById('result').textContent=r.message||'';
}
async function callAction(action,value){
 let r=await api('/api/action',{action,value});
 document.getElementById('result').textContent=r.message||JSON.stringify(r);
 if(action==='connect')status();
}
async function showPackages(kind){
 let r=await api('/api/packages?kind='+kind);
 let box=document.getElementById('pkgs');box.textContent='';
 if(!r.packages){document.getElementById('result').textContent=r.message||'تعذر القراءة';return}
 for(let p of r.packages){
  let div=document.createElement('div');div.className='pitem';
  let b=document.createElement('button');b.className='sub';
  b.textContent='اختيار';b.onclick=()=>document.getElementById('pkg').value=p;
  div.appendChild(b);div.appendChild(document.createTextNode(p));box.appendChild(div);
 }
}
status();
</script></html>"""

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(self.address_string() + " " + fmt % args, flush=True)

    def authenticate(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Basic "):
            try:
                supplied = base64.b64decode(auth[6:], validate=True).decode()
                if hmac.compare_digest(supplied, USER + ":" + PASSWORD):
                    return True
            except (ValueError, UnicodeDecodeError):
                pass
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="S16 Home Remote"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        return False

    def reply(self, result, status=200):
        body = json.dumps(result, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.authenticate():
            return
        path = urlsplit(self.path)
        if path.path == "/":
            body = PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)
        elif path.path == "/api/status":
            output, _ = run(["adb", "devices"])
            connected = any(x.startswith(SERIAL + "\tdevice") for x in output.splitlines())
            self.reply({"connected": connected,
                        "message": "ADB connected" if connected else output})
        elif path.path == "/api/packages":
            kind = parse_qs(path.query).get("kind", ["user"])[0]
            if kind not in ("system", "user"):
                return self.reply({"error": "Invalid kind"}, 400)
            output, code = adb("shell", "pm", "list", "packages",
                               "-s" if kind == "system" else "-3")
            if code:
                return self.reply({"message": output}, 409)
            self.reply({"packages": sorted(x[8:] for x in output.splitlines()
                                            if x.startswith("package:"))})
        else:
            self.reply({"error": "Not found"}, 404)

    def do_POST(self):
        if not self.authenticate():
            return
        if urlsplit(self.path).path != "/api/action":
            return self.reply({"error": "Not found"}, 404)
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            return self.reply({"error": "Expected JSON"}, 415)
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 2048:
                return self.reply({"error": "Bad length"}, 400)
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                return self.reply({"error": "Expected object"}, 400)
            action, value = data.get("action"), data.get("value")
            if action == "connect":
                output, code = run(["adb", "connect", SERIAL])
            elif action == "key" and isinstance(value, str) and value in KEYS:
                output, code = adb("shell", "input", "keyevent", KEYS[value])
            elif action == "settings":
                output, code = adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
            elif action == "launch" and isinstance(value, str) and PACKAGE.fullmatch(value):
                output, code = adb("shell", "monkey", "-p", value,
                                   "-c", "android.intent.category.LAUNCHER", "1")
            else:
                return self.reply({"error": "Action not allowed"}, 400)
            self.reply({"ok": code == 0,
                        "message": output or ("OK" if code == 0 else "ADB failed")},
                       200 if code == 0 else 409)
        except (ValueError, TypeError, json.JSONDecodeError):
            self.reply({"error": "Invalid input"}, 400)

def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 80))
        result = s.getsockname()[0]
        s.close()
        return result
    except OSError:
        return "RECEIVER_IP"

if __name__ == "__main__":
    print("\n==== S16 REMOTE (HOME WIFI ONLY) ====", flush=True)
    print("Safari on iPhone: http://" + local_ip() + ":" + str(PORT), flush=True)
    print("Username: " + USER, flush=True)
    print("Password: " + PASSWORD, flush=True)
    print("Do not share this password or open the port to the Internet.", flush=True)
    print("Ctrl+C stops the server.\n", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
