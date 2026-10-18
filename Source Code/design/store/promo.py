"""Render 1080x1920 store images (headline + phone frame with an app screenshot) with hidden headless Chrome.
Usage: python promo.py <shots_dir> <out_dir>   (shots_dir holds 01.png.. named in SHOTS)"""
import asyncio, base64, json, os, pathlib, subprocess, sys, time, urllib.request

import websockets

SHOTS_DIR, OUT = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
os.makedirs(OUT, exist_ok=True)
FONT = pathlib.Path(r"E:\CodeBase\My Apps\Squish Android Compressor - Opus 5.5\Source Code\app\src\main\res\font\vazirmatn.ttf").as_uri()
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9341
SHOTS = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "promo.json"), encoding="utf-8"))

TPL = """<!doctype html><html dir="rtl"><head><meta charset="utf-8"><style>
@font-face{font-family:V;src:url('%(font)s');font-weight:100 900}
html,body{margin:0;width:1080px;height:1920px;overflow:hidden}
body{font-family:V;background:%(bg)s;position:relative}
.pool{position:absolute;border-radius:50%%;filter:blur(90px);opacity:%(po)s}
h1{position:absolute;top:120px;left:0;right:0;margin:0;text-align:center;font-weight:900;font-size:92px;line-height:1.32;color:%(ink)s;letter-spacing:-1px}
h1 span{display:block}
h1 .g{background:linear-gradient(90deg,#2D63F5,#3A9BFF 55%%,#2CCBE0);-webkit-background-clip:text;background-clip:text;color:transparent}
.phone{position:absolute;top:500px;left:50%%;transform:translateX(-50%%);width:660px;height:1466px;border-radius:84px;
 background:#0f1a30;padding:16px;box-sizing:border-box;box-shadow:0 40px 90px rgba(20,40,90,.35),inset 0 0 0 3px #2b3a5c}
.phone img{width:628px;height:1396px;border-radius:70px;display:block;object-fit:cover;object-position:top}
</style></head><body>
<div class="pool" style="width:760px;height:760px;left:-260px;top:-200px;background:#3A9BFF"></div>
<div class="pool" style="width:700px;height:700px;right:-280px;top:520px;background:#2CCBE0"></div>
<div class="pool" style="width:800px;height:800px;left:-200px;bottom:-380px;background:#2D63F5"></div>
<h1><span>%(l1)s</span><span class="g">%(l2)s</span></h1>
<div class="phone"><img src="%(img)s"></div>
</body></html>"""

proc = subprocess.Popen([CHROME, "--headless=new", f"--remote-debugging-port={PORT}", "--no-first-run", "--disable-gpu",
                         "--hide-scrollbars", "--allow-file-access-from-files", "--window-size=1080,1920",
                         f"--user-data-dir={os.path.join(OUT, '_prof')}", "about:blank"],
                        creationflags=0x08000000, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


async def main():
    for _ in range(150):
        try:
            tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json")); break
        except Exception:
            time.sleep(0.2)
    ws_url = [t for t in tabs if t["type"] == "page"][0]["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=200_000_000) as ws:
        n = 0

        async def cmd(m, **p):
            nonlocal n
            n += 1; my = n
            await ws.send(json.dumps({"id": my, "method": m, "params": p}))
            while True:
                r = json.loads(await ws.recv())
                if r.get("id") == my:
                    if "error" in r: raise RuntimeError(r["error"])
                    return r.get("result", {})
        await cmd("Page.enable")
        await cmd("Emulation.setDeviceMetricsOverride", width=1080, height=1920, deviceScaleFactor=1, mobile=False)
        for s in SHOTS:
            dark = s.get("dark", False)
            html = TPL % dict(font=FONT, img=pathlib.Path(os.path.join(SHOTS_DIR, s["shot"])).as_uri(),
                              l1=s["l1"], l2=s["l2"], bg="#14203a" if dark else "#DCE6F5",
                              ink="#E9F0FC" if dark else "#0F1F3A", po="0.45" if dark else "0.38")
            page = os.path.join(OUT, "_page.html")
            open(page, "w", encoding="utf-8").write(html)
            await cmd("Page.navigate", url=pathlib.Path(page).as_uri())
            await asyncio.sleep(1.5)
            r = await cmd("Page.captureScreenshot", format="png")
            open(os.path.join(OUT, s["out"]), "wb").write(base64.b64decode(r["data"]))
            print("saved", s["out"], flush=True)


try:
    asyncio.run(main())
finally:
    proc.terminate()
