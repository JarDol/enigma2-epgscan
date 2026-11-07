# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - statystyka EPG: podsumowanie, z niego kanaly od najkrotszego EPG i duble

import os

from Screens.Screen import Screen
from Components.ActionMap import ActionMap
from Components.ScrollLabel import ScrollLabel
from enigma import eTimer

from .common import VERSION, log, log_path, epg_stats, stats_text, all_refs, log_dups, export_dups, \
	build_jobs, channel_table, whole_cache_stats, fmt_span, keys_skin, init_keys, set_keys


def _skin(title):
	return """
	<screen position="center,center" size="1600,920" title="%s">
		<widget name="text" position="20,10" size="1560,815" font="Regular;30" />
		%s
	</screen>""" % (title, keys_skin(20, 845, 1560))


class _Base(Screen):
	"""Ekran z przewijanym tekstem liczonym w tle (najpierw ekran, potem liczenie)."""

	def __init__(self, session, title):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan %s - %s" % (VERSION, title))
		self["text"] = ScrollLabel("Liczenie...")
		init_keys(self)
		self.timer = eTimer()
		self.timer.callback.append(self.compute)
		self.onLayoutFinish.append(self.layout_done)

	def scroll_actions(self):
		return {
			"cancel": self.close,
			"red": self.close,
			"up": self["text"].pageUp,
			"down": self["text"].pageDown,
			"left": self["text"].pageUp,
			"right": self["text"].pageDown,
		}

	def layout_done(self):
		self.show_keys()
		self.timer.start(200, True)

	def show_keys(self):
		set_keys(self, red="Zamknij")

	def compute(self):
		pass


def report_sources():
	"""[(opis, adres raportu)] zrodel XMLTV udostepniajacych raport - najpierw zaznaczone w ustawieniach."""
	try:
		from .common import cfg
		from .xmltv import load_sources
		srcs, _ = load_sources()
	except Exception:
		return []
	sel = [x for x in cfg.xmltv_sources.value.split("|") if x]
	out, seen = [], set()
	for s in sorted(srcs, key=lambda x: x.desc not in sel):
		if s.report and s.report not in seen:
			seen.add(s.report)
			out.append((s.desc, s.report))
	return out


class EPGScanInfo(_Base):
	"""Podsumowanie EPG (jak dotychczas) + przejscie do kanalow i dubli."""
	skin = _skin("EPG Scan - statystyka EPG")

	def __init__(self, session, engine):
		_Base.__init__(self, session, "statystyka EPG")
		self.engine = engine
		acts = self.scroll_actions()
		acts.update({"ok": self.close})
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions"], acts, -1)
		self.reports = report_sources()

	def show_keys(self):
		set_keys(self, red="Zamknij")

	def server_report(self):
		"""Raport z serwera wlasnego zrodla XMLTV (np. epgpl); przy kilku - wybor."""
		if not self.reports:
			return
		from .setup import EPGScanReport
		if len(self.reports) == 1:
			desc, url = self.reports[0]
			self.session.open(EPGScanReport, desc, url)
			return
		from .common import EPGChoiceBox as ChoiceBox
		self.session.openWithCallback(self.report_chosen, ChoiceBox, title="Raport ktorego serwera?",
			list=[(d, (d, u)) for d, u in self.reports])

	def report_chosen(self, choice):
		if choice:
			from .setup import EPGScanReport
			self.session.openWithCallback(lambda *a: self.server_report(), EPGScanReport, choice[1][0], choice[1][1])

	def compute(self):
		e = self.engine
		jobs = e.jobs if (e.running and not e.topup) else build_jobs()   # zawsze pelna lista
		bq = epg_stats(all_refs())
		refs = []
		for j in jobs:
			refs.extend(j.all_refs)
		al = epg_stats(refs) if refs else None
		nall, cov, nev, finfo = whole_cache_stats()
		log("Statystyka EPG (bukiety): " + stats_text(bq))
		if al:
			log("Statystyka EPG (wszystkie): " + stats_text(al))
		nl = lambda s: s.replace(", zasieg", "\nzasieg")
		t = ["Kanaly z bukietow:", nl(stats_text(bq)), ""]
		if al:
			t += ["Wszystkie kanaly skanowanych transponderow:", nl(stats_text(al)), ""]
		t += ["Cala pamiec EPG: kanalow na liscie %d, z EPG %d, audycji %d" % (nall, cov, nev),
			"Plik %s" % finfo, "", "Log: %s" % log_path(), "",
			"Kanaly od najkrotszego EPG, duble, pobieranie na dekoderze i raporty serwerow - w menu Info/EPG."]
		self["text"].setText("\n".join(t))

	def channels(self):
		self.session.open(EPGScanChannels)

	def dups(self):
		self.session.open(EPGScanDups)


class EPGScanChannels(_Base):
	skin = _skin("EPG Scan - kanaly od najkrotszego EPG")

	def __init__(self, session):
		_Base.__init__(self, session, "kanaly od najkrotszego EPG")
		acts = self.scroll_actions()
		acts["ok"] = self.close
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions"], acts, -1)

	def compute(self):
		rows = channel_table()
		empty = len([r for r in rows if r[0] == 0])
		short = len([r for r in rows if 0 < r[0] < 86400])
		t = ["Kanaly z bukietow: %d, bez EPG: %d, krocej niz 1 dzien: %d" % (len(rows), empty, short), "",
			"%-10s %7s   %-16s %s" % ("zasieg", "audycji", "bukiet", "kanal")]
		for sec, n, bqn, name in rows:
			t.append("%-10s %7d   %-16s %s" % (fmt_span(sec), n, bqn[:16], name))
		self["text"].setText("\n".join(t))


class EPGScanDups(_Base):
	skin = _skin("EPG Scan - nakladajace sie audycje")

	def __init__(self, session):
		_Base.__init__(self, session, "nakladajace sie audycje (duble)")
		acts = self.scroll_actions()
		acts["ok"] = self.close
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions"], acts, -1)

	def compute(self):
		log_dups("(Info/EPG)")
		path, pairs = export_dups(all_refs())
		if not path:
			self["text"].setText("Nie udalo sie przygotowac listy - szczegoly w logu %s" % log_path())
			return
		if not pairs:
			self["text"].setText("Brak nakladajacych sie audycji w kanalach z bukietow.\n\nPlik: %s" % path)
			return
		try:
			with open(path, encoding="utf-8", errors="ignore") as f:
				body = f.read()
		except Exception:
			body = ""
		head = "Par nakladajacych sie audycji: %d (plik: %s)\n\n" % (pairs, path)
		self["text"].setText(head + body.split("\n\n", 1)[-1])
