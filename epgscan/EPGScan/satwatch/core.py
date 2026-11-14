# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""
SatWatch - rdzen (bez zaleznosci od Enigma2, mozna testowac na PC).

Funkcje:
  * pobranie satellites.xml z ETag (If-None-Match) i walidacja,
  * diff transponderow dla wybranych pozycji (z tolerancja czestotliwosci),
  * opcjonalna aktualizacja /etc/enigma2/satellites.xml (kopia, zapis atomowy),
  * zrzut lamedb + bukietow i porownanie zrzutow (zgubione kanaly z pozycja
    w bukiecie, dawna nazwa i kandydaci do podmiany).

Python 3.9 (OpenPLi 9.2).
"""
from __future__ import print_function

import difflib
import hashlib
import json
try:
    from ..i18n import _
except (ImportError, ValueError):
    _ = lambda message: message

import os
import re
import shutil
import ssl
import time
import xml.etree.ElementTree as ET
from urllib.error import HTTPError
from urllib.request import Request, urlopen

VERSION = "0.4.2"

DEFAULT_URL = "https://raw.githubusercontent.com/OpenPLi/tuxbox-xml/master/xml/satellites.xml"
E2DIR = "/etc/enigma2"
DATA_DIR = "/etc/enigma2/satwatch"
LOG_PREF = ""   # wybrany katalog logu i historii raportow (np. /media/hdd/satwatch); "" = DATA_DIR
TARGET = "/etc/enigma2/satellites.xml"
DEFAULT_IGNORE = "Last Scanned"
SYSTEM = "/etc/tuxbox/satellites.xml"

POL = {0: "H", 1: "V", 2: "L", 3: "R"}
SYSNAME = {0: "S", 1: "S2"}
MODNAME = {0: "auto", 1: "QPSK", 2: "8PSK", 3: "QAM16", 4: "16APSK", 5: "32APSK"}

# pola porownywane (oprocz czestotliwosci i polaryzacji, ktore sa kluczem)
CMP_FIELDS = ("symbol_rate", "fec_inner", "system", "modulation", "rolloff",
              "pilot", "inversion", "is_id", "pls_mode", "pls_code",
              "t2mi_plp_id", "t2mi_pid")
# wartosci uznawane za "brak atrybutu" - jesli po jednej stronie atrybutu nie ma,
# a po drugiej jest jedna z tych wartosci, nie traktujemy tego jako zmiany
NEUTRAL = {
    "rolloff": ("0",), "pilot": ("2",), "inversion": ("2",),
    "is_id": ("-1",), "pls_mode": ("0", "1"), "pls_code": ("0", "1"),
    "t2mi_plp_id": ("-1",), "t2mi_pid": ("4096",),
}
FIELD_LABEL = {
    "symbol_rate": "SR", "fec_inner": "FEC", "system": "system",
    "modulation": "modulacja", "rolloff": "rolloff", "pilot": "pilot",
    "inversion": "inwersja", "is_id": "is_id", "pls_mode": "pls_mode",
    "pls_code": "pls_code", "t2mi_plp_id": "t2mi_plp", "t2mi_pid": "t2mi_pid",
}

_CTRL = re.compile(u"[\u0080-\u009f]")


# --------------------------------------------------------------------------
# narzedzia
# --------------------------------------------------------------------------

def ensure_dir(path):
    if not os.path.isdir(path):
        os.makedirs(path)


def atomic_write(path, data):
    """Zapis atomowy: plik tymczasowy w tym samym katalogu + rename."""
    if isinstance(data, str):
        data = data.encode("utf-8")
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.rename(tmp, path)


def load_json(path, default):
    try:
        with open(path, "r") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, obj):
    atomic_write(path, json.dumps(obj, ensure_ascii=False, indent=1))


def clean_name(s):
    return _CTRL.sub("", s or "").strip()


def pos_str(pos):
    pos = int(pos)
    if pos > 1800:
        pos -= 3600
    return "%.1f%s" % (abs(pos) / 10.0, "E" if pos >= 0 else "W")


def norm_pos(pos):
    pos = int(pos)
    if pos > 1800:
        pos -= 3600
    return pos


def parse_positions(text):
    """'130, 19.2E, 5.0W' -> [130, 192, -50]"""
    out = []
    for part in re.split(r"[,;\s]+", text or ""):
        part = part.strip().upper()
        if not part:
            continue
        m = re.match(r"^(-?\d+(?:\.\d+)?)\s*([EW])?$", part)
        if not m:
            continue
        val, ew = m.group(1), m.group(2)
        if ew or "." in val:
            p = int(round(float(val) * 10))
            if ew == "W":
                p = -p
        else:
            p = int(val)
        if p not in out:
            out.append(p)
    return out


def tp_str(tp):
    """dict z atrybutami satellites.xml -> '11034 V 27500 S2 8PSK 3/4'"""
    try:
        s = "%d %s %d" % (int(tp["frequency"]) // 1000,
                          POL.get(int(tp.get("polarization", 0)), "?"),
                          int(tp["symbol_rate"]) // 1000)
        s += " DVB-%s" % SYSNAME.get(int(tp.get("system", 0)), "?")
        s += " %s" % MODNAME.get(int(tp.get("modulation", 0)), tp.get("modulation"))
        if tp.get("is_id") not in (None, "-1"):
            s += " MIS:%s" % tp["is_id"]
        if tp.get("t2mi_plp_id") not in (None, "-1"):
            s += " T2MI:%s" % tp["t2mi_plp_id"]
        return s
    except Exception:
        return str(tp)


# --------------------------------------------------------------------------
# satellites.xml
# --------------------------------------------------------------------------

def parse_sat_xml(data_or_path):
    """Zwraca (root_element, {pos: sat_element})."""
    if isinstance(data_or_path, bytes):
        root = ET.fromstring(data_or_path)
    else:
        root = ET.parse(data_or_path).getroot()
    if root.tag != "satellites":
        raise ValueError(_("to nie jest satellites.xml (root <%s>)") % root.tag)
    sats = {}
    for sat in root.findall("sat"):
        try:
            sats[norm_pos(sat.get("position"))] = sat
        except (TypeError, ValueError):
            pass
    if not sats:
        raise ValueError(_("brak elementow <sat> w pliku"))
    return root, sats


def sat_tps(sat_el):
    if sat_el is None:
        return []
    return [dict(t.attrib) for t in sat_el.findall("transponder")]


def local_xml_path():
    """Plik, z ktorego korzysta enigma: /etc/enigma2 ma pierwszenstwo."""
    if os.path.exists(TARGET):
        return TARGET
    return SYSTEM


def _field_differs(field, a, b):
    va, vb = a.get(field), b.get(field)
    if va == vb:
        return False
    if va is None or vb is None:
        present = vb if va is None else va
        return present not in NEUTRAL.get(field, ())
    return True


def diff_tps(old, new, tol_khz=3000):
    """
    old/new: listy dictow (atrybuty transponderow jednej pozycji).
    Klucz dopasowania: polaryzacja + is_id, czestotliwosc z tolerancja.
    Zwraca dict: added, removed, changed[(old,new,[pola])], shifted[(old,new)]
    """
    def key(tp):
        return (tp.get("polarization", "0"), tp.get("is_id", "-1"),
                tp.get("t2mi_plp_id", "-1"))

    groups = {}
    for i, tp in enumerate(old):
        groups.setdefault(key(tp), ([], []))[0].append(i)
    for j, tp in enumerate(new):
        groups.setdefault(key(tp), ([], []))[1].append(j)

    pairs = []
    for k, (oi, nj) in groups.items():
        cand = []
        for i in oi:
            fo = int(old[i]["frequency"])
            for j in nj:
                d = abs(fo - int(new[j]["frequency"]))
                if d <= tol_khz:
                    cand.append((d, i, j))
        cand.sort()
        used_o, used_n = set(), set()
        for d, i, j in cand:
            if i in used_o or j in used_n:
                continue
            used_o.add(i)
            used_n.add(j)
            pairs.append((i, j))

    matched_o = set(i for i, j in pairs)
    matched_n = set(j for i, j in pairs)
    res = {"added": [], "removed": [], "changed": [], "shifted": []}
    for i, j in pairs:
        a, b = old[i], new[j]
        fields = [f for f in CMP_FIELDS if _field_differs(f, a, b)]
        if fields:
            res["changed"].append((a, b, fields))
        elif a.get("frequency") != b.get("frequency"):
            res["shifted"].append((a, b))
    res["removed"] = [old[i] for i in range(len(old)) if i not in matched_o]
    res["added"] = [new[j] for j in range(len(new)) if j not in matched_n]
    for k in ("added", "removed"):
        res[k].sort(key=lambda t: int(t["frequency"]))
    for k in ("changed", "shifted"):
        res[k].sort(key=lambda p: int(p[0]["frequency"]))
    return res


def diff_count(d):
    return len(d["added"]) + len(d["removed"]) + len(d["changed"]) + len(d["shifted"])


def build_output_xml(local_root, remote_root, remote_sats, positions, mode, source):
    """
    mode 'positions': plik lokalny, w ktorym sekcje wybranych pozycji zastapiono
                      sekcjami z pliku zdalnego (pozostale pozycje bez zmian).
    mode 'full':      caly plik zdalny.
    """
    import copy
    if mode == "full":
        root = copy.deepcopy(remote_root)
    else:
        root = ET.Element("satellites")
        done = set()
        if local_root is not None:
            for sat in local_root.findall("sat"):
                p = norm_pos(sat.get("position"))
                if p in positions and p in remote_sats:
                    root.append(copy.deepcopy(remote_sats[p]))
                    done.add(p)
                else:
                    root.append(copy.deepcopy(sat))
        for p in positions:
            if p not in done and p in remote_sats:
                root.append(copy.deepcopy(remote_sats[p]))
    # komentarz z informacja o zrodle
    comment = ET.Comment(" SatWatch %s | zrodlo: %s | %s | tryb: %s "
                         % (VERSION, source, time.strftime("%Y-%m-%d %H:%M"), mode))
    root.insert(0, comment)
    if hasattr(ET, "indent"):
        ET.indent(root, space="\t")
    body = ET.tostring(root, encoding="unicode")
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + body + "\n"


def backup_and_write(content, backups=5):
    bdir = os.path.join(DATA_DIR, "backup")
    ensure_dir(bdir)
    src = local_xml_path()
    if os.path.exists(src):
        name = "satellites.xml.%s" % time.strftime("%Y%m%d-%H%M%S")
        shutil.copy2(src, os.path.join(bdir, name))
    files = sorted(f for f in os.listdir(bdir) if f.startswith("satellites.xml."))
    for f in files[:-backups] if backups > 0 else []:
        try:
            os.remove(os.path.join(bdir, f))
        except OSError:
            pass
    atomic_write(TARGET, content)


def list_backups():
    bdir = os.path.join(DATA_DIR, "backup")
    if not os.path.isdir(bdir):
        return []
    return sorted((f for f in os.listdir(bdir) if f.startswith("satellites.xml.")), reverse=True)


def restore_backup(name):
    src = os.path.join(DATA_DIR, "backup", name)
    with open(src, "rb") as f:
        atomic_write(TARGET, f.read())


# --------------------------------------------------------------------------
# pobieranie
# --------------------------------------------------------------------------

def download(url, state, verify=True, timeout=60):
    """
    Zwraca (bytes, changed). Jesli serwer odpowie 304, zwraca kopie z cache.
    state: dict (etag, url) - aktualizowany na miejscu.
    """
    cache = os.path.join(DATA_DIR, "remote_satellites.xml")
    headers = {"User-Agent": "SatWatch/%s (Enigma2)" % VERSION}
    if state.get("url") == url and state.get("etag") and os.path.exists(cache):
        headers["If-None-Match"] = state["etag"]
    ctx = ssl.create_default_context() if verify else ssl._create_unverified_context()
    try:
        resp = urlopen(Request(url, headers=headers), timeout=timeout, context=ctx)
        data = resp.read()
        etag = resp.headers.get("ETag")
    except HTTPError as e:
        if e.code == 304:
            with open(cache, "rb") as f:
                return f.read(), False
        raise
    parse_sat_xml(data)  # walidacja - rzuci wyjatek dla smieci
    ensure_dir(DATA_DIR)
    atomic_write(cache, data)
    state["url"] = url
    state["etag"] = etag
    state["remote_sha"] = hashlib.sha256(data).hexdigest()
    state["remote_time"] = time.strftime("%Y-%m-%d %H:%M:%S")
    return data, True


# --------------------------------------------------------------------------
# lamedb
# --------------------------------------------------------------------------

def _hex(s):
    return int(s, 16)


def _parse_s_params(s):
    """'10719000:27500000:1:4:130:2:0:1:2:0:2' -> dict"""
    p = s.split(":")
    d = {"freq": int(p[0]), "sr": int(p[1]), "pol": int(p[2]), "fec": int(p[3]),
         "pos": norm_pos(p[4])}
    if len(p) > 7:
        d["sys"] = int(p[7])
    if len(p) > 8:
        d["mod"] = int(p[8])
    return d


def tp_short(t):
    if not t:
        return "?"
    s = "%d %s %d" % (t["freq"] // 1000, POL.get(t["pol"], "?"), t["sr"] // 1000)
    if "sys" in t:
        s += " DVB-%s" % SYSNAME.get(t["sys"], "?")
    return s + " " + pos_str(t["pos"])


def parse_lamedb(e2dir=None):
    e2dir = e2dir or E2DIR
    """
    Zwraca (tps, services):
      tps:      {(ns,tsid,onid): {freq,sr,pol,fec,pos,sys,mod}}  (tylko DVB-S)
      services: {(sid,ns,tsid,onid): {name, provider, type}}
    Obsluguje lamedb (v4) i lamedb5.
    """
    p4 = os.path.join(e2dir, "lamedb")
    p5 = os.path.join(e2dir, "lamedb5")
    if os.path.exists(p4):
        return _parse_lamedb4(p4)
    if os.path.exists(p5):
        return _parse_lamedb5(p5)
    return {}, {}


def _parse_lamedb4(path):
    tps, services = {}, {}
    with open(path, "rb") as f:
        lines = f.read().decode("utf-8", "replace").splitlines()
    i, n = 0, len(lines)
    while i < n and lines[i].strip() != "transponders":
        i += 1
    i += 1
    while i < n and lines[i].strip() != "end":
        key = lines[i].strip().split(":")
        i += 1
        if len(key) >= 3:
            k = (_hex(key[0]), _hex(key[1]), _hex(key[2]))
            while i < n and lines[i].strip() != "/":
                ln = lines[i].strip()
                if ln.startswith("s "):
                    try:
                        tps[k] = _parse_s_params(ln[2:])
                    except Exception:
                        pass
                i += 1
        i += 1  # "/"
    while i < n and lines[i].strip() != "services":
        i += 1
    i += 1
    while i + 2 < n + 1 and i < n and lines[i].strip() != "end":
        key = lines[i].strip().split(":")
        name = lines[i + 1] if i + 1 < n else ""
        extra = lines[i + 2] if i + 2 < n else ""
        i += 3
        if len(key) < 5:
            continue
        try:
            k = (_hex(key[0]), _hex(key[1]), _hex(key[2]), _hex(key[3]))
            stype = int(key[4])
            srcid = _hex(key[6]) if len(key) > 6 and key[6] else 0
        except ValueError:
            continue
        prov = ""
        for part in extra.split(","):
            if part.startswith("p:"):
                prov = part[2:]
                break
        services[k] = {"name": clean_name(name), "provider": clean_name(prov), "type": stype, "srcid": srcid}
    return tps, services


_RE5_SRV = re.compile(r'^s:([0-9a-fA-F:]+),"(.*)"(?:,(.*))?$')


def _parse_lamedb5(path):
    tps, services = {}, {}
    with open(path, "rb") as f:
        for ln in f.read().decode("utf-8", "replace").splitlines():
            if ln.startswith("t:"):
                try:
                    keypart, params = ln[2:].split(",", 1)
                    key = keypart.split(":")
                    if params.startswith("s:"):
                        tps[(_hex(key[0]), _hex(key[1]), _hex(key[2]))] = _parse_s_params(params[2:])
                except Exception:
                    pass
            elif ln.startswith("s:"):
                m = _RE5_SRV.match(ln)
                if not m:
                    continue
                key = m.group(1).split(":")
                try:
                    k = (_hex(key[0]), _hex(key[1]), _hex(key[2]), _hex(key[3]))
                    stype = int(key[4])
                    srcid = _hex(key[6]) if len(key) > 6 and key[6] else 0
                except (ValueError, IndexError):
                    continue
                prov = ""
                for part in (m.group(3) or "").split(","):
                    if part.startswith("p:"):
                        prov = part[2:]
                        break
                services[k] = {"name": clean_name(m.group(2)), "provider": clean_name(prov), "type": stype, "srcid": srcid}
    return tps, services


# --------------------------------------------------------------------------
# bukiety
# --------------------------------------------------------------------------

_RE_FROM = re.compile(r'FROM BOUQUET "([^"]+)"')


def _ref_key(ref):
    """'1:0:19:1B3D:2BC:13E:820000:0:0:0:' -> (sid,ns,tsid,onid) lub None (IPTV, marker...)"""
    f = ref.split(":")
    if len(f) < 10 or f[0] != "1":
        return None
    try:
        flags = int(f[1], 16) if f[1] else 0
        if flags != 0:
            return None
        if len(f) > 10 and f[10].strip():
            return None  # stream/URL
        sid, tsid, onid, ns = _hex(f[3]), _hex(f[4]), _hex(f[5]), _hex(f[6])
    except ValueError:
        return None
    if ns == 0 or sid == 0:
        return None
    return (sid, ns, tsid, onid)


def parse_bouquets(e2dir=None):
    e2dir = e2dir or E2DIR
    """
    Zwraca liste wpisow:
      {bouquet, file, pos, section, key(tuple|None), ref, desc}
    pos - numer kanalu w bukiecie (markery nie sa liczone).
    """
    entries = []
    seen = set()

    def read_bouquet(fname, depth):
        if fname in seen or depth > 3:
            return
        seen.add(fname)
        path = os.path.join(e2dir, fname)
        if not os.path.exists(path):
            return
        with open(path, "rb") as f:
            lines = f.read().decode("utf-8", "replace").splitlines()
        bname = fname
        section = ""
        pos = 0
        last = None
        subs = []
        for ln in lines:
            if ln.startswith("#NAME "):
                bname = clean_name(ln[6:])
            elif ln.startswith("#SERVICE "):
                ref = ln[9:].strip()
                last = None
                f = ref.split(":")
                m = _RE_FROM.search(ref)
                if m:
                    subs.append(m.group(1))
                    continue
                flags = 0
                try:
                    flags = int(f[1], 16) if len(f) > 1 and f[1] else 0
                except ValueError:
                    pass
                if flags & 64:  # marker
                    section = clean_name(":".join(f[10:]).strip(": ")) if len(f) > 10 else ""
                    last = {"_marker": True}
                    continue
                pos += 1
                last = {"bouquet": bname, "file": fname, "pos": pos, "section": section,
                        "key": _ref_key(ref), "ref": ref, "desc": ""}
                entries.append(last)
            elif ln.startswith("#DESCRIPTION"):
                d = clean_name(ln[12:].lstrip(": "))
                if last is not None:
                    if last.get("_marker"):
                        section = d
                    else:
                        last["desc"] = d
        for s in subs:
            read_bouquet(s, depth + 1)

    for idx in ("bouquets.tv", "bouquets.radio"):
        path = os.path.join(e2dir, idx)
        if not os.path.exists(path):
            continue
        with open(path, "rb") as f:
            for ln in f.read().decode("utf-8", "replace").splitlines():
                m = _RE_FROM.search(ln)
                if m:
                    read_bouquet(m.group(1), 0)
    return entries


# --------------------------------------------------------------------------
# zrzuty i porownanie
# --------------------------------------------------------------------------

def key_str(k):
    return "%04X:%08X:%04X:%04X" % k


def str_key(s):
    return tuple(int(x, 16) for x in s.split(":"))


def stale_kept_path():
    return os.path.join(DATA_DIR, "nieaktualne.json")


def stale_kept():
    """Serwisy, ktorych ostatni skan nie znalazl na satelicie, a nie zostaly usuniete (opcja wylaczona).
    W zrzutach traktowane jak nieistniejace - kanal z bukietu wskazujacy na nie jest 'zgubiony'."""
    return set(load_json(stale_kept_path(), {}).get("keys", []))


def stale_kept_update(stale, removed, found_keys):
    """Po skanie: dopisuje nieusuniete nieaktualne, wykresla znalezione i usuniete."""
    keys = stale_kept()
    keys -= set(key_str(k) for k in found_keys)
    keys -= set(key_str(s["key"]) for s in removed)
    rem = set(key_str(s["key"]) for s in removed)
    keys |= set(key_str(s["key"]) for s in stale if key_str(s["key"]) not in rem)
    ensure_dir(DATA_DIR)
    save_json(stale_kept_path(), {"keys": sorted(keys), "time": time.strftime("%Y-%m-%d %H:%M:%S")})
    return len(keys)


def take_snapshot(e2dir=None):
    e2dir = e2dir or E2DIR
    tps, services = parse_lamedb(e2dir)
    if e2dir == E2DIR:
        for ks in stale_kept():
            try:
                services.pop(str_key(ks), None)
            except Exception:
                pass
    bq = parse_bouquets(e2dir)
    srv = {}
    for k, s in services.items():
        t = tps.get((k[1], k[2], k[3]))
        srv[key_str(k)] = [s["name"], s["provider"], tp_short(t), s["type"], s.get("srcid", 0)]
    b = []
    for e in bq:
        b.append([e["bouquet"], e["pos"], e["section"],
                  key_str(e["key"]) if e["key"] else "", e["desc"]])
    return {"version": VERSION, "time": time.strftime("%Y-%m-%d %H:%M:%S"),
            "services": srv, "bouquets": b}


def snapshot_path():
    return os.path.join(DATA_DIR, "snapshot.json")


def save_snapshot(snap):
    ensure_dir(DATA_DIR)
    save_json(snapshot_path(), snap)


def load_snapshot():
    return load_json(snapshot_path(), None)


_RE_NORM = re.compile(r"\b(hd|fhd|uhd|4k|sd|hevc|h265|tv|poland)\b")
_RE_NUM = re.compile(r"\d+")


def _norm_name(n):
    n = re.sub(r"[^0-9a-zÀ-ɏ+]+", " ", (n or "").lower())
    n = _RE_NORM.sub(" ", n)
    return re.sub(r"\s+", " ", n).strip()


def _compact(n):
    """'FILMBOX+ action' == 'FilmBox Action', 'CANAL+1 HD' == 'CANAL+ 1 HD',
    'Eurosport 1 Poland HD' == 'Eurosport 1 HD'."""
    return re.sub(r"[^0-9a-z\u00c0-\u024f]", "", _norm_name(n))


def _first(n):
    t = re.sub(r"[^0-9a-z\u00c0-\u024f ]", "", _norm_name(n)).split()
    return t[0] if t else ""


def _ref(ks, stype=1, srcid=0):
    """klucz 'SID:NS:TSID:ONID' -> ref w formacie bukietu/enigmy"""
    sid, ns, tsid, onid = str_key(ks) if isinstance(ks, str) else ks
    return "1:0:%X:%X:%X:%X:%X:0:0:%X:" % (stype or 1, sid, tsid, onid, ns, srcid or 0)


def _srv_type(v):
    return v[3] if len(v) > 3 else 1


def _srv_srcid(v):
    return v[4] if len(v) > 4 else 0


def find_candidates(old_name, old_prov, old_key, new_services, added, limit=3, provs=None, bq_keys=None):
    """
    Kandydaci do podmiany zgubionego kanalu (z aktualnego lamedb), od najlepszego.
    Liczby w nazwie musza sie zgadzac (TVP 1 != TVP 2), chyba ze ten sam SID.
    limit=None - wszyscy kandydaci.
    """
    on = _norm_name(old_name)
    oc, of = _compact(old_name), _first(old_name)
    onums = _RE_NUM.findall(on)
    sid = old_key[0]
    bq_keys = bq_keys or set()
    out = []
    for ks, v in new_services.items():
        name, prov, tp = v[0], v[1], v[2]
        if not name:
            continue
        k = str_key(ks)
        nn = _norm_name(name)
        same_sid = k[0] == sid
        same_ts = k[1:] == old_key[1:]
        if not same_sid and _RE_NUM.findall(nn) != onums:
            continue
        ratio = difflib.SequenceMatcher(None, on, nn).ratio() if on else 0.0
        score = ratio
        if same_sid:
            score += 0.25
        if same_ts:
            score += 0.15
        if old_prov and prov and old_prov.lower().rstrip(".") == prov.lower().rstrip("."):
            score += 0.1
        is_new = ks in added
        if is_new:
            score += 0.2
        if score >= 0.65 and (ratio >= 0.6 or same_sid):
            out.append({"score": score, "ratio": ratio, "key": ks, "name": name, "provider": prov,
                        "tp": tp, "type": _srv_type(v), "srcid": _srv_srcid(v),
                        "ref": _ref(ks, _srv_type(v), _srv_srcid(v)),
                        "same_sid": same_sid, "same_ts": same_ts, "new": is_new,
                        "compact_eq": _compact(name) == oc and oc != "",
                        "first_eq": _first(name) == of, "in_bq": ks in bq_keys,
                        "known_prov": (not provs) or same_ts or same_sid
                                      or (prov or "").lower().rstrip(".") in provs})
    out.sort(key=lambda c: (-c["score"], c["key"]))
    return out if limit is None else out[:limit]


def _is_radio(t):
    return t in (2, 0x0A)


def tp_candidates(old_prov, old_key, old_tp, old_type, new_services, added, provs=None, bq_keys=None, limit=30):
    """Gdy po nazwie i SID nie ma kandydatow (nadawca zmienil naraz nazwe i SID): kanaly z tego samego
    strumienia (TS) albo od tego samego nadawcy na tym samym TP. Kolejnosc: nowe po skanie, spoza
    bukietow, ten sam nadawca. Tylko do recznego wyboru - nigdy podmiana automatyczna."""
    bq_keys = bq_keys or set()
    op = (old_prov or "").lower().rstrip(".")
    out = []
    for ks, v in new_services.items():
        name, prov, tp = v[0], v[1], v[2]
        k = str_key(ks)
        if not name or k == old_key or _is_radio(_srv_type(v)) != _is_radio(old_type):
            continue
        same_ts = k[1:] == old_key[1:]
        same_prov = bool(op) and (prov or "").lower().rstrip(".") == op
        if not (same_ts or (same_prov and tp == old_tp)):
            continue
        out.append({"score": 0.0, "ratio": 0.0, "key": ks, "name": name, "provider": prov,
                    "tp": tp, "type": _srv_type(v), "srcid": _srv_srcid(v),
                    "ref": _ref(ks, _srv_type(v), _srv_srcid(v)),
                    "same_sid": False, "same_ts": same_ts, "new": ks in added,
                    "compact_eq": False, "first_eq": False, "in_bq": ks in bq_keys,
                    "known_prov": (not provs) or same_ts or (prov or "").lower().rstrip(".") in provs,
                    "same_prov": same_prov, "fallback": True})
    out.sort(key=lambda c: (not c["new"], c["in_bq"], not c["same_prov"], not c["same_ts"], c["name"].lower()))
    return out[:limit]


def parse_ignore(text):
    return set(x.strip().lower() for x in re.split(r"[,;]", text or "") if x.strip())


def _tp_near(a, b, tol_mhz=3):
    """'10910 V 27500 DVB-S2 13.0E' vs '10911 V ...' - ten sam TP, inna tylko czestotliwosc
    w granicach tolerancji (np. korekta z satellites.xml) - nie raportujemy."""
    try:
        pa, pb = a.split(), b.split()
        return pa[1:] == pb[1:] and abs(int(pa[0]) - int(pb[0])) <= tol_mhz
    except Exception:
        return False


def same_channel(a, b):
    """Czy zmiana nazwy pod tym samym refem to ten sam kanal (TV6 -> TV6 HD, FOX HD -> FX HD),
    czy SID przejal inny kanal (Eurosport 2 HD -> Wydarzenia 24 HD)."""
    na, nb = _norm_name(a), _norm_name(b)
    if not na or not nb or na == nb:
        return True
    if na.replace(" ", "") in nb.replace(" ", "") or nb.replace(" ", "") in na.replace(" ", ""):
        return True
    if _RE_NUM.findall(na) != _RE_NUM.findall(nb):
        return False
    return difflib.SequenceMatcher(None, na, nb).ratio() >= 0.6


SIMILAR_MIN = 0.75   # regula "jedyny podobny kandydat" - minimalna zgodnosc nazwy


def compare_snapshots(old, new, ignore=None):
    """
    Porownuje zrzut referencyjny z aktualnym. Bukiety bierzemy z aktualnego
    zrzutu (Twoja obecna kolejnosc). Wpisy grupowane po refie - jeden kanal
    w kilku bukietach to jeden blok z lista miejsc.
    Zrzut moze miec 'ghosts' - kanaly zgubione wczesniej i nadal nierozwiazane;
    wracaja jako zgubione, dopoki ich nie podmienisz lub nie usuniesz z bukietu.
    ignore: zbior nazw bukietow (male litery) do pominiecia.
    """
    ignore = ignore or set()
    osrv, nsrv = old.get("services", {}), new.get("services", {})
    ghosts = old.get("ghosts", {})
    added = set(nsrv) - set(osrv)
    res = {"lost": [], "renamed": [], "moved": [], "dead": [],
           "added_count": len(added), "removed_count": len(set(osrv) - set(nsrv))}
    groups = {}   # (kind, ks) -> entry
    order = []
    bq_keys = set(b[3] for b in new.get("bouquets", []) if b[3] and b[0].lower() not in ignore)
    provs = set()
    for k in bq_keys:
        for src_ in (nsrv.get(k), osrv.get(k)):
            if src_ and src_[1]:
                provs.add(src_[1].lower().rstrip("."))

    def lost_entry(ks, src, earlier, now_name=None):
        cands = find_candidates(src[0], src[1], str_key(ks), nsrv, added, limit=None,
                                provs=provs, bq_keys=bq_keys - set([ks]))
        if now_name is not None:
            cands = [x for x in cands if x["key"] != ks]
        if not cands:
            cands = tp_candidates(src[1], str_key(ks), src[2], _srv_type(src), nsrv, added, provs, bq_keys - set([ks]))
        return {"key": ks, "name": src[0], "provider": src[1], "tp": src[2],
                "type": _srv_type(src), "srcid": _srv_srcid(src),
                "ref": _ref(ks, _srv_type(src), _srv_srcid(src)),
                "earlier": earlier, "now": now_name, "candidates": cands, "places": []}

    for bq, pos, section, ks, desc in new.get("bouquets", []):
        if not ks or bq.lower() in ignore:
            continue
        place = (bq, pos, section)
        if ks in nsrv:
            n = nsrv[ks]
            o = osrv.get(ks)
            gh = ghosts.get(ks)
            # SID przejety przez inny kanal: wczesniej (albo jako nierozwiazany) byl tu inny kanal
            src, earlier = None, False
            if o and o[0] != n[0] and not same_channel(o[0], n[0]):
                src = o
            elif gh and (not o or o[0] == n[0]) and not same_channel(gh[0], n[0]):
                src, earlier = gh, True
            if src:
                g = groups.get(("lost", ks))
                if g is None:
                    g = groups[("lost", ks)] = lost_entry(ks, src, earlier, n[0])
                    order.append(("lost", ks))
                g["places"].append(place)
                continue
            if o:
                if o[0] != n[0]:
                    g = groups.get(("renamed", ks))
                    if g is None:
                        g = groups[("renamed", ks)] = {"key": ks, "old": o[0], "new": n[0], "places": []}
                        order.append(("renamed", ks))
                    g["places"].append(place)
                if o[2] != n[2] and not _tp_near(o[2], n[2]):
                    g = groups.get(("moved", ks))
                    if g is None:
                        g = groups[("moved", ks)] = {"key": ks, "name": n[0], "old_tp": o[2],
                                                    "new_tp": n[2], "places": []}
                        order.append(("moved", ks))
                    g["places"].append(place)
            continue
        src = osrv.get(ks) or ghosts.get(ks)
        kind = "lost" if src else "dead"
        g = groups.get((kind, ks))
        if g is None:
            if kind == "lost":
                g = lost_entry(ks, src, ks not in osrv)
            else:
                g = {"key": ks, "name": desc or "", "ref": _ref(ks), "places": []}
            groups[(kind, ks)] = g
            order.append((kind, ks))
        elif kind == "dead" and desc and not g["name"]:
            g["name"] = desc
        g["places"].append(place)
    for kind, ks in order:
        res[kind].append(groups[(kind, ks)])
    return res


_TIE = (("in_bq", "juz w Twoich bukietach"), ("new", "NOWY"), ("same_ts", "ten sam TS"),
        ("same_sid", "ten sam SID"))


def display_reason(reason):
    """Translate a decision reason without changing stored decision data."""
    if not reason:
        return reason
    base, separator, tie = reason.partition(", rozstrzyga: ")
    match = re.match(r"^(\d+) kandydatow: (.*)$", base)
    if match:
        base = _("%d kandydatow: %s") % (int(match.group(1)), display_reason(match.group(2)))
    else:
        patterns = (
            (r"^nazwa i SID nie pasuja do niczego - (\d+) kanalow z tego TP / nadawcy do wyboru$",
             _("nazwa i SID nie pasuja do niczego - %d kanalow z tego TP / nadawcy do wyboru")),
            (r"^jedyny kandydat za malo podobny \((\d+)%\)$",
             _("jedyny kandydat za malo podobny (%d%%)")),
            (r"^(\d+) podobnych kandydatow$", _("%d podobnych kandydatow")),
        )
        for pattern, message in patterns:
            match = re.match(pattern, base)
            if match:
                base = message % int(match.group(1))
                break
        else:
            base = _(base)
    if separator:
        base += _(", rozstrzyga: ") + ", ".join(_(part) for part in tie.split(", "))
    return base


def display_scan_kind(kind):
    if not kind:
        return kind
    suffix = " (wznowienie)"
    resumed = kind.endswith(suffix)
    base = kind[:-len(suffix)] if resumed else kind
    return _(base) + (_(suffix) if resumed else "")


def display_scan_status(status):
    """Format scan-result statuses for display; their original values drive retries."""
    if not status:
        return status
    patterns = (
        (r"^brak tunera skonfigurowanego na (.*)$", _("brak tunera skonfigurowanego na %s"), str),
        (r"^przerwano - brak wolnego tunera przez (\d+) min$", _("przerwano - brak wolnego tunera przez %d min"), int),
        (r"^przerwano - tunery zajete \(FBC\) przez (\d+) min$", _("przerwano - tunery zajete (FBC) przez %d min"), int),
        (r"^blad skanu (.*)$", _("blad skanu %s"), str),
        (r"^limit czasu (\d+) s$", _("limit czasu %d s"), int),
        (r"^tuner potrzebny uzytkownikowi \((\d+)x\)$", _("tuner potrzebny uzytkownikowi (%dx)"), int),
    )
    for pattern, message, convert in patterns:
        match = re.match(pattern, str(status))
        if match:
            return message % convert(match.group(1))
    return _(status)


def _known_first(pool):
    """Gdy sa kandydaci od dostawcow z Twoich bukietow i od innych (np. zagraniczne
    pakiety) - zostaja tylko ci pierwsi."""
    known = [c for c in pool if c.get("known_prov", True)]
    return known if known else pool


def _pick(pool, label):
    """Jeden kandydat -> on; kilku -> kolejne kryteria rozstrzygajace."""
    pool = _known_first(pool)
    if len(pool) == 1:
        return pool[0], label
    used = []
    for flag, lab in _TIE:
        sub = [c for c in pool if c.get(flag)]
        if len(sub) == 1:
            return sub[0], label + ", rozstrzyga: " + ", ".join(used + [lab])
        if len(sub) > 1:
            pool = sub
            used.append(lab)
    return None, "%d kandydatow: %s" % (len(pool), label)


def decide(entry):
    """
    Reguly automatycznej podmiany:
      1. dokladnie ta sama nazwa -> jeden: podmien; kilku: rozstrzygaj (juz w Twoich bukietach,
         NOWY, ten sam TS, ten sam SID), nic nie rozstrzyga -> do decyzji,
      2. ta sama nazwa po pominieciu HD/4K/Poland, spacji i znakow (Puls 2 -> Puls 2 HD,
         FilmBox Action -> FILMBOX+ action) -> jak w punkcie 1,
      3. dokladnie jeden podobny kandydat (>= 75%, to samo pierwsze slowo) -> podmien,
      4. reszta -> do decyzji.
    Zwraca (kandydat|None, powod).
    """
    cands = entry.get("candidates") or []
    if not cands:
        return None, "brak kandydata"
    if cands[0].get("fallback"):
        return None, "nazwa i SID nie pasuja do niczego - %d kanalow z tego TP / nadawcy do wyboru" % len(cands)
    oname = (entry.get("name") or "").strip().lower()
    exact = [c for c in cands if c["name"].strip().lower() == oname]
    if exact:
        return _pick(exact, "ta sama nazwa")
    same = [c for c in cands if c.get("compact_eq")]
    if same:
        return _pick(same, "ta sama nazwa (bez HD/spacji)")
    cands = _known_first(cands)
    if len(cands) == 1:
        c = cands[0]
        if c["ratio"] >= SIMILAR_MIN and c.get("first_eq"):
            return c, "jedyny podobny kandydat"
        return None, "jedyny kandydat za malo podobny (%d%%)" % int(c["ratio"] * 100)
    return None, "%d podobnych kandydatow" % len(cands)


def _bouquet_files(e2dir):
    """[(plik, nazwa_bukietu)] - pliki bukietow uzytkownika (z podbukietami)."""
    out = []
    seen = set()

    def walk(fname, depth):
        if fname in seen or depth > 3:
            return
        seen.add(fname)
        path = os.path.join(e2dir, fname)
        if not os.path.exists(path):
            return
        bname = fname
        subs = []
        with open(path, "rb") as f:
            for ln in f.read().decode("utf-8", "replace").splitlines():
                if ln.startswith("#NAME "):
                    bname = clean_name(ln[6:])
                elif ln.startswith("#SERVICE "):
                    m = _RE_FROM.search(ln)
                    if m:
                        subs.append(m.group(1))
        out.append((fname, bname))
        for s in subs:
            walk(s, depth + 1)

    for idx in ("bouquets.tv", "bouquets.radio"):
        path = os.path.join(e2dir, idx)
        if not os.path.exists(path):
            continue
        with open(path, "rb") as f:
            for ln in f.read().decode("utf-8", "replace").splitlines():
                m = _RE_FROM.search(ln)
                if m:
                    walk(m.group(1), 0)
    return out


def _swap_ref(ref, new_key, new_type, new_srcid=0):
    f = ref.split(":")
    sid, ns, tsid, onid = new_key
    f[2] = "%X" % (new_type or 1)
    f[3] = "%X" % sid
    f[4] = "%X" % tsid
    f[5] = "%X" % onid
    f[6] = "%X" % ns
    if len(f) > 9 and new_srcid:
        f[9] = "%X" % new_srcid
    return ":".join(f)


def apply_bouquet_replacements(repl, ignore=None, e2dir=None, keep=10):
    """
    repl: {stary_klucz(tuple): (nowy_klucz(tuple), typ, srcid)}
    Podmienia refy w plikach bukietow (z pominieciem ignorowanych), #DESCRIPTION
    i pozycje zostaja. Przed zmiana kopia zmienianych plikow.
    Zwraca liste (bukiet, pozycja, stary_ref, nowy_ref).
    """
    e2dir = e2dir or E2DIR
    ignore = ignore or set()
    changes = []
    to_write = []
    for fname, bname in _bouquet_files(e2dir):
        if bname.lower() in ignore:
            continue
        path = os.path.join(e2dir, fname)
        with open(path, "rb") as f:
            raw = f.read().decode("utf-8", "replace")
        lines = raw.split("\n")
        changed = False
        pos = 0
        for i, ln in enumerate(lines):
            if not ln.startswith("#SERVICE "):
                continue
            ref = ln[9:].rstrip("\r")
            fl = ref.split(":")
            try:
                flags = int(fl[1], 16) if len(fl) > 1 and fl[1] else 0
            except ValueError:
                flags = 0
            if flags & 64 or _RE_FROM.search(ref):
                continue
            pos += 1
            k = _ref_key(ref.strip())
            if k in repl:
                nk, nt, ns_ = repl[k]
                newref = _swap_ref(ref, nk, nt, ns_)
                lines[i] = "#SERVICE " + newref + ("\r" if ln.endswith("\r") else "")
                changes.append((bname, pos, ref.strip(), newref.strip()))
                changed = True
        if changed:
            to_write.append((path, fname, "\n".join(lines)))
    if to_write:
        bdir = os.path.join(DATA_DIR, "bouquet_backup")
        tdir = os.path.join(bdir, time.strftime("%Y%m%d-%H%M%S"))
        ensure_dir(tdir)
        for path, fname, content in to_write:
            shutil.copy2(path, os.path.join(tdir, fname))
        for path, fname, content in to_write:
            atomic_write(path, content)
        dirs = sorted(d for d in os.listdir(bdir) if os.path.isdir(os.path.join(bdir, d)))
        for d in dirs[:-keep]:
            shutil.rmtree(os.path.join(bdir, d), ignore_errors=True)
    return changes


def clear_unresolved():
    """Akceptacja obecnego stanu: czysci liste nierozwiazanych (DO DECYZJI)."""
    snap = load_snapshot()
    if not snap:
        return 0
    n = len(snap.get("ghosts", {}))
    snap["ghosts"] = {}
    save_snapshot(snap)
    return n


def pending_decisions(ignore_text=None):
    """Kanaly DO DECYZJI z pelna lista kandydatow (do ekranu wyboru)."""
    old = load_snapshot()
    if not old:
        return []
    res = compare_snapshots(old, take_snapshot(), parse_ignore(ignore_text))
    return res["lost"]


def remove_from_bouquets(key, ignore=None, e2dir=None, keep=10):
    """Usuwa wpisy kanalu (klucz tuple) z bukietow, z kopia. Zwraca liczbe usunietych wpisow."""
    e2dir = e2dir or E2DIR
    ignore = ignore or set()
    n = 0
    to_write = []
    for fname, bname in _bouquet_files(e2dir):
        if bname.lower() in ignore:
            continue
        path = os.path.join(e2dir, fname)
        with open(path, "rb") as f:
            lines = f.read().decode("utf-8", "replace").split("\n")
        out, skip_desc, changed = [], False, False
        for ln in lines:
            if skip_desc and ln.startswith("#DESCRIPTION"):
                skip_desc = False
                continue
            skip_desc = False
            if ln.startswith("#SERVICE ") and _ref_key(ln[9:].strip()) == key:
                n += 1
                changed = skip_desc = True
                continue
            out.append(ln)
        if changed:
            to_write.append((path, fname, "\n".join(out)))
    if to_write:
        bdir = os.path.join(DATA_DIR, "bouquet_backup")
        tdir = os.path.join(bdir, time.strftime("%Y%m%d-%H%M%S"))
        ensure_dir(tdir)
        for path, fname, content in to_write:
            shutil.copy2(path, os.path.join(tdir, fname))
            atomic_write(path, content)
        dirs = sorted(d for d in os.listdir(bdir) if os.path.isdir(os.path.join(bdir, d)))
        for d in dirs[:-keep]:
            shutil.rmtree(os.path.join(bdir, d), ignore_errors=True)
    return n


def resolve_decision(ks, action, cand=None, ignore_text=None):
    """Decyzja dla kanalu DO DECYZJI (ks = klucz tekstowy).
    action: "replace" (cand = kandydat z pending_decisions), "remove" (usun z bukietow), "keep" (zostaw).
    Zwraca (opis, bukiety_zmienione)."""
    ign = parse_ignore(ignore_text)
    changed = False
    if action == "replace" and cand:
        ch = apply_bouquet_replacements({str_key(ks): (str_key(cand["key"]), cand["type"], cand["srcid"])}, ign)
        changed = bool(ch)
        msg = _("Podmieniono na %s (%s) w %d miejscach") % (cand["name"], cand["ref"], len(ch))
    elif action == "remove":
        n = remove_from_bouquets(str_key(ks), ign)
        changed = bool(n)
        msg = _("Usunieto z bukietow (%d wpisow)") % n
    else:
        msg = _("Zostawiono bez zmian")
    snap = load_snapshot() or {}
    ghosts = dict(snap.get("ghosts", {}))
    ghosts.pop(ks, None)
    new = take_snapshot()
    new["ghosts"] = ghosts
    save_snapshot(new)
    append_report([_("Decyzja (recznie): %s -> %s") % (ks, msg)])
    return msg, changed


def list_bouquet_backups():
    bdir = os.path.join(DATA_DIR, "bouquet_backup")
    if not os.path.isdir(bdir):
        return []
    return sorted((d for d in os.listdir(bdir) if os.path.isdir(os.path.join(bdir, d))), reverse=True)


def restore_bouquet_backup(name, e2dir=None):
    """Przywraca pliki bukietow z kopii (sprzed podmiany). Zwraca liczbe plikow."""
    e2dir = e2dir or E2DIR
    src = os.path.join(DATA_DIR, "bouquet_backup", name)
    n = 0
    for f in os.listdir(src):
        with open(os.path.join(src, f), "rb") as fh:
            atomic_write(os.path.join(e2dir, f), fh.read())
        n += 1
    return n


def process_bouquets(ignore_text=None, autoreplace=True, e2dir=None):
    """
    Porownuje aktualny stan z ostatnim zrzutem, wg regul podmienia zgubione kanaly
    w bukietach, zapisuje nowy zrzut (z 'ghosts' - nierozwiazanymi).
    Zwraca dict: lines, replaced, unresolved, changed(bool - pliki bukietow zmienione).
    """
    e2dir = e2dir or E2DIR
    ign = parse_ignore(ignore_text)
    old = load_snapshot()
    new = take_snapshot(e2dir)
    if not old:
        save_snapshot(new)
        return {"lines": [_("Bukiety: pierwszy zrzut - porownanie przy nastepnym sprawdzeniu."),
                          _("Zapisano zrzut: %d serwisow w lamedb, %d wpisow w bukietach.")
                          % (len(new["services"]), len(new["bouquets"]))],
                "replaced": 0, "unresolved": 0, "unresolved_new": 0, "changed": False}
    res = compare_snapshots(old, new, ign)
    repl = {}
    for e in res["lost"]:
        c, why = decide(e)
        e["reason"] = why
        if c and autoreplace:
            e["replaced"] = c
            repl[str_key(e["key"])] = (str_key(c["key"]), c["type"], c["srcid"])
        elif c:
            e["would"] = c
    changes = []
    if repl:
        changes = apply_bouquet_replacements(repl, ign, e2dir)
        new = take_snapshot(e2dir)
    ghosts = {}
    for e in res["lost"]:
        if not e.get("replaced"):
            ghosts[e["key"]] = [e["name"], e["provider"], e["tp"], e["type"], e["srcid"]]
    new["ghosts"] = ghosts
    save_snapshot(new)
    lines = format_bouquet_report(res, old.get("time"))
    lines.append("")
    lines.append(_("Zapisano zrzut: %d serwisow w lamedb, %d wpisow w bukietach.")
                 % (len(new["services"]), len(new["bouquets"])))
    return {"lines": lines, "replaced": len([e for e in res["lost"] if e.get("replaced")]),
            "unresolved": len(ghosts),
            "unresolved_new": len([e for e in res["lost"] if not e.get("replaced") and not e.get("earlier")]),
            "changed": bool(changes), "changes": changes}


def bouquet_hits_for_tps(diff_by_pos, tol_khz=3000, e2dir=None, ignore=None):
    """
    Dla zmienionych/usunietych/przesunietych transponderow (z diffu satellites.xml)
    zwraca kanaly z Twoich bukietow, ktore na nich leza (wg aktualnego lamedb).
    """
    e2dir = e2dir or E2DIR
    ignore = ignore or set()
    tps, services = parse_lamedb(e2dir)
    bq = parse_bouquets(e2dir)
    targets = []  # (pos, pol, freq, label)
    for pos, d in diff_by_pos.items():
        for a, b, fields in d["changed"]:
            targets.append((pos, int(a["polarization"]), int(a["frequency"]), "zmieniony: " + tp_str(a)))
        for a, b in d["shifted"]:
            targets.append((pos, int(a["polarization"]), int(a["frequency"]), "przesuniety: " + tp_str(a)))
        for a in d["removed"]:
            targets.append((pos, int(a["polarization"]), int(a["frequency"]), "usuniety: " + tp_str(a)))
    hits = {}
    for e in bq:
        k = e["key"]
        if not k or e["bouquet"].lower() in ignore:
            continue
        t = tps.get((k[1], k[2], k[3]))
        if not t:
            continue
        for pos, pol, freq, label in targets:
            if t["pos"] == pos and t["pol"] == pol and abs(t["freq"] - freq) <= tol_khz:
                s = services.get(k, {})
                hits.setdefault(label, []).append(
                    (e["bouquet"], e["pos"], s.get("name") or e["desc"] or key_str(k)))
                break
    return hits


# --------------------------------------------------------------------------
# skanowanie - czesc niezalezna od enigmy
# --------------------------------------------------------------------------

def tp_id(tp):
    return (int(tp["position"]), int(tp.get("polarization", 0)), int(tp["frequency"]))


def merge_tps(*lists):
    """Laczy listy TP (dict z 'position'), bez duplikatow (pozycja, polaryzacja, freq)."""
    out, seen = [], set()
    for lst in lists:
        for tp in lst or []:
            i = tp_id(tp)
            if i in seen:
                continue
            seen.add(i)
            out.append(tp)
    return out


def full_scan_tps(positions):
    """Wszystkie TP monitorowanych pozycji z pliku, ktorego uzywa enigma."""
    _, sats = parse_sat_xml(local_xml_path())
    out = []
    for p in positions:
        for tp in sat_tps(sats.get(p)):
            out.append(dict(tp, position=p))
    out.sort(key=lambda t: (t["position"], int(t["frequency"])))
    return out


def find_stale(scanned, found_keys, tol_khz=3000, e2dir=None):
    """
    Po skanie: serwisy w lamedb lezace na poprawnie zeskanowanych TP,
    ktorych skan nie znalazl -> nieaktualne (do usuniecia).
    Zabezpieczenie: jesli na TP znaleziono mniej niz polowe znanych serwisow,
    nic z niego nie usuwamy (TP 'podejrzany').
    scanned: lista dict TP z polem 'ok'.
    found_keys: zbior kluczy (sid, ns, tsid, onid) znalezionych w skanie.
    Zwraca (stale, suspicious):
      stale: lista dict {key, type, srcid, name, tp}
      suspicious: lista (opis_tp, znalezione, znane)
    """
    e2dir = e2dir or E2DIR
    tps, services = parse_lamedb(e2dir)
    ok = [t for t in scanned if t.get("ok")]
    per = {}  # indeks ok TP -> lista kluczy serwisow
    for k, s in services.items():
        t = tps.get((k[1], k[2], k[3]))
        if not t:
            continue
        best, bd = None, None
        for i, sc in enumerate(ok):
            pos, pol, freq = tp_id(sc)
            if t["pos"] != pos or t["pol"] != pol:
                continue
            d = abs(t["freq"] - freq)
            if d <= tol_khz and (bd is None or d < bd):
                best, bd = i, d
        if best is not None:
            per.setdefault(best, []).append(k)
    stale, suspicious = [], []
    for i, keys in per.items():
        known = len(keys)
        found = len([k for k in keys if k in found_keys])
        missing = [k for k in keys if k not in found_keys]
        if not missing:
            continue
        if known >= 4 and found * 2 < known:
            suspicious.append((tp_str(ok[i]), found, known))
            continue
        for k in missing:
            s = services[k]
            stale.append({"key": k, "type": s["type"], "srcid": s.get("srcid", 0),
                          "name": s["name"], "tp": tp_short(tps.get((k[1], k[2], k[3])))})
    stale.sort(key=lambda x: (x["tp"], x["name"]))
    return stale, suspicious


def format_scan_report(kind, results, stale, removed, suspicious, kept=False):
    """kind: 'zmienione TP' / 'pelny'; results: lista dict TP z polami ok, status, found, tuner."""
    L = []
    okn = len([r for r in results if r.get("ok")])
    L.append(_("Skan (%s): %d TP, poprawnie %d, nieudane %d") % (display_scan_kind(kind), len(results), okn, len(results) - okn))
    stopped = [r for r in results if str(r.get("status", "")).startswith("przerwano")]
    for r in results:
        if not r.get("ok") and r not in stopped:
            L.append("  ! %s %s - %s" % (pos_str(r["position"]), tp_str(r), display_scan_status(r.get("status", "?"))))
    if stopped:
        L.append(_("  ! %d TP niezeskanowanych: %s") % (len(stopped), display_scan_status(stopped[0].get("status", "?"))))
    if removed:
        L.append(_("Usunieto nieaktualne serwisy: %d") % len(removed))
        for s in removed[:40]:
            L.append("  - %s  (%s)" % (s["name"] or key_str(s["key"]), s["tp"]))
        if len(removed) > 40:
            L.append(_("  ... i %d wiecej") % (len(removed) - 40))
    notrem = [s for s in stale if s not in removed]
    if notrem and kept:
        L.append(_("Nieaktualne - skan ich nie znalazl, nie usuniete (opcja wylaczona): %d") % len(notrem))
        for s in notrem[:40]:
            L.append("  - %s  (%s)" % (s["name"] or key_str(s["key"]), s["tp"]))
        if len(notrem) > 40:
            L.append(_("  ... i %d wiecej") % (len(notrem) - 40))
        L.append(_("  (kanaly z bukietow wsrod nich trafiaja do podmiany / decyzji jak zgubione)"))
    elif notrem:
        L.append(_("Nie udalo sie usunac: %d") % len(notrem))
    for desc, f, k in suspicious:
        L.append(_("  ? %s - znaleziono %d z %d znanych serwisow, nic nie usunieto") % (desc, f, k))
    return L


def scan_failed_update(results, max_attempts=3, keep_aborted=True):
    """Aktualizuje liste TP do ponowienia przy nastepnym sprawdzeniu:
    TP zeskanowane poprawnie znikaja z listy, nieudane dostaja +1 prob,
    TP spoza tego skanu zostaja bez zmian.
    keep_aborted=False (pelny skan): przerwane TP nie trafiaja do ponowien
    (pelny skan ma wlasne wznawianie)."""
    state_path = os.path.join(DATA_DIR, "state.json")
    state = load_json(state_path, {})
    prev = dict((tuple(x["id"]), x) for x in state.get("pending_failed", []))
    for r in results:
        i = tp_id(r)
        if r.get("ok"):
            prev.pop(i, None)
            continue
        if not keep_aborted and str(r.get("status", "")).startswith("przerwano"):
            continue
        if str(r.get("status", "")).startswith("brak tunera skonfigurowanego"):
            prev.pop(i, None)      # konfiguracja tunerow, nie sygnal - ponawianie nic nie da
            continue
        att = prev.get(i, {}).get("attempts", 0) + 1
        if att <= max_attempts:
            tp = dict((k, v) for k, v in r.items() if k not in ("ok", "status", "found", "tuner"))
            prev[i] = {"id": list(i), "tp": tp, "attempts": att}
        else:
            prev.pop(i, None)
    out = list(prev.values())
    state["pending_failed"] = out
    save_json(state_path, state)
    return out


def resume_save(kind, results, hours=24):
    """Zapamietuje TP niezeskanowane z braku tunera - do wznowienia, gdy tunery beda wolne."""
    state_path = os.path.join(DATA_DIR, "state.json")
    state = load_json(state_path, {})
    tps = [dict((k, v) for k, v in r.items() if k not in ("ok", "status", "found", "tuner"))
           for r in results if str(r.get("status", "")).startswith("przerwano - ")]
    if tps:
        state["resume"] = {"kind": kind.replace(" (wznowienie)", ""), "tps": tps,
                           "until": int(time.time() + hours * 3600)}
    else:
        state.pop("resume", None)
    save_json(state_path, state)
    return len(tps)


def resume_add(kind, tps, hours=24):
    """Dopisuje TP do wznowienia w tle (gdy tunery beda wolne) - bez blokowania przebiegu EPG Scan."""
    state_path = os.path.join(DATA_DIR, "state.json")
    state = load_json(state_path, {})
    old = state.get("resume") or {}
    if old.get("tps") and time.time() <= old.get("until", 0):
        tps = merge_tps(old["tps"], tps)
        kind = old.get("kind", kind)
    if tps:
        state["resume"] = {"kind": kind, "tps": tps, "until": int(time.time() + hours * 3600)}
        save_json(state_path, state)
    return len(tps)


def resume_take():
    """Zwraca (kind, tps) do wznowienia albo None; czysci zapis."""
    state_path = os.path.join(DATA_DIR, "state.json")
    state = load_json(state_path, {})
    r = state.pop("resume", None)
    save_json(state_path, state)
    if not r or not r.get("tps") or time.time() > r.get("until", 0):
        return None
    return r["kind"], r["tps"]


def resume_pending():
    r = load_json(os.path.join(DATA_DIR, "state.json"), {}).get("resume")
    if r and r.get("tps") and time.time() <= r.get("until", 0):
        return r
    return None


def pending_failed_tps():
    state = load_json(os.path.join(DATA_DIR, "state.json"), {})
    return [x["tp"] for x in state.get("pending_failed", [])]


# --------------------------------------------------------------------------
# raport
# --------------------------------------------------------------------------

def format_tp_diff(pos, d, sat_name=""):
    lines = []
    head = _("Pozycja %s") % (sat_name if sat_name.startswith(pos_str(pos)) else (pos_str(pos) + " " + sat_name))
    if not diff_count(d):
        return [head.strip() + _(": bez zmian")]
    lines.append(head.strip() + _(":  nowe %d, usuniete %d, zmienione %d, przesuniete %d")
                 % (len(d["added"]), len(d["removed"]), len(d["changed"]), len(d["shifted"])))
    for tp in d["added"]:
        lines.append("  + %s" % tp_str(tp))
    for tp in d["removed"]:
        lines.append("  - %s" % tp_str(tp))
    for a, b, fields in d["changed"]:
        ch = ", ".join("%s %s->%s" % (_(FIELD_LABEL.get(f, f)), a.get(f, "-"), b.get(f, "-")) for f in fields)
        if a.get("frequency") != b.get("frequency"):
            ch = "freq %d->%d, " % (int(a["frequency"]) // 1000, int(b["frequency"]) // 1000) + ch
        lines.append("  * %s  [%s]" % (tp_str(a), ch))
    for a, b in d["shifted"]:
        lines.append("  ~ %s  [freq %d->%d]" % (tp_str(a), int(a["frequency"]) // 1000, int(b["frequency"]) // 1000))
    return lines


def _places(places, limit=6):
    s = []
    for bq, pos, section in places[:limit]:
        s.append(_('"%s" poz. %d%s') % (bq, pos, (" [" + section + "]") if section else ""))
    if len(places) > limit:
        s.append("... +%d" % (len(places) - limit))
    return s


def _cand_lines(c, prefix="  -> "):
    tags = []
    if c.get("in_bq"):
        tags.append(_("juz w bukietach"))
    if c["new"]:
        tags.append(_("NOWY"))
    if c["same_sid"]:
        tags.append(_("ten sam SID"))
    if c["same_ts"]:
        tags.append(_("ten sam TS"))
    if c.get("fallback"):
        tags.append(_("nazwa i SID nie pasuja - kanal z tego TP / nadawcy"))
    return [prefix + _("%s  (%s, %s) nazwa %d%%%s")
            % (c["name"], c["tp"], c["provider"] or "-", int(c["ratio"] * 100),
               (", " + ", ".join(tags)) if tags else ""),
            " " * len(prefix) + "ref %s" % c["ref"]]


def format_bouquet_report(cmp_res, ref_time=""):
    lost = cmp_res["lost"]
    repl = [e for e in lost if e.get("replaced")]
    todo = [e for e in lost if not e.get("replaced")]
    L = []
    L.append(_("Bukiety vs zrzut z %s:  zgubione %d (podmienione %d, do decyzji %d), "
             "zmiana nazwy %d, zmiana TP %d, martwe od dawna %d")
             % (ref_time or "?", len(lost), len(repl), len(todo), len(cmp_res["renamed"]),
                len(cmp_res["moved"]), len(cmp_res["dead"])))
    L.append(_("  (w lamedb: nowych serwisow %d, usunietych %d)")
             % (cmp_res["added_count"], cmp_res["removed_count"]))
    for e in repl:
        c = e["replaced"]
        L.append("")
        L.append(_("PODMIENIONO: %s -> %s  (%s)%s") % (e["name"], c["name"], display_reason(e.get("reason", "")),
                                                   (_("  [SID przejal: %s]") % e["now"]) if e.get("now") else ""))
        L.append(_("  stary: %s  (%s)") % (e["ref"], e["tp"]))
        L.append(_("  nowy:  %s  (%s)") % (c["ref"], c["tp"]))
        for p in _places(e["places"]):
            L.append(_("  w:     %s") % p)
    for e in todo:
        L.append("")
        head = _("DO DECYZJI") if ("reason" in e and not e.get("would")) else _("ZGUBIONY")
        L.append("%s: %s  (%s, %s)%s" % (head, e["name"], e["tp"], e["provider"] or "-",
                                         _("  [nierozwiazany od wczesniej]") if e.get("earlier") else ""))
        L.append("  ref:   %s" % e["ref"])
        if e.get("now"):
            L.append(_("  UWAGA: pod tym refem jest teraz inny kanal: %s") % e["now"])
        for p in _places(e["places"]):
            L.append(_("  w:     %s") % p)
        if e.get("would"):
            L.append(_("  automat: podmienilby na %s (%s)") % (e["would"]["name"], display_reason(e.get("reason", ""))))
        elif "reason" in e:
            L.append(_("  powod: %s") % display_reason(e["reason"]))
        if e["candidates"]:
            for c in e["candidates"][:3]:
                L.extend(_cand_lines(c))
            if len(e["candidates"]) > 3:
                L.append(_("  ... i %d kandydatow wiecej") % (len(e["candidates"]) - 3))
        else:
            L.append(_("  -> brak kandydata"))
    if cmp_res["renamed"]:
        L.append("")
        L.append(_("Zmiana nazwy:"))
        for e in cmp_res["renamed"]:
            L.append(_("  %s -> %s   w: %s") % (e["old"], e["new"], "; ".join(_places(e["places"], 3))))
    if cmp_res["moved"]:
        L.append("")
        L.append(_("Zmiana transpondera (kanal dziala, ten sam ref):"))
        for e in cmp_res["moved"]:
            L.append(_("  %s  %s -> %s   w: %s") % (e["name"], e["old_tp"], e["new_tp"],
                                               "; ".join(_places(e["places"], 3))))
    if cmp_res["dead"]:
        L.append("")
        L.append(_("Martwe wpisy (nie bylo ich w lamedb juz przy poprzednim zrzucie):"))
        for e in cmp_res["dead"][:50]:
            L.append(_("  %s  ref %s   w: %s") % (e["name"] or _("(bez nazwy)"), e["ref"],
                                              "; ".join(_places(e["places"], 3))))
        if len(cmp_res["dead"]) > 50:
            L.append(_("  ... i %d wiecej") % (len(cmp_res["dead"]) - 50))
    return L


def mount_root(path):
    """/media/hdd/satwatch -> /media/hdd ; /etc/enigma2/x -> None (pamiec wewnetrzna)."""
    p = os.path.abspath(path).split(os.sep)
    if len(p) >= 3 and p[1] in ("media", "mnt"):
        return os.sep.join(p[:3])
    return None


def dir_usable(path):
    """Katalog na nosniku zewnetrznym tylko gdy nosnik jest zamontowany - inaczej
    zapis trafilby do pustego katalogu /media/hdd na flashu."""
    if not path:
        return False
    root = mount_root(path)
    if root and not os.path.ismount(root):
        return False
    try:
        ensure_dir(path)
        return os.access(path, os.W_OK)
    except Exception:
        return False


def out_dir():
    """Katalog logu i historii raportow: wybrany, a gdy niedostepny - DATA_DIR."""
    if LOG_PREF and os.path.abspath(LOG_PREF) != os.path.abspath(DATA_DIR) and dir_usable(LOG_PREF):
        return LOG_PREF
    return DATA_DIR


def migrate_out_dir(old, new):
    """Po zmianie katalogu: kopiuje log i historie raportow (stare zostaja)."""
    n = 0
    if not old or not new or os.path.abspath(old) == os.path.abspath(new) or not dir_usable(new):
        return 0
    for f in os.listdir(old) if os.path.isdir(old) else []:
        if f.startswith("satwatch.log") or f == "last_report.txt":
            if not os.path.exists(os.path.join(new, f)):
                shutil.copy2(os.path.join(old, f), os.path.join(new, f))
                n += 1
    src = os.path.join(old, "reports")
    if os.path.isdir(src):
        dst = os.path.join(new, "reports")
        ensure_dir(dst)
        for f in os.listdir(src):
            if not os.path.exists(os.path.join(dst, f)):
                shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
                n += 1
    return n


def save_report(text):
    if not text.endswith("\n"):
        text += "\n"
    # last_report.txt zawsze w DATA_DIR (czyta go wtyczka), kopia + historia w out_dir()
    atomic_write(os.path.join(ensure_dir(DATA_DIR) or DATA_DIR, "last_report.txt"), text)
    od = out_dir()
    rdir = os.path.join(od, "reports")
    ensure_dir(rdir)
    if od != DATA_DIR:
        atomic_write(os.path.join(od, "last_report.txt"), text)
    atomic_write(os.path.join(rdir, "report-%s.txt" % time.strftime("%Y%m%d-%H%M%S")), text)
    files = sorted(os.listdir(rdir))
    for f in files[:-30]:
        try:
            os.remove(os.path.join(rdir, f))
        except OSError:
            pass


def read_last_report():
    try:
        with open(os.path.join(DATA_DIR, "last_report.txt"), "rb") as f:
            return f.read().decode("utf-8", "replace")
    except Exception:
        return ""


# --------------------------------------------------------------------------
# glowne operacje (wywolywane z wtyczki w osobnym watku)
# --------------------------------------------------------------------------

def run_check(opts):
    """
    opts: url, positions(list), mode('positions'|'full'), action('report'|'update'),
          tol_mhz, backups, verify_ssl, snapshot(bool), ignore_bouquets, autoreplace
    Zwraca dict: summary, report, changes(int), updated(bool), error,
                 scan_tps (TP do skanu: zmienione/nowe/usuniete + nieudane wczesniej),
                 bq_changed (pliki bukietow zmienione - trzeba przeladowac)
    """
    ensure_dir(DATA_DIR)
    state_path = os.path.join(DATA_DIR, "state.json")
    state = load_json(state_path, {})
    tol = int(opts.get("tol_mhz", 3)) * 1000
    positions = opts.get("positions") or [130]
    ign_text = opts.get("ignore_bouquets", DEFAULT_IGNORE)
    R = []
    R.append(_("SatWatch %s  |  %s") % (VERSION, time.strftime("%Y-%m-%d %H:%M")))
    R.append(_("Zrodlo: %s") % opts["url"])
    base = {"changes": 0, "updated": False, "error": None, "scan_tps": [], "bq_changed": False}

    try:
        data, fresh = download(opts["url"], state, verify=opts.get("verify_ssl", True))
    except Exception as e:
        msg = _("Blad pobierania: %s") % e
        R.append(msg)
        text = "\n".join(R)
        save_report(text)
        return dict(base, summary=msg, report=text, error=msg, scan_tps=pending_failed_tps())

    R.append(_("Plik zdalny: %s (ETag %s)") % (_("pobrany") if fresh else _("bez zmian od ostatniego pobrania"),
                                            (state.get("etag") or "-")[:20]))
    remote_root, remote_sats = parse_sat_xml(data)
    lpath = local_xml_path()
    try:
        local_root, local_sats = parse_sat_xml(lpath)
    except Exception as e:
        local_root, local_sats = None, {}
        R.append(_("Uwaga: nie moge odczytac lokalnego %s (%s)") % (lpath, e))
    R.append(_("Plik lokalny: %s") % lpath)
    R.append("")

    diffs = {}
    total = 0
    sane = True
    no_local = set()      # pozycje bez lokalnych danych: odtworzenie z pliku zdalnego, bez skanu
    restore_full = local_root is None
    if restore_full:
        R.append(_("Brak lokalnego satellites.xml - zostanie odtworzony z pliku zdalnego (bez skanu transponderow)."))
    for pos in positions:
        lt = sat_tps(local_sats.get(pos))
        rt = sat_tps(remote_sats.get(pos))
        if not lt and rt:
            no_local.add(pos)
            if not restore_full:
                R.append(_("Pozycja %s: brak w lokalnym pliku - zostanie dodana z pliku zdalnego (bez skanu).") % pos_str(pos))
        if pos not in remote_sats:
            R.append(_("Pozycja %s: BRAK w pliku zdalnym - pomijam") % pos_str(pos))
            sane = False
            continue
        if lt and len(rt) < 0.8 * len(lt):
            R.append(_("Pozycja %s: plik zdalny ma podejrzanie malo TP (%d vs %d lokalnie)")
                     % (pos_str(pos), len(rt), len(lt)))
            sane = False
        d = diff_tps(lt, rt, tol)
        diffs[pos] = d
        total += diff_count(d)
        R.extend(format_tp_diff(pos, d, remote_sats[pos].get("name", "")))
    R.append("")

    # kanaly z bukietow na zmienionych TP
    try:
        hits = bouquet_hits_for_tps(diffs, tol, ignore=parse_ignore(ign_text))
    except Exception as e:
        hits = {}
        R.append(_("Uwaga: blad analizy bukietow: %s") % e)
    if hits:
        R.append(_("Twoje kanaly na zmienionych/usunietych TP:"))
        for label in sorted(hits):
            for prefix in ("zmieniony: ", "przesuniety: ", "usuniety: "):
                if label.startswith(prefix):
                    label = _(prefix) + label[len(prefix):]
                    break
            R.append("  %s" % label)
            for bq, p, name in hits[label]:
                R.append(_('     "%s" poz. %d: %s') % (bq, p, name))
        R.append("")

    # TP do skanu: nowe i zmienione (nowe parametry), usuniete (stare parametry -
    # skan sprawdzi, czy TP jeszcze zyje; nieudany skan niczego nie usuwa)
    scan_new = []
    if sane:
        for pos, d in diffs.items():
            if pos in no_local:
                continue
            for tp in d["added"]:
                scan_new.append(dict(tp, position=pos))
            for a, b, f in d["changed"]:
                scan_new.append(dict(b, position=pos))
            for a, b in d["shifted"]:
                scan_new.append(dict(b, position=pos))
            for tp in d["removed"]:
                scan_new.append(dict(tp, position=pos, removed=True))
    retry = pending_failed_tps()
    scan_tps = merge_tps(scan_new, retry)
    if retry:
        R.append(_("Do ponowienia z poprzedniego skanu: %d TP") % len(retry))

    updated = False
    if opts.get("action") == "update" and total:
        if not sane:
            R.append(_("Aktualizacja WSTRZYMANA - plik zdalny nie przeszedl kontroli."))
        else:
            content = build_output_xml(local_root, remote_root, remote_sats, positions,
                                       "full" if restore_full else opts.get("mode", "positions"), opts["url"])
            backup_and_write(content, int(opts.get("backups", 5)))
            updated = True
            R.append(_("Zaktualizowano %s (kopia w %s/backup).") % (TARGET, DATA_DIR))
            R.append("")
    elif total:
        R.append(_("Tryb 'tylko raport' - satellites.xml nie zostal zmieniony."))
        if restore_full or no_local:
            R.append(_("UWAGA: brak danych satelity w lokalnym pliku - odtworz satellites.xml (np. z %s/backup).") % DATA_DIR)
        R.append("")

    # bukiety: porownanie z ostatnim zrzutem (np. po recznym skanie), podmiany, nowy zrzut
    bq_changed = False
    bq_repl = bq_unres = 0
    if opts.get("snapshot", True):
        try:
            b = process_bouquets(ign_text, opts.get("autoreplace", True))
            R.extend(b["lines"])
            bq_changed = b["changed"]
            bq_repl, bq_unres = b.get("replaced", 0), b.get("unresolved", 0)
        except Exception as e:
            R.append(_("Uwaga: blad zrzutu bukietow: %s") % e)

    state["last_check"] = time.strftime("%Y-%m-%d %H:%M:%S")
    state["last_changes"] = total
    state.pop("pending_scan", None)
    save_json(state_path, state)
    text = "\n".join(R)
    save_report(text)
    if restore_full or no_local:
        summary = _("SatWatch: satellites.xml %s") % (
            _("odtworzony z pliku zdalnego - potrzebny restart Enigma2") if updated else _("bez danych satelity - odtworz plik!"))
        base = dict(base, restored=True)
    else:
        summary = (_("SatWatch: zmian w transponderach: %d") % total) + (_(" (plik zaktualizowany)") if updated else "")
    if bq_repl or bq_unres:
        summary += _(", bukiety: podmieniono %d, do decyzji %d") % (bq_repl, bq_unres)
    return dict(base, summary=summary, report=text, changes=total, updated=updated,
                scan_tps=scan_tps, scan_new=scan_new, retry=retry, bq_changed=bq_changed,
                bq_replaced=bq_repl, unresolved=bq_unres)


def append_report(lines):
    """Dopisuje sekcje do ostatniego raportu (np. wynik skanu)."""
    text = read_last_report().rstrip("\n")
    text = (text + "\n\n" if text else "") + "\n".join(lines)
    save_report(text)
    return text


def snapshot_compare_and_save(ignore_text=None, autoreplace=True):
    return process_bouquets(ignore_text, autoreplace)["lines"]


def preview_bouquets(old, new, ignore_text=None):
    """Porownanie z informacja, co zrobilby automat - bez zmian w plikach."""
    res = compare_snapshots(old, new, parse_ignore(ignore_text))
    for e in res["lost"]:
        c, why = decide(e)
        e["reason"] = why
        if c:
            e["would"] = c
    return format_bouquet_report(res, old.get("time"))


def snapshot_compare_only(ignore_text=None):
    """Porownanie bez nadpisywania zrzutu i bez podmian (podglad)."""
    old = load_snapshot()
    if not old:
        return _("Brak zrzutu referencyjnego - najpierw zapisz zrzut.")
    new = take_snapshot()
    L = [_("SatWatch %s  |  %s  |  podglad (bez zmian)") % (VERSION, time.strftime("%Y-%m-%d %H:%M")), ""]
    L.extend(preview_bouquets(old, new, ignore_text))
    text = "\n".join(L)
    save_report(text)
    return text


def snapshot_save_only():
    old = load_snapshot() or {}
    snap = take_snapshot()
    # nierozwiazane z poprzedniego zrzutu zostaja, jesli nadal sa w bukietach i brak ich w lamedb
    inbq = set(b[3] for b in snap["bouquets"] if b[3])
    snap["ghosts"] = dict((k, v) for k, v in old.get("ghosts", {}).items()
                          if k in inbq and k not in snap["services"])
    save_snapshot(snap)
    return _("Zapisano zrzut (%s): %d serwisow w lamedb, %d wpisow w bukietach.") % (
        snap["time"], len(snap["services"]), len(snap["bouquets"]))


# --------------------------------------------------------------------------
# symulacja: stara lista + aktualna lamedb -> automat -> porownanie z wzorcem
# --------------------------------------------------------------------------

def simulate(old_dir, out_dir, ref_dir="/etc/enigma2", ignore_text=DEFAULT_IGNORE):
    """
    Nic nie zmienia w ref_dir. W out_dir powstaje: lamedb (kopia aktualnej z ref_dir),
    bukiety starej listy poprawione przez automat, raport.txt i porownanie.txt.
    """
    global DATA_DIR
    if os.path.abspath(out_dir) in (os.path.abspath(ref_dir), os.path.abspath(old_dir)):
        raise SystemExit(_("KATALOG_WYNIKU musi byc inny niz stara lista i wzorzec"))
    ensure_dir(out_dir)
    for f in os.listdir(out_dir):
        p = os.path.join(out_dir, f)
        if os.path.isfile(p):
            os.remove(p)
    # 1. stara lista = stan przed skanem
    old = take_snapshot(old_dir)
    # 2. po "skanie": stare bukiety + aktualna lamedb
    for f in os.listdir(old_dir):
        if f.startswith("userbouquet.") or f in ("bouquets.tv", "bouquets.radio"):
            shutil.copy2(os.path.join(old_dir, f), os.path.join(out_dir, f))
    for f in ("lamedb", "lamedb5"):
        if os.path.exists(os.path.join(ref_dir, f)):
            shutil.copy2(os.path.join(ref_dir, f), os.path.join(out_dir, f))
    new = take_snapshot(out_dir)
    # 3. automat na kopii (kopie zapasowe tez w out_dir)
    DATA_DIR = os.path.join(out_dir, "satwatch-data")
    ign = parse_ignore(ignore_text)
    res = compare_snapshots(old, new, ign)
    repl = {}
    for e in res["lost"]:
        cand, why = decide(e)
        e["reason"] = why
        if cand:
            e["replaced"] = cand
            repl[str_key(e["key"])] = (str_key(cand["key"]), cand["type"], cand["srcid"])
    if repl:
        apply_bouquet_replacements(repl, ign, out_dir)
    L = [_("SatWatch %s - SYMULACJA  |  %s") % (VERSION, time.strftime("%Y-%m-%d %H:%M")),
         _("stara lista: %s (%d serwisow, %d wpisow w bukietach)") % (old_dir, len(old["services"]), len(old["bouquets"])),
         _("lamedb po 'skanie': %s (%d serwisow)") % (ref_dir, len(new["services"])), ""]
    L.extend(format_bouquet_report(res, _("stara lista")))
    rep = "\n".join(L) + "\n"
    atomic_write(os.path.join(out_dir, "raport.txt"), rep)
    # 4. porownanie wyniku z Twoimi aktualnymi bukietami (pozycja po pozycji, ten sam plik)
    tps, services = parse_lamedb(out_dir)

    def name(k):
        s = services.get(k)
        return s["name"] if s else ("?" if k is None else key_str(k))

    def entries(d, fname):
        out = []
        path = os.path.join(d, fname)
        if not os.path.exists(path):
            return None
        with open(path, "rb") as fh:
            for ln in fh.read().decode("utf-8", "replace").splitlines():
                if ln.startswith("#SERVICE "):
                    ref = ln[9:].strip()
                    fl = ref.split(":")
                    try:
                        if (int(fl[1], 16) if len(fl) > 1 and fl[1] else 0) & 64 or _RE_FROM.search(ref):
                            continue
                    except ValueError:
                        pass
                    out.append(_ref_key(ref))
        return out

    replaced_keys = set(repl.keys())
    P = [_("Porownanie: stara lista po automacie vs Twoja aktualna lista (%s)") % ref_dir,
         _("  (roznice moga wynikac tez z Twoich wlasnych zmian - przestawien, dodanych kanalow)"), ""]
    tot = {"same": 0, "auto_ok": 0, "auto_diff": 0, "other": 0}
    for fname, bname in _bouquet_files(out_dir):
        if bname.lower() in ign:
            continue
        a = entries(out_dir, fname)
        b = entries(ref_dir, fname)
        if b is None:
            P.append(_('"%s": brak takiego pliku w aktualnej liscie - pomijam') % bname)
            continue
        diffs = []
        same = 0
        for i in range(max(len(a), len(b))):
            ka = a[i] if i < len(a) else None
            kb = b[i] if i < len(b) else None
            if ka == kb:
                same += 1
                continue
            diffs.append((i + 1, ka, kb))
        tot["same"] += same
        sa, sb = set(x for x in a if x), set(x for x in b if x)
        only_a, only_b = sa - sb, sb - sa
        P.append(_('"%s": te same pozycje %d z %d; kanaly tylko u automatu %d, tylko u Ciebie %d')
                 % (bname, same, max(len(a), len(b)), len(only_a), len(only_b)))
        if only_a or only_b:
            for k in sorted(only_a, key=name)[:25]:
                P.append(_("   automat ma, Ty nie:  %s%s") % (name(k), _("  (PODMIANA)") if k in
                                                         set(v[0] for v in repl.values()) else ""))
            for k in sorted(only_b, key=name)[:25]:
                P.append(_("   Ty masz, automat nie: %s") % name(k))
    # trafnosc podmian: czy nowy ref automatu jest gdziekolwiek w Twoich bukietach
    mine = set()
    for fname, bname in _bouquet_files(ref_dir):
        mine |= set(x for x in (entries(ref_dir, fname) or []) if x)
    hit = [e for e in res["lost"] if e.get("replaced") and str_key(e["replaced"]["key"]) in mine]
    miss = [e for e in res["lost"] if e.get("replaced") and str_key(e["replaced"]["key"]) not in mine]
    P.append("")
    P.append(_("Podmiany, ktore masz tez u siebie (trafione): %d") % len(hit))
    P.append(_("Podmiany, ktorych u siebie nie masz (do sprawdzenia): %d") % len(miss))
    for e in miss:
        P.append("   %s -> %s (%s)" % (e["name"], e["replaced"]["name"], display_reason(e.get("reason", ""))))
    P.append("")
    P.append(_("Podmiany automatu: %d, do decyzji: %d (szczegoly w raport.txt)")
             % (len(repl), len([e for e in res["lost"] if not e.get("replaced")])))
    cmpf = "\n".join(P) + "\n"
    atomic_write(os.path.join(out_dir, "porownanie.txt"), cmpf)
    print(rep)
    print(cmpf)
    print(_("Pliki: %s/raport.txt, %s/porownanie.txt (nic nie zmieniono w %s)") % (out_dir, out_dir, ref_dir))


# --------------------------------------------------------------------------
# CLI do testow na PC/dekoderze (bez GUI)
# --------------------------------------------------------------------------

if __name__ == "__main__":
    import sys
    if len(sys.argv) >= 4 and sys.argv[1] == "diff":
        _, ls = parse_sat_xml(sys.argv[2])
        _, rs = parse_sat_xml(sys.argv[3])
        for p in parse_positions(" ".join(sys.argv[4:]) or "130"):
            print("\n".join(format_tp_diff(p, diff_tps(sat_tps(ls.get(p)), sat_tps(rs.get(p))))))
    elif len(sys.argv) >= 4 and sys.argv[1] == "compare":
        o = load_json(sys.argv[2], {})
        n = take_snapshot(sys.argv[3])
        ign = sys.argv[4] if len(sys.argv) > 4 else DEFAULT_IGNORE
        print("\n".join(preview_bouquets(o, n, ign)))
    elif len(sys.argv) >= 4 and sys.argv[1] == "symuluj":
        # symuluj STARA_LISTA OUT [WZORZEC=/etc/enigma2]
        simulate(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else "/etc/enigma2",
                 sys.argv[5] if len(sys.argv) > 5 else DEFAULT_IGNORE)
    elif len(sys.argv) >= 4 and sys.argv[1] == "snapshot":
        save_json(sys.argv[3], take_snapshot(sys.argv[2]))
    else:
        print(_("uzycie: core.py diff LOCAL REMOTE [poz...] | snapshot E2DIR OUT.json | compare OLD.json E2DIR [\"Bukiet1,Bukiet2\"]\n"
                "        core.py symuluj KATALOG_STAREJ_LISTY KATALOG_WYNIKU [KATALOG_WZORCA=/etc/enigma2]"))
