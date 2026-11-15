#!/usr/bin/env python3
"""차량 신호 조작판. 버튼을 눌러 Pleos Connect Virtual Console에 신호를 보낸다.

명령을 치지 않고 오토로그를 손으로 시험할 때 쓴다. 표준 라이브러리만 쓴다.
전제: 에뮬레이터 실행 중, adb forward tcp:8088 tcp:8088.
사용: python3 tools/panel.py  →  브라우저에서 http://127.0.0.1:8090/

Virtual Console은 브라우저 세션(하트비트)이 살아 있어야 쓰기를 받는다. 이 조작판이 직접 하트비트를 보낸다.
"""
import json
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VC = "http://127.0.0.1:8088"
PORT = 8090
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PACKAGE = "com.autolog.app"

busy = threading.Lock()
log_lines = []


def log(text):
    log_lines.append(time.strftime("%H:%M:%S ") + text)
    del log_lines[:-12]


def vc(method, path):
    request = urllib.request.Request(VC + path, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read()


def heartbeat():
    while True:
        try:
            vc("POST", "/browser/session?client=autolog-panel")
        except Exception:
            pass
        time.sleep(1)


def state():
    data = json.loads(vc("GET", "/vehicle/state"))
    return {
        "gear": data["gear"]["actual"]["value"],
        "odometer": data["odometer"]["traveled_distance_km"],
        "door_open": data["body"]["doors"]["front_left"],
        "speed": data["controls"]["speed_kph"],
    }


def adb(*args):
    subprocess.run([ADB, "-e", *args], capture_output=True, timeout=30)


# 한 단계씩 보내는 신호
def door(open_):
    vc("PUT", "/vehicle/body?door_fl=" + ("true" if open_ else "false"))
    log("운전석 문 " + ("열림" if open_ else "닫힘"))


def gear(name):
    vc("PUT", "/vehicle/gear?gear=" + name)
    log("기어 " + name)


def speed(kph):
    if kph == 0:
        vc("PUT", "/vehicle/controls?speed=0&accelerator=0&brake=100")
    else:
        vc("PUT", f"/vehicle/controls?speed={kph}&accelerator=20&brake=0")
    log(f"속도 {kph}km/h")


def odometer_add(km):
    target = int(state()["odometer"]) + km
    vc("PUT", f"/vehicle/odometer?traveled_distance_km={target}")
    log(f"계기판 {target:,}km (+{km})")


def trip(km, with_route):
    """운행 한 건을 처음부터 끝까지 재생한다."""
    door(False)
    time.sleep(1.5)
    if with_route:
        adb("shell", "am", "broadcast", "--user", "10", "-n", PACKAGE + "/.debug.DemoRouteReceiver")
        log("내비 목적지 42dot 설정")
        time.sleep(6)
    gear("Drive")
    time.sleep(1.5)
    speed(40)
    time.sleep(3)
    odometer_add(km)
    time.sleep(3)
    speed(0)
    time.sleep(1.5)
    gear("Park")
    time.sleep(2)
    door(True)
    time.sleep(1)
    adb("shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity")
    log("운행 재생 끝")


ACTIONS = {
    "door_close": lambda: door(False),
    "door_open": lambda: door(True),
    "gear_p": lambda: gear("Park"),
    "gear_d": lambda: gear("Drive"),
    "speed_0": lambda: speed(0),
    "speed_40": lambda: speed(40),
    "odo_1": lambda: odometer_add(1),
    "odo_10": lambda: odometer_add(10),
    "odo_25": lambda: odometer_add(25),
    "trip_18": lambda: trip(18, False),
    "trip_route_25": lambda: trip(25, True),
    "app_front": lambda: (adb("shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity"), log("오토로그를 화면 앞으로")),
    "reset_demo": lambda: (
        adb("shell", "pm", "clear", "--user", "10", PACKAGE),
        time.sleep(1),
        adb("shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity", "--ez", "seed", "true"),
        log("앱 데이터를 지우고 예시 운행 12건으로 시작"),
    ),
}


def run(action):
    try:
        ACTIONS[action]()
    except urllib.error.HTTPError as error:
        log(f"실패: Virtual Console이 {error.code}로 거절 (계기판 거리는 줄일 수 없음)")
    except Exception as error:
        log(f"실패: {type(error).__name__}: {error}")
    finally:
        busy.release()


PAGE = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>오토로그 차량 신호 조작판</title>
<style>
:root{--bg:#f4f6f8;--panel:#fff;--ink:#111418;--quiet:#4a5560;--line:#c7ced6;--accent:#16324f;--on:#fff;--soft:#e6ebf0}
@media (prefers-color-scheme:dark){:root{--bg:#0e1114;--panel:#171b20;--ink:#e9edf1;--quiet:#a9b4bf;--line:#3a434d;--accent:#9cc2ea;--on:#0b2138;--soft:#2a323b}}
*{box-sizing:border-box}body{margin:0;padding:16px;background:var(--bg);color:var(--ink);font:15px/1.5 -apple-system,"Apple SD Gothic Neo",sans-serif}
h1{font-size:18px;margin:0 0 4px}p{margin:0 0 12px;color:var(--quiet)}
section{background:var(--panel);border-radius:14px;padding:14px 16px;margin-bottom:12px}
h2{font-size:13px;margin:0 0 10px;color:var(--quiet);font-weight:600}
.row{display:flex;flex-wrap:wrap;gap:8px}
button{font:inherit;font-weight:600;padding:12px 16px;border-radius:999px;border:0;background:var(--soft);color:var(--ink);cursor:pointer}
button.main{background:var(--accent);color:var(--on)}button:disabled{opacity:.45;cursor:default}
button.warn{background:transparent;border:1px solid var(--line);color:var(--quiet)}
#state{display:flex;flex-wrap:wrap;gap:8px 20px;font-variant-numeric:tabular-nums}#state b{font-size:20px}
#state span{color:var(--quiet);font-size:12px;display:block}
pre{margin:0;font:12px/1.6 ui-monospace,Menlo,monospace;color:var(--quiet);white-space:pre-wrap;min-height:96px}
</style></head><body>
<h1>오토로그 차량 신호 조작판</h1>
<p>버튼을 누르면 에뮬레이터의 차량 신호가 바뀝니다. 앱 화면은 에뮬레이터 창에서 직접 누르세요.</p>
<section><h2>지금 차량 상태</h2><div id="state">불러오는 중</div></section>
<section><h2>운행 한 건을 한 번에 재생</h2><div class="row">
<button class="main" data-a="trip_18">운행 한 건 (18km)</button>
<button class="main" data-a="trip_route_25">내비 목적지 넣고 운행 (25km)</button></div></section>
<section><h2>한 단계씩</h2><div class="row">
<button data-a="door_close">운전석 문 닫기</button><button data-a="gear_d">기어 D</button><button data-a="speed_40">속도 40</button>
<button data-a="odo_1">계기판 +1km</button><button data-a="odo_10">계기판 +10km</button><button data-a="odo_25">계기판 +25km</button>
<button data-a="speed_0">속도 0</button><button data-a="gear_p">기어 P</button><button data-a="door_open">운전석 문 열기</button></div></section>
<section><h2>앱</h2><div class="row">
<button data-a="app_front">오토로그를 화면 앞으로</button>
<button class="warn" data-a="reset_demo" data-confirm="앱에 저장된 운행과 설정을 모두 지우고 예시 운행 12건으로 다시 시작합니다. 계속할까요?">예시 상태로 되돌리기</button></div></section>
<section><h2>보낸 신호</h2><pre id="log"></pre></section>
<script>
const buttons=[...document.querySelectorAll('button[data-a]')];
async function refresh(){try{const r=await fetch('/api/state');const s=await r.json();
document.getElementById('state').innerHTML=s.error?('에뮬레이터에 연결하지 못했습니다: '+s.error):
`<div><span>기어</span><b>${s.gear}</b></div><div><span>계기판</span><b>${Math.round(s.odometer).toLocaleString()}km</b></div><div><span>속도</span><b>${Math.round(s.speed)}km/h</b></div><div><span>운전석 문</span><b>${s.door_open?'열림':'닫힘'}</b></div>`;
document.getElementById('log').textContent=(s.log||[]).join('\\n');buttons.forEach(b=>b.disabled=!!s.busy);}catch(e){document.getElementById('state').textContent='조작판 서버에 연결하지 못했습니다';}}
buttons.forEach(b=>b.addEventListener('click',async()=>{if(b.dataset.confirm&&!confirm(b.dataset.confirm))return;buttons.forEach(x=>x.disabled=true);await fetch('/api/do?a='+b.dataset.a,{method:'POST'});refresh();}));
refresh();setInterval(refresh,1500);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body, kind):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", kind + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            self.send(200, PAGE, "text/html")
        elif path == "/api/state":
            try:
                result = state()
            except Exception as error:
                result = {"error": f"{type(error).__name__}"}
            result["busy"] = busy.locked()
            result["log"] = log_lines
            self.send(200, json.dumps(result, ensure_ascii=False), "application/json")
        else:
            self.send(404, "not found", "text/plain")

    def do_POST(self):
        url = urlparse(self.path)
        action = parse_qs(url.query).get("a", [""])[0]
        if url.path != "/api/do" or action not in ACTIONS:
            self.send(404, "not found", "text/plain")
            return
        if not busy.acquire(blocking=False):
            self.send(409, json.dumps({"busy": True}), "application/json")
            return
        threading.Thread(target=run, args=(action,), daemon=True).start()
        self.send(200, json.dumps({"started": action}), "application/json")


if __name__ == "__main__":
    threading.Thread(target=heartbeat, daemon=True).start()
    print(f"조작판: http://127.0.0.1:{PORT}/")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
