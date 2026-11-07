#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - serwer HTTP dla odbiornika:
#  GET  /...              pliki z /opt/epgpl/www (pl.xml.xz, mapa kanalow, raport, mapa.json, stacje.json)
#  POST /api/bukiety      ustawienia z dekodera: bukiety {"mode": "all"|"selected", "names": [...]}
#                         i kolejnosc serwisow {"sites": ["tm", "onet", "naz", "wp"]} (pierwszenstwo przed SITES)
#  POST /api/alias        wskazanie z tunera: {"name": "<kanal na satelicie>", "target": "tm:Nat-Geo-Wild" | "-" | ""}
#                         albo {"items": [{"name", "target"}, ...]} - wiele naraz (synchronizacja z dekoderem).
#                         "" = usun reczne wskazanie (wraca automat). Zapis do wskazania.txt,
#                         potem przeliczenie mapy (mapa.json) - program pobierze nastepne pobranie.
#  GET  /api/podglad?target=tm:Nat-Geo-Wild   program stacji na 2 dni: z ostatniego pliku programu,
#                         a gdy stacji w nim nie ma - pobrany na zadanie z serwisu (kilkanascie sekund)
#  GET  /api/staty?t=tm:Eurosport-3,onet:x   statystyki stacji z pliku programu: srednia dlugosc opisu, ile dni
#                         programu, biezaca audycja (okno wyboru stacji w EPG Scan); stacji spoza pliku - brak klucza
#  POST /api/kandydaci   {"targets": ["tm:x", ...]} pobranie programu stacji teraz (w tle) -> www/teraz.xml.xz;
#  GET  /api/kandydaci   stan: {"running", "total", "done"}
#  POST /api/pobierz      uruchomienie pobierania programu (epgpl.service; wymaga /etc/sudoers.d/epgpl z instalatora)
#  POST /api/tuner        dekoder zglasza swoj harmonogram: {"grab": true, "next": <czas>} - harmonogram serwera
#                         (epgpl-auto.service) jest pomijany w oknie +-TUNER_WINDOW h (tuner_skip.py)
#  GET  /api/stan         czy pobieranie trwa, ostatnia linia logu, wynik ostatniego przebiegu, czas pliku programu
# Zmiany przyjmowane tylko z adresow sieci lokalnej i tylko w scisle okreslonej postaci.
import glob
import http.server
import ipaddress
import json
import lzma
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

BASE = os.environ.get("EPGPL_BASE", "/opt/epgpl")
WWW = os.path.join(BASE, "www")
TUNER = os.path.join(BASE, "wskazania.txt")
TARGET = re.compile(r"^(onet|tm|wp|naz|cp):[A-Za-z0-9%._+-]{1,100}$")
LOCK = threading.Lock()
PLOCK = threading.Lock()          # najwyzej jeden podglad pobierany z serwisu naraz
SITE_OF = {"onet": "programtv.onet.pl", "tm": "teleman.pl", "wp": "pilot.wp.pl",
           "naz": "programtv.naziemna.info", "cp": "canalplus.com"}


def port():
    try:
        m = re.search(r"^PORT=(\d+)", open(os.path.join(BASE, "config.env")).read(), re.M)
        return int(m.group(1))
    except Exception:
        return 8088


def load_tuner():
    out = {}
    try:
        for ln in open(TUNER, encoding="utf-8"):
            ln = ln.split("#", 1)[0].strip()
            if "=" in ln:
                k, v = [x.strip() for x in ln.split("=", 1)]
                out[k] = v
    except FileNotFoundError:
        pass
    return out


def save_tuner(d):
    tmp = TUNER + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("# epgpl - wskazania ustawione z tunera (EPG Scan -> Dopasowanie kanalow). Pierwszenstwo przed automatem.\n")
        f.write("# Mozna edytowac recznie; usuniecie linii = powrot do automatu.\n")
        for k in sorted(d, key=str.lower):
            f.write("%s = %s\n" % (k, d[k]))
    os.replace(tmp, TUNER)


def rebuild():
    """Przeliczenie mapy w tle (bez pobierania programu)."""
    subprocess.Popen(["bash", os.path.join(BASE, "mapa.sh")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# --- podglad programu stacji ---
def xmltime(v):
    import calendar
    m = re.match(r"(\d{14})\s*([+-]\d{4})?", v or "")
    if not m:
        return None
    t = calendar.timegm(time.strptime(m.group(1), "%Y%m%d%H%M%S"))
    if m.group(2):
        o = int(m.group(2))
        t -= (1 if o >= 0 else -1) * ((abs(o) // 100) * 3600 + (abs(o) % 100) * 60)
    return t


def read_progs(f, xid=None):
    now = time.time()
    out = []
    for ev, el in ET.iterparse(f):
        if el.tag == "programme":
            if xid is None or el.get("channel") == xid:
                st, sp = xmltime(el.get("start")), xmltime(el.get("stop"))
                if st and (sp or st + 1800) > now - 3 * 3600 and st < now + 2 * 86400:
                    sub = (el.findtext("sub-title") or "").strip()
                    out.append({"start": st, "stop": sp or 0, "title": (el.findtext("title") or "").strip(),
                                "sub": sub[:120], "desc": (el.findtext("desc") or "").strip()[:500]})
            el.clear()
    out.sort(key=lambda x: x["start"])
    for i, p in enumerate(out):
        if not p["stop"] and i + 1 < len(out):
            p["stop"] = out[i + 1]["start"]
    return out[:120]


SLOCK = threading.Lock()
_stats = {}          # plik -> (czas modyfikacji, {xid: [audycji, znakow opisu, koniec, [(start, stop, tytul)]]})
# pliki programu: glowny (kanaly z bukietow), "teraz" (kandydaci pobrani na zadanie z dekodera), kandydaci z nocy (24 h)
GUIDES = (("pl.xml.xz", "plik"), ("teraz.xml.xz", "teraz"), ("kandydaci.xml.xz", "kandydaci"))


def file_stats(name):
    guide = os.path.join(WWW, name)
    try:
        mt = os.path.getmtime(guide)
    except OSError:
        return {}
    with SLOCK:
        old = _stats.get(name)
        if old and old[0] == mt:
            return old[1]
        acc = {}
        with lzma.open(guide) as f:
            for ev, el in ET.iterparse(f):
                if el.tag == "programme":
                    x = el.get("channel")
                    st, sp = xmltime(el.get("start")), xmltime(el.get("stop"))
                    if x and st:
                        a = acc.setdefault(x, [0, 0, 0, []])
                        a[0] += 1
                        a[1] += len((el.findtext("desc") or "").strip())
                        a[2] = max(a[2], sp or st)
                        a[3].append((st, sp or 0, (el.findtext("title") or "").strip()))
                    el.clear()
        for a in acc.values():
            a[3].sort()
            for i, (st, sp, t) in enumerate(a[3]):        # brak konca (np. WP) - poczatek nastepnej audycji
                if not sp and i + 1 < len(a[3]):
                    a[3][i] = (st, a[3][i + 1][0], t)
        _stats[name] = (mt, acc)
        return acc


def xid_of(target):
    tag, sid = target.split(":", 1)
    return "%s.%s" % (tag, re.sub(r"[^A-Za-z0-9-]", "_", sid))


def stats(targets):
    files = [(src, file_stats(name)) for name, src in GUIDES]
    now = time.time()
    out = {}
    for t in targets:
        x = xid_of(t)
        for src, acc in files:
            a = acc.get(x)
            if not a or a[2] < now:
                continue
            cur = None
            for st, sp, title in a[3]:
                if st <= now < (sp or st + 1800):
                    cur = [st, sp, title]
                    break
                if st > now:
                    break
            out[t] = {"avg": int(a[1] / a[0]) if a[0] else 0, "days": round(max(0.0, (a[2] - now) / 86400.0), 1),
                      "now": cur, "src": src}
            break
    return {"ok": True, "stats": out}


# --- program kandydatow na zadanie (okno wyboru stacji w EPG Scan: Menu -> Pobierz program kandydatow) ---
TLOCK = threading.Lock()
TSTATE = {"running": False, "total": 0, "done": 0, "targets": [], "t0": 0, "error": ""}


def grab_env():
    return dict(os.environ, PATH=os.path.join(BASE, "node", "bin") + ":" + os.environ.get("PATH", "/usr/bin:/bin"), HOME=BASE)


def station_names():
    try:
        st = json.load(open(os.path.join(WWW, "stacje.json"), encoding="utf-8"))
        return {tag: dict((a, b) for a, b in lst) for tag, lst in st.items()}
    except Exception:
        return {}


def teraz_start(targets):
    with TLOCK:
        if TSTATE["running"]:
            return {"ok": True, "started": False, "running": True, "total": TSTATE["total"], "done": TSTATE["done"]}
        TSTATE.update(running=True, total=len(targets), done=0, targets=targets, t0=int(time.time()), error="")
    th = threading.Thread(target=teraz_work, args=(targets,))
    th.daemon = True
    th.start()
    return {"ok": True, "started": True, "total": len(targets)}


def teraz_work(targets):
    """Pobranie programu wskazanych stacji (dzien biezacy; w nocy od poprzedniego, wieczorem z jutrzejszym)
    - kazdy serwis rownolegle - i dopisanie do www/teraz.xml.xz (stacje pobrane wczesniej zostaja do 24 h)."""
    tmp = tempfile.mkdtemp(prefix="teraz-", dir=os.path.join(BASE, "work"))
    try:
        names = station_names()
        by_site = {}
        for t in targets:
            tag, sid = t.split(":", 1)
            if tag in SITE_OF:
                by_site.setdefault(tag, []).append(sid)
        h = time.localtime().tm_hour
        if h < 6:
            curr, days = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 86400)), 2
        else:
            curr, days = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), 2 if h >= 20 else 1
        outs = []

        def site_job(tag, sids):
            ch, out = os.path.join(tmp, tag + ".channels.xml"), os.path.join(tmp, tag + ".xml")
            with open(ch, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n')
                for sid in sids:
                    f.write('  <channel site="%s" site_id=%s lang="pl" xmltv_id="%s">%s</channel>\n' % (
                        SITE_OF[tag], quoteattr(sid), xid_of(tag + ":" + sid), escape(names.get(tag, {}).get(sid, sid))))
                f.write("</channels>\n")
            try:
                subprocess.run(["npm", "run", "--silent", "grab", "---", "--channels=" + ch, "--output=" + out,
                                "--days=%d" % days, "--maxConnections=2"], cwd=os.path.join(BASE, "iptv-epg"),
                               env=dict(grab_env(), CURR_DATE=curr), timeout=600,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass
            if os.path.exists(out):
                outs.append(out)
            with TLOCK:
                TSTATE["done"] += len(sids)

        ths = [threading.Thread(target=site_job, args=(t, s)) for t, s in by_site.items()]
        for th in ths:
            th.start()
        for th in ths:
            th.join()
        merge_teraz(outs)
    except Exception as e:
        TSTATE["error"] = str(e)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        with TLOCK:
            TSTATE["running"] = False


def merge_teraz(outs):
    """Nowe audycje + stacje z poprzedniego teraz.xml.xz (mlodszego niz 24 h), ktorych teraz nie pobierano."""
    new, chans = [], {}
    for o in outs:
        for ev, el in ET.iterparse(o):
            if el.tag == "channel":
                chans[el.get("id")] = ET.tostring(el, encoding="unicode")
            elif el.tag == "programme":
                new.append(ET.tostring(el, encoding="unicode"))
                el.clear()
    got = {re.search(r'channel="([^"]+)"', p).group(1) for p in new if 'channel="' in p}
    path = os.path.join(WWW, "teraz.xml.xz")
    old = []
    if os.path.exists(path) and time.time() - os.path.getmtime(path) < 86400:
        with lzma.open(path) as f:
            for ev, el in ET.iterparse(f):
                if el.tag == "channel" and el.get("id") not in got:
                    chans.setdefault(el.get("id"), ET.tostring(el, encoding="unicode"))
                elif el.tag == "programme" and el.get("channel") not in got:
                    old.append(ET.tostring(el, encoding="unicode"))
                    el.clear()
    tmp = path + ".tmp"
    with lzma.open(tmp, "wt", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="epgpl (kandydaci na zadanie)">\n')
        for c in chans.values():
            f.write("  " + c.strip() + "\n")
        for p in new + old:
            f.write("  " + p.strip() + "\n")
        f.write("</tv>\n")
    os.replace(tmp, path)


def preview(target):
    tag, sid = target.split(":", 1)
    xid = "%s.%s" % (tag, re.sub(r"[^A-Za-z0-9-]", "_", sid))
    for name, src in GUIDES:
        guide = os.path.join(WWW, name)
        if not os.path.exists(guide):
            continue
        with lzma.open(guide) as f:
            progs = read_progs(f, xid)
        if progs and progs[-1]["start"] > time.time():
            what = {"plik": "plik programu", "teraz": "program kandydatow pobrany na zadanie",
                    "kandydaci": "program kandydatow z nocy"}[src]
            return {"ok": True, "from": "%s z %s" % (what, time.strftime("%d.%m %H:%M", time.localtime(os.path.getmtime(guide)))),
                    "programmes": progs}
    if not PLOCK.acquire(timeout=5):
        return {"ok": False, "error": "trwa inny podglad - sprobuj za chwile"}
    try:
        name = sid
        try:
            st = json.load(open(os.path.join(WWW, "stacje.json"), encoding="utf-8"))
            name = dict((a, b) for a, b in st.get(tag, [])).get(sid, sid)
        except Exception:
            pass
        repo = os.path.join(BASE, "iptv-epg")
        tmp = tempfile.mkdtemp(prefix="podglad-", dir=os.path.join(BASE, "work"))
        try:
            ch, out = os.path.join(tmp, "ch.xml"), os.path.join(tmp, "out.xml")
            with open(ch, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n  <channel site="%s" site_id=%s lang="pl" xmltv_id="%s">%s</channel>\n</channels>\n' % (
                    SITE_OF[tag], quoteattr(sid), xid, escape(name)))
            env = dict(os.environ, PATH=os.path.join(BASE, "node", "bin") + ":" + os.environ.get("PATH", "/usr/bin:/bin"), HOME=BASE)
            subprocess.run(["npm", "run", "--silent", "grab", "---", "--channels=" + ch, "--output=" + out,
                            "--days=2", "--maxConnections=2"], cwd=repo, env=env, timeout=150,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            progs = read_progs(out) if os.path.exists(out) else []
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if not progs:
            return {"ok": False, "error": "serwis nie zwrocil programu dla tej stacji"}
        return {"ok": True, "from": "pobrane teraz z serwisu", "programmes": progs}
    finally:
        PLOCK.release()


# --- pobieranie programu na zadanie ---
def grab_running():
    try:
        r = subprocess.run(["systemctl", "is-active", "epgpl.service", "epgpl-auto.service"],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10)
        return any(x.strip() in ("active", "activating", "reloading", "deactivating") for x in r.stdout.splitlines())
    except Exception:
        return False


def start_grab():
    if grab_running():
        return {"ok": False, "running": True, "error": "pobieranie juz trwa"}
    sc = shutil.which("systemctl") or "/usr/bin/systemctl"
    r = subprocess.run(["sudo", "-n", sc, "start", "--no-block", "epgpl.service"],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=20)
    try:
        with open(os.path.join(BASE, "work", "tuner_last"), "w") as f:
            f.write(str(int(time.time())))
    except Exception:
        pass
    if r.returncode:
        return {"ok": False, "error": "nie udalo sie uruchomic pobierania (uprawnienia - zainstaluj epgpl 0.9.0 ponownie): %s" % r.stdout.strip()[:200]}
    return {"ok": True}


SHORT = {"teleman.pl": "teleman", "programtv.onet.pl": "onet", "pilot.wp.pl": "wp",
         "programtv.naziemna.info": "naziemna", "canalplus.com": "canal+"}


def grab_progress(runline):
    """Postep pobierania z work/grab.log: dla kazdego serwisu ostatni licznik [i/n] grabbera
    (n = kanaly x dni), serwisy zakonczone wg logu przebiegu."""
    gl = os.path.join(BASE, "work", "grab.log")
    try:
        with open(gl, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 400000))
            tail = f.read().decode("utf-8", "ignore")
    except Exception:
        return ""
    tail = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", tail)          # kolory terminala
    last, order = {}, []
    for m in re.finditer(r"^\[([^\]]+)\][^\n]*?\[(\d+)/(\d+)\]", tail, re.M):
        site = m.group(1)
        if site not in last:
            order.append(site)
        last[site] = (int(m.group(2)), int(m.group(3)))
    if not last:
        return ""
    done = set()
    logs = sorted(glob.glob(os.path.join(BASE, "log", "run-*.log")), key=os.path.getmtime)
    if logs:
        try:
            done = set(re.findall(r"(\S+): gotowe w", open(logs[-1], encoding="utf-8", errors="ignore").read()))
        except Exception:
            pass
    parts = []
    for site in order:
        i, n = last[site]
        name = SHORT.get(site, site)
        parts.append("%s gotowe" % name if site in done else "%s %d%%" % (name, 100 * i // max(1, n)))
    return ", ".join(parts)


def state():
    out = {"ok": True, "running": grab_running(), "line": "", "result": "", "guide": 0}
    logs = sorted(glob.glob(os.path.join(BASE, "log", "run-*.log")), key=os.path.getmtime)
    if logs:
        try:
            lines = [x.strip() for x in open(logs[-1], encoding="utf-8", errors="ignore").read().splitlines() if x.strip()]
            out["line"] = lines[-1][:200] if lines else ""
        except Exception:
            pass
    if out["running"]:
        prog = grab_progress(out["line"])
        if prog:
            out["line"] = prog
    try:
        rl = open(os.path.join(WWW, "raport.txt"), encoding="utf-8", errors="ignore").read().splitlines()
        out["result"] = "\n".join(x.strip() for x in rl[:2] if x.strip())[:300]
    except Exception:
        pass
    g = os.path.join(WWW, "pl.xml.xz")
    if os.path.exists(g):
        out["guide"] = int(os.path.getmtime(g))
    try:
        out["full"] = json.load(open(os.path.join(WWW, "pelny.json"), encoding="utf-8"))   # ostatnie pelne pobranie
    except Exception:
        out["full"] = {}
    return out


def tuner_window():
    try:
        m = re.search(r"^TUNER_WINDOW=([0-9.]+)", open(os.path.join(BASE, "config.env")).read(), re.M)
        return float(m.group(1))
    except Exception:
        return 3.0


def local(handler):
    try:
        return ipaddress.ip_address(handler.client_address[0]).is_private
    except Exception:
        return False


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=WWW, **k)

    def log_message(self, fmt, *args):
        pass

    def reply(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if not u.path.startswith("/api/"):
            return super().do_GET()
        if not local(self):
            return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
        try:
            if u.path == "/api/wskazania":
                return self.reply(200, {"ok": True, "pins": load_tuner()})     # reczne wskazania (synchronizacja z EPG Scan)
            if u.path == "/api/stan":
                return self.reply(200, state())
            if u.path == "/api/podglad":
                target = (urllib.parse.parse_qs(u.query).get("target") or [""])[0].strip()
                if not TARGET.match(target):
                    return self.reply(400, {"ok": False, "error": "zle wskazanie"})
                return self.reply(200, preview(target))
            if u.path == "/api/kandydaci":
                with TLOCK:
                    return self.reply(200, dict(TSTATE, ok=True))
            if u.path == "/api/staty":
                ts = [x.strip() for x in (urllib.parse.parse_qs(u.query).get("t") or [""])[0].split(",") if x.strip()]
                ts = [x for x in ts if TARGET.match(x)][:200]
                return self.reply(200, stats(ts))
            return self.reply(404, {"ok": False, "error": "nieznany adres"})
        except subprocess.TimeoutExpired:
            return self.reply(200, {"ok": False, "error": "serwis nie odpowiedzial w czasie"})
        except Exception as e:
            return self.reply(500, {"ok": False, "error": str(e)})

    def package(self, u):
        """Pobieranie w jednym miejscu (EPG Scan):
        POST /api/zajety {"until": czas}          dekoder pobiera - harmonogram serwera czeka (jedno_miejsce.py)
        POST /api/paczka?plik=pl.xml.xz|pl.channels.xml.xz|kandydaci.xml.xz   (tresc pliku, xz)
        POST /api/paczka?gotowe=1 {"days", "channels", "total"}   paczka dekodera -> www (zastepuje plik serwera)
        POST /api/teraz                           (XMLTV xz) program kandydatow pobrany na dekoderze na zadanie"""
        q = urllib.parse.parse_qs(u.query)
        n = int(self.headers.get("Content-Length", "0"))
        if n < 0 or n > 60 * 1024 * 1024:
            return {"ok": False, "error": "zly rozmiar"}
        body = self.rfile.read(n) if n else b""
        pdir = os.path.join(BASE, "work", "paczka")
        os.makedirs(pdir, exist_ok=True)
        if u.path == "/api/zajety":
            req = json.loads(body.decode("utf-8") or "{}")
            with open(os.path.join(BASE, "work", "dekoder_pobiera.json"), "w") as f:
                json.dump({"until": int(req.get("until") or 0), "ip": self.client_address[0]}, f)
            return {"ok": True}
        if u.path == "/api/teraz":
            tmp = os.path.join(pdir, "teraz.xml")
            with open(tmp, "wb") as f:
                f.write(lzma.decompress(body))
            try:
                merge_teraz([tmp])
            finally:
                os.remove(tmp)
            return {"ok": True}
        name = (q.get("plik") or [""])[0]
        if name:
            if name not in ("pl.xml.xz", "pl.channels.xml.xz", "kandydaci.xml.xz"):
                return {"ok": False, "error": "zly plik"}
            if body[:6] != b"\xfd7zXZ\x00":
                return {"ok": False, "error": "to nie jest plik xz"}
            with open(os.path.join(pdir, name), "wb") as f:
                f.write(body)
            return {"ok": True, "plik": name, "bytes": len(body)}
        if (q.get("gotowe") or [""])[0]:
            req = json.loads(body.decode("utf-8") or "{}")
            for need in ("pl.xml.xz", "pl.channels.xml.xz"):
                if not os.path.exists(os.path.join(pdir, need)):
                    return {"ok": False, "error": "brak %s w paczce" % need}
            has_cand = os.path.exists(os.path.join(pdir, "kandydaci.xml.xz"))
            for name in ("pl.channels.xml.xz", "kandydaci.xml.xz", "pl.xml.xz"):
                p = os.path.join(pdir, name)
                if os.path.exists(p):
                    os.replace(p, os.path.join(WWW, name))
            full = {"time": int(time.time()), "source": "dekoder", "days": int(req.get("days") or 0),
                    "channels": int(req.get("channels") or 0), "total": int(req.get("total") or 0),
                    "ip": self.client_address[0]}
            with open(os.path.join(WWW, ".pelny.json"), "w") as f:
                json.dump(full, f)
            os.replace(os.path.join(WWW, ".pelny.json"), os.path.join(WWW, "pelny.json"))
            with open(os.path.join(BASE, "log", "pominiete.log"), "a") as f:
                f.write("%s paczka z dekodera %s: program na %d dni, kanalow %d/%d%s\n" % (
                    time.strftime("%d.%m %H:%M"), full["ip"], full["days"], full["channels"], full["total"],
                    "" if has_cand else " (bez kandydatow - liczone na serwerze)"))
            if not has_cand:
                subprocess.Popen(["bash", os.path.join(BASE, "kandydaci.sh")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return {"ok": True}
        return {"ok": False, "error": "nieznane zadanie"}

    def do_POST(self):
        if self.path == "/api/pobierz":
            if not local(self):
                return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
            try:
                return self.reply(200, start_grab())
            except Exception as e:
                return self.reply(500, {"ok": False, "error": str(e)})
        u = urllib.parse.urlparse(self.path)
        if u.path in ("/api/paczka", "/api/zajety", "/api/teraz"):
            if not local(self):
                return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
            try:
                return self.reply(200, self.package(u))
            except Exception as e:
                return self.reply(500, {"ok": False, "error": str(e)})
        if self.path == "/api/kandydaci":
            if not local(self):
                return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
                req = json.loads(self.rfile.read(n).decode("utf-8")) if 0 < n <= 65536 else {}
                ts = [t for t in (req.get("targets") or []) if isinstance(t, str) and TARGET.match(t)][:60]
                if not ts:
                    return self.reply(400, {"ok": False, "error": "brak stacji"})
                return self.reply(200, teraz_start(ts))
            except Exception as e:
                return self.reply(500, {"ok": False, "error": str(e)})
        if self.path == "/api/tuner":
            if not local(self):
                return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
                req = json.loads(self.rfile.read(n).decode("utf-8")) if 0 < n <= 4096 else {}
                t = {"grab": bool(req.get("grab")), "next": int(req.get("next") or 0),
                     "seen": int(time.time()), "ip": self.client_address[0]}
                tmp = os.path.join(BASE, "work", "tuner.json.tmp")
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(t, f)
                os.replace(tmp, os.path.join(BASE, "work", "tuner.json"))
                return self.reply(200, {"ok": True, "window": tuner_window()})
            except Exception as e:
                return self.reply(500, {"ok": False, "error": str(e)})
        if self.path == "/api/bukiety":
            # bukiety do dopasowania wybrane na dekoderze: {"mode": "all"|"selected", "names": [...]}
            if not local(self):
                return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
                req = json.loads(self.rfile.read(n).decode("utf-8")) if 0 < n <= 65536 else {}
                mode = "selected" if req.get("mode") == "selected" else "all"
                names = [str(x)[:100] for x in (req.get("names") or [])][:200]
                out = {"mode": mode, "names": names}
                sites = [t for t in (req.get("sites") or []) if t in ("tm", "onet", "wp", "naz")]
                if sites:
                    out["sites"] = list(dict.fromkeys(sites))   # kolejnosc serwisow z dekodera (centrum ustawien)
                if "cand" in req:
                    out["cand"] = bool(req.get("cand"))             # codzienny program kandydatow (EPG Scan)
                if "one_place" in req:
                    out["one_place"] = bool(req.get("one_place"))   # pobieranie w jednym miejscu (EPG Scan)
                tmp = os.path.join(BASE, "work", "bukiety.json.tmp")
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(out, f, ensure_ascii=False)
                os.replace(tmp, os.path.join(BASE, "work", "bukiety.json"))
                rebuild()
                return self.reply(200, {"ok": True, "mode": mode, "names": len(names), "sites": out.get("sites", [])})
            except Exception as e:
                return self.reply(500, {"ok": False, "error": str(e)})
        if self.path != "/api/alias":
            return self.reply(404, {"ok": False, "error": "nieznany adres"})
        try:
            if not ipaddress.ip_address(self.client_address[0]).is_private:
                return self.reply(403, {"ok": False, "error": "tylko z sieci lokalnej"})
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > 65536:
                return self.reply(400, {"ok": False, "error": "zly rozmiar"})
            req = json.loads(self.rfile.read(n).decode("utf-8"))
            # jedno wskazanie {"name", "target"} albo wiele naraz {"items": [...]} (synchronizacja z dekoderem) - jedno przeliczenie mapy
            items = req.get("items") if isinstance(req.get("items"), list) else [req]
            if len(items) > 500:
                return self.reply(400, {"ok": False, "error": "za duzo wskazan"})
            try:
                st = json.load(open(os.path.join(WWW, "stacje.json"), encoding="utf-8"))
            except FileNotFoundError:
                st = None
            good, errors = [], []
            for it in items:
                name = str((it or {}).get("name", "")).strip()
                target = str((it or {}).get("target", "")).strip()
                if not name or len(name) > 100 or re.search(r"[\r\n=#]", name):
                    errors.append({"name": name, "error": "zla nazwa kanalu"})
                    continue
                if target not in ("", "-") and not TARGET.match(target):
                    # wskazanie inna nazwa kanalu ("= Nazwa w serwisach") - jak w wskazania.txt
                    if len(target) > 100 or re.search(r"[\r\n=#]", target) or ":" in target:
                        errors.append({"name": name, "error": "zle wskazanie"})
                        continue
                elif target not in ("", "-"):
                    tag, sid = target.split(":", 1)
                    if st is not None and sid not in {x[0] for x in st.get(tag, [])}:
                        errors.append({"name": name, "error": "nie ma takiej stacji w serwisie na serwerze"})
                        continue
                good.append((name, target))
            if not good:
                return self.reply(400, {"ok": False, "error": errors[0]["error"] if errors else "brak wskazan", "errors": errors})
            with LOCK:
                d = load_tuner()
                for name, target in good:
                    if target:
                        d[name] = target
                    else:
                        d.pop(name, None)
                save_tuner(d)
            rebuild()
            return self.reply(200, {"ok": True, "applied": len(good), "errors": errors})
        except Exception as e:
            return self.reply(500, {"ok": False, "error": str(e)})


if __name__ == "__main__":
    srv = http.server.ThreadingHTTPServer(("", port()), Handler)
    srv.serve_forever()
