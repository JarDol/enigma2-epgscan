#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - porownanie zrodel programu (onet, teleman, WP Pilot) na tych samych kanalach.
# Miary: liczba audycji, opisy, czas pobierania i zgodnosc z EPG z satelity (godzina startu, tytul).
# Uzycie (jako uzytkownik epgpl):  python3 /opt/epgpl/tools/porownaj.py [--kanalow 12] [--dni 2]
#   --serwisy onet,naz      tylko te serwisy (kanaly musza byc we wszystkich wybranych)
#   --filtr "TVP 3"         tylko kanaly, ktorych nazwa zawiera ten tekst (wyrazenie regularne, bez wielkosci liter)
#   --opisy                 onet z pelnymi opisami (wolniej; domyslnie opisy z samej listy)
#   --iptv                  takze kanaly IPTV z bukietow (bez EPG z satelity - porownanie serwisow miedzy soba)
import argparse, difflib, json, os, re, subprocess, sys, time, urllib.parse, urllib.request
import xml.etree.ElementTree as ET

BASE = os.environ.get("EPGPL_BASE", "/opt/epgpl")
sys.path.insert(0, BASE)
import build_map as bm  # noqa: E402

SITES = [("onet", "programtv.onet.pl"), ("tm", "teleman.pl"), ("wp", "pilot.wp.pl"), ("naz", "programtv.naziemna.info")]
NAMES = {"onet": "onet.pl", "tm": "teleman", "wp": "WP Pilot", "naz": "naziemna"}


def cfg(name, default):
    try:
        for ln in open(os.path.join(BASE, "config.env"), encoding="utf-8"):
            m = re.match(r"^%s=([^#\s]*)" % name, ln)
            if m:
                return m.group(1).strip('"')
    except Exception:
        pass
    return default


def tnorm(t):
    t = re.sub(r"[^\w]+", " ", (t or "").lower()).strip()
    t = re.sub(r"\b(odc|sezon|cz)\b.*$", "", t).strip()
    return t


def similar(a, b):
    a, b = tnorm(a), tnorm(b)
    if not a or not b:
        return False
    if a.startswith(b) or b.startswith(a):
        return True
    return difflib.SequenceMatcher(None, a, b).ratio() >= 0.6


def xmltime(s):
    import calendar
    m = re.match(r"(\d{14})\s*([+-]\d{4})?", s or "")
    if not m:
        return None
    t = calendar.timegm(time.strptime(m.group(1), "%Y%m%d%H%M%S"))
    if m.group(2):
        o = int(m.group(2))
        t -= (1 if o >= 0 else -1) * ((abs(o) // 100) * 3600 + (abs(o) % 100) * 60)
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kanalow", type=int, default=12)
    ap.add_argument("--dni", type=int, default=2)
    ap.add_argument("--polaczen", type=int, default=int(cfg("MAX_CONNECTIONS", "2")))
    ap.add_argument("--serwisy", default="")
    ap.add_argument("--filtr", default="")
    ap.add_argument("--opisy", action="store_true")
    ap.add_argument("--iptv", action="store_true")
    a = ap.parse_args()
    global SITES
    if a.serwisy:
        want = [bm.SITE_ALIASES.get(x.strip(), x.strip()) for x in a.serwisy.split(",") if x.strip()]
        SITES = [x for x in SITES if x[0] in want]
        if not SITES:
            sys.exit("nieznane serwisy: %s (onet, tm, wp, naz)" % a.serwisy)
    receiver = cfg("RECEIVER", "")
    repo = os.path.join(BASE, "iptv-epg")
    work = os.path.join(BASE, "work", "porownanie")
    os.makedirs(work, exist_ok=True)

    # wlasne grabbery (teleman, WP Pilot) do repozytorium iptv-org - normalnie robi to run.sh
    import shutil
    shutil.copytree(os.path.join(BASE, "sites"), os.path.join(repo, "sites"), dirs_exist_ok=True)
    onet = os.path.join(repo, "sites/programtv.onet.pl/programtv.onet.pl.config.js")
    try:
        js = open(onet, encoding="utf-8").read()
        if not a.opisy and "const details = await loadProgramDetails(programUrl)" in js:
            open(onet, "w", encoding="utf-8").write(js.replace("const details = await loadProgramDetails(programUrl)", "const details = null"))
    except Exception:
        pass

    services = bm.load_services(os.path.join(BASE, "work", "kanaly.json"))
    mine, seen = [], set()
    for b in services:
        if b["servicename"] in ("Last Scanned",):
            continue
        for s in b.get("subservices", []):
            ref = s.get("servicereference", "")
            if bm.is_tv(ref) and bm.norm(s["servicename"]) not in seen and (a.iptv or "%3a" not in ref.lower()):
                if a.filtr and not re.search(a.filtr, s["servicename"], re.I):
                    continue
                seen.add(bm.norm(s["servicename"]))
                mine.append((s["servicename"], bm.ref10(ref)))
    sites = {}
    for tag, site in SITES:
        path = "sites/%s/%s.channels.xml" % (site, site)
        sites[tag] = bm.load_site(repo, tag, site, path)
    def find(t, n):
        """Nazwa dokladnie albo jednoznaczne rozszerzenie nazwy (jak w build_map)."""
        it = sites[t].get(bm.norm(n))
        if it:
            return it
        st = bm.ctoks(n)
        cand = [x for x in sites[t].values() if bm.extends(st, bm.ctoks(x["name"]))]
        return cand[0] if len(cand) == 1 else None
    common = []
    for n, r in mine:
        found = {t: find(t, n) for t, _ in SITES}
        if all(found.values()):
            common.append((n, r, found))
    print("Kanalow z Twoich bukietow dostepnych we wszystkich %d serwisach: %d" % (len(SITES), len(common)))
    for t, _ in SITES:
        print("  dostepnych w %-8s: %d" % (NAMES[t], len([1 for n, r in mine if find(t, n)])))
    if not common:
        sys.exit("brak wspolnych kanalow")
    step = max(1, len(common) // a.kanalow)
    pick = common[::step][:a.kanalow]
    print("Test na %d kanalach, %d dni, polaczen %d:\n  %s\n" % (len(pick), a.dni, a.polaczen, ", ".join(p[0] for p in pick)))

    # pobranie z kazdego serwisu
    env = dict(os.environ, PATH=os.path.join(BASE, "node", "bin") + ":" + os.environ.get("PATH", ""), HOME=BASE)
    res = {}
    for tag, site in SITES:
        chf = os.path.join(work, "%s.channels.xml" % tag)
        out = os.path.join(work, "%s.xml" % tag)
        with open(chf, "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n')
            for i, (n, r, it) in enumerate(pick):
                f.write('  <channel site="%s" site_id="%s" lang="pl" xmltv_id="k%d">%s</channel>\n' % (
                    site, it[tag]["site_id"], i, n))
            f.write("</channels>\n")
        if os.path.exists(out):
            os.remove(out)
        print("Pobieranie: %s ..." % NAMES[tag], flush=True)
        t0 = time.time()
        p = subprocess.run(["npm", "run", "--silent", "grab", "---", "--channels=" + chf, "--output=" + out,
                            "--days=%d" % a.dni, "--maxConnections=%d" % a.polaczen],
                           cwd=repo, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        dt = time.time() - t0
        errs = len(re.findall(r"ERR:", p.stdout))
        progs = {}
        try:
            for ev, el in ET.iterparse(out):
                if el.tag == "programme":
                    progs.setdefault(el.get("channel"), []).append((
                        xmltime(el.get("start")), xmltime(el.get("stop")), el.findtext("title") or "",
                        len((el.findtext("desc") or "").strip()), bool((el.findtext("sub-title") or "").strip())))
                    el.clear()
        except Exception as e:
            print("  blad odczytu %s: %s" % (out, e))
        res[tag] = (dt, errs, progs)

    # EPG z satelity (OpenWebif) - okno: teraz .. +24 h
    now = time.time()
    sat = {}
    for i, (n, r, it) in enumerate(pick):
        try:
            u = "http://%s/api/epgservice?sRef=%s" % (receiver, urllib.parse.quote(r, safe=""))
            with urllib.request.urlopen(u, timeout=30) as f:
                evs = json.loads(f.read().decode("utf-8", "ignore")).get("events", [])
            sat["k%d" % i] = [(int(e["begin_timestamp"]), e.get("title", "")) for e in evs
                              if now <= int(e["begin_timestamp"]) <= now + 86400]
        except Exception:
            sat["k%d" % i] = []

    # wyniki
    print("\n%-10s %7s %6s %10s %7s %8s %8s %10s %10s" % ("serwis", "czas", "bledy", "audycji/d", "opisy", "zn.opisu", "podtyt.", "godz.=sat", "tytul=sat"))
    for tag, _ in SITES:
        dt, errs, progs = res[tag]
        allp = [x for v in progs.values() for x in v]
        nd = max(1, a.dni) * len(pick)
        tm = tt = tot = 0
        for k, sv in sat.items():
            pv = progs.get(k, [])
            for st, title in sv:
                tot += 1
                near = [x for x in pv if x[0] is not None and abs(x[0] - st) <= 300]
                if near:
                    tm += 1
                    if any(similar(title, x[2]) for x in near):
                        tt += 1
        pc = lambda x, y: ("%d%%" % round(100.0 * x / y)) if y else "-"
        print("%-10s %6ds %6d %10.1f %7s %8d %8s %10s %10s" % (
            NAMES[tag], dt, errs, len(allp) / float(nd), pc(sum(1 for x in allp if x[3]), len(allp)),
            sum(x[3] for x in allp) // max(1, len(allp)),
            pc(sum(1 for x in allp if x[4]), len(allp)), pc(tm, tot), pc(tt, tot)))
    print("\n(godz.=sat: audycje z satelity w ciagu 24 h, dla ktorych serwis ma audycje startujaca +-5 min;")
    print(" tytul=sat: to samo i podobny tytul; audycji z satelity w porownaniu: %d)" % sum(len(v) for v in sat.values()))
    print("\nNa kanal (audycji w %d dni: %s; zgodnosc godzin z satelita):" % (a.dni, " / ".join(NAMES[t] for t, _ in SITES)))
    for i, (n, r, it) in enumerate(pick):
        k = "k%d" % i
        cnt = " / ".join("%3d" % len(res[t][2].get(k, [])) for t, _ in SITES)
        m = []
        for t, _ in SITES:
            pv = res[t][2].get(k, [])
            sv = sat.get(k, [])
            hit = sum(1 for st, _ in sv if any(x[0] is not None and abs(x[0] - st) <= 300 for x in pv))
            m.append(("%d/%d" % (hit, len(sv))) if sv else "-")
        print("  %-26s %s   %s" % (n[:26], cnt, "  ".join(m)))
    # zgodnosc serwisow miedzy soba (wazne dla kanalow bez EPG z satelity, np. IPTV)
    if len(SITES) >= 2:
        print("\nZgodnosc serwisow miedzy soba (audycje w ciagu 24 h, start +-5 min / i podobny tytul):")
        for i1 in range(len(SITES)):
            for i2 in range(i1 + 1, len(SITES)):
                t1, t2 = SITES[i1][0], SITES[i2][0]
                tot = tm = tt = 0
                for i in range(len(pick)):
                    k = "k%d" % i
                    p2 = res[t2][2].get(k, [])
                    for st, _, title, _, _ in res[t1][2].get(k, []):
                        if st is None or not (now <= st <= now + 86400):
                            continue
                        tot += 1
                        near = [x for x in p2 if x[0] is not None and abs(x[0] - st) <= 300]
                        if near:
                            tm += 1
                            if any(similar(title, x[2]) for x in near):
                                tt += 1
                pc = lambda x, y: ("%d%%" % round(100.0 * x / y)) if y else "-"
                print("  %s -> %s: %s / %s  (audycji %s: %d)" % (NAMES[t1], NAMES[t2], pc(tm, tot), pc(tt, tot), NAMES[t1], tot))
    # przyklad tej samej audycji w kazdym serwisie (pierwszy kanal, audycja ok. 20:00)
    k = "k0"
    target = None
    for st, title in sat.get(k, []):
        if time.localtime(st).tm_hour >= 20:
            target = (st, title)
            break
    src = "satelita"
    if not target:                                   # bez EPG z satelity - wg pierwszego serwisu
        src = NAMES[SITES[0][0]]
        for st, _, title, _, _ in sorted(x for x in res[SITES[0][0]][2].get(k, []) if x[0]):
            if st >= now and time.localtime(st).tm_hour >= 20:
                target = (st, title)
                break
    if target:
        print("\nPrzyklad - %s, %s (%s: %s):" % (pick[0][0], time.strftime("%d.%m %H:%M", time.localtime(target[0])), src, target[1]))
        for t, _ in SITES:
            near = [x for x in res[t][2].get(k, []) if x[0] is not None and abs(x[0] - target[0]) <= 300]
            print("  %-9s %s" % (NAMES[t], (near[0][2] + "  [opis: %d znakow]" % near[0][3]) if near else "-"))


if __name__ == "__main__":
    main()
