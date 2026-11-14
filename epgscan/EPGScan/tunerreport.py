# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Sample tuner use and scheduling conditions without affecting scan decisions."""

import json
import os
import time
from collections import deque

from .common import CHANNEL_PARAMS_FILE, log
from .i18n import _


TRACE_LIMIT = 120


def _tuner(no):
	return chr(ord("A") + no) if 0 <= no < 26 else "?"


class TunerTrace(object):
	def __init__(self, total, reserve, limit, gap, started, epg_borrowed=None, independent=True):
		self.total = total
		self.reserve = reserve
		self.limit = limit
		self.gap = gap
		self.started = started
		self.epg_borrowed = epg_borrowed
		self.independent = independent
		self.parallel_fallback = False
		self.samples = 0
		self.min_free = None
		self.max_free = None
		self.max_own = 0
		self.tuners = set()
		self.attempts = {"independent": 0, "shared": 0, "failed": 0}
		self.seconds = {}
		self.first = []
		self.last = deque(maxlen=TRACE_LIMIT // 2)
		self.changes = 0
		self.previous = None
		self.previous_mono = None
		self.closed = False

	def note_tuner(self, number):
		if number >= 0:
			self.tuners.add(number)

	def note_start(self, shared, success):
		self.attempts["shared" if shared else "independent"] += 1
		if not success:
			self.attempts["failed"] += 1

	def _advance(self, monotonic):
		if self.previous_mono is not None:
			elapsed = max(0, monotonic - self.previous_mono)
			for key in self.previous["conditions"]:
				self.seconds[key] = self.seconds.get(key, 0) + elapsed
		self.previous_mono = monotonic

	def sample(self, engine, now, queued, ready, own, tuning, gap, monotonic=None):
		if self.closed:
			return
		self._advance(time.monotonic() if monotonic is None else monotonic)
		free = engine.free_tuners()
		timer = getattr(engine, "timer_hold", 0)
		timer_error = getattr(engine, "timer_check_error", None) or ""
		effective = engine.eff_reserve()
		conditions = []
		if queued and not ready:
			conditions.append("deferred")
		if ready:
			if timer_error:
				conditions.append("timer_error")
			elif timer:
				conditions.append("timer")
			if tuning:
				conditions.append("tuning")
			if gap:
				conditions.append("gap")
			if own >= self.limit:
				conditions.append("limit")
			if free <= effective:
				conditions.append("reserve")
		jobs = []
		for job in getattr(engine, "active", []):
			number = getattr(job, "tuner_no", -1)
			self.note_tuner(number)
			jobs.append([number, bool(getattr(job, "shared", False)), getattr(job, "name", "")])
		jobs.sort(key=lambda item: (item[0], item[1], item[2]))
		state = {
			"free": free, "used": sorted(getattr(engine, "used", ())),
			"other": sorted(getattr(engine, "other_used", ())), "jobs": jobs,
			"own": own, "reserve": effective, "timer": timer, "timer_error": timer_error,
			"queued": queued, "ready": ready, "conditions": conditions,
		}
		self.samples += 1
		self.min_free = free if self.min_free is None else min(self.min_free, free)
		self.max_free = free if self.max_free is None else max(self.max_free, free)
		self.max_own = max(self.max_own, own)
		if state != self.previous:
			event = dict(state, at=int(now))
			self.changes += 1
			if len(self.first) < TRACE_LIMIT // 2:
				self.first.append(event)
			else:
				self.last.append(event)
		self.previous = state

	def finish(self, finished, cancelled=False, monotonic=None):
		if not self.closed:
			if self.previous is not None:
				self._advance(time.monotonic() if monotonic is None else monotonic)
			self.closed = True
		events = self.first + list(self.last)
		report = {
			"started": self.started, "finished": finished, "cancelled": bool(cancelled),
			"total": self.total, "reserve": self.reserve, "limit": self.limit, "gap": self.gap,
			"samples": self.samples, "min_free": self.min_free, "max_free": self.max_free,
			"max_own": self.max_own, "tuners": sorted(self.tuners), "attempts": dict(self.attempts),
			"seconds": {key: round(value, 1) for key, value in self.seconds.items()},
			"events": events, "omitted": max(0, self.changes - len(events)),
		}
		if self.epg_borrowed is not None:
			report["epg_borrowed"] = self.epg_borrowed
			report["independent"] = self.independent
			report["parallel_fallback"] = self.parallel_fallback
		return report


def _path(kind):
	if kind not in ("epg", "params"):
		raise ValueError("Nieznany rodzaj raportu tunerow: %s" % kind)
	return os.path.join(os.path.dirname(CHANNEL_PARAMS_FILE), "tuners-%s.json" % kind)


def save_trace(kind, report):
	path = _path(kind)
	directory = os.path.dirname(path)
	if not os.path.isdir(directory):
		os.makedirs(directory, exist_ok=True)
	tmp = path + ".tmp"
	try:
		with open(tmp, "w", encoding="utf-8") as f:
			json.dump(report, f, ensure_ascii=False, indent=1, sort_keys=True)
			f.flush()
			os.fsync(f.fileno())
		os.replace(tmp, path)
	except (OSError, TypeError, ValueError):
		try:
			if os.path.exists(tmp):
				os.unlink(tmp)
		except OSError as e:
			log("Raport tunerow: nie mozna usunac pliku tymczasowego: %s" % e)
		raise


def close_trace(trace, kind, finished, cancelled=False):
	if trace is None:
		return None
	report = trace.finish(finished, cancelled)
	try:
		save_trace(kind, report)
	except (OSError, TypeError, ValueError) as e:
		report["save_error"] = str(e)
		log("Nie udalo sie zapisac raportu tunerow (%s): %s" % (kind, e))
	return report


def load_trace(kind):
	try:
		with open(_path(kind), encoding="utf-8") as f:
			report = json.load(f)
	except FileNotFoundError:
		return None
	if not isinstance(report, dict) or not isinstance(report.get("events"), list):
		raise ValueError("Nieprawidlowy raport tunerow")
	return report


def format_trace(report):
	if not report:
		return ""
	def clock(value):
		return time.strftime("%d.%m.%Y %H:%M:%S", time.localtime(value)) if value else "-"
	def labels(numbers):
		return ", ".join(_tuner(number) for number in numbers) or "-"
	reasons = {
		"deferred": _("brak gotowych zadan (grupa aktywna lub opozniona)"),
		"timer_error": _("blad odczytu timerow"),
		"timer": _("nadchodzacy timer"),
		"tuning": _("trwa strojenie EPG"),
		"gap": _("odstep miedzy strojeniami"),
		"limit": _("limit rownoleglych skanow"),
		"reserve": _("rezerwa wolnych tunerow"),
	}
	attempts = report.get("attempts", {})
	lines = [
		_("Wykorzystanie tunerow (%s)") % (_("przerwany") if report.get("cancelled") else _("zakonczony")),
		_("Poczatek: %s | Koniec: %s") % (clock(report.get("started")), clock(report.get("finished"))),
		_("Tunery: %d | Rezerwa podstawowa: %d | Limit skanow: %d | Odstep: %d s") % (
			report.get("total", 0), report.get("reserve", 0), report.get("limit", 0), report.get("gap", 0)),
	]
	if "epg_borrowed" in report:
		if report.get("independent"):
			lines.append(_("Dane dotycza niezaleznej fazy PAT/PMT. Pozyczenia dostrojen EPG: %d; szczegoly w statystyce EPG.") %
				report["epg_borrowed"])
		else:
			lines.append(_("Niezalezna faza PAT/PMT nie rozpoczela sie. Pozyczenia dostrojen EPG: %d; szczegoly w statystyce EPG.") %
				report["epg_borrowed"])
		if report.get("parallel_fallback"):
			lines.append(_("Po bledzie rownoleglego strojenia ponawiano z odstepem."))
	if not report.get("samples"):
		lines.append(_("Brak probek wykorzystania tunerow."))
		if report.get("save_error"):
			lines.append(_("Nie udalo sie zapisac raportu tunerow: %s") % report["save_error"])
		return "\n".join(lines)
	lines += [
		_("Wolne (probki): min %d, maks %d | Maks. niezaleznych grup: %d | Tunery skanu: %s") % (
			report["min_free"], report["max_free"], report.get("max_own", 0), labels(report.get("tuners", []))),
		_("Proby uruchomienia: niezalezne %d, wspoldzielone %d, nieudane %d") % (
			attempts.get("independent", 0), attempts.get("shared", 0), attempts.get("failed", 0)),
		_("Warunki przy oczekujacych zadaniach (czasy moga sie nakladac):"),
	]
	seconds = report.get("seconds", {})
	present = [(reasons[key], seconds[key]) for key in reasons if seconds.get(key, 0) > 0]
	lines += ["- %s: %.1f s" % pair for pair in present] if present else [_("- brak zaobserwowanych ograniczen")]
	lines += ["", _("Przebieg zmian (stan zgloszony przez Enigma2; kolejka zadan i gotowe grupy):")]
	events = report.get("events", [])
	middle = min(TRACE_LIMIT // 2, len(events))
	for index, event in enumerate(events):
		if report.get("omitted", 0) and index == middle:
			lines.append(_("... pominieto %d zmian; podsumowanie obejmuje caly skan ...") % report["omitted"])
		jobs = ", ".join("%s%s: %s" % (_tuner(no), "*" if shared else "", name)
			for no, shared, name in event.get("jobs", [])) or "-"
		restrictions = ", ".join(reasons[key] for key in event.get("conditions", []) if key in reasons) or "-"
		lines.append(_("%s | wolne %d/%d | skan %s | inne %s | uzyte %s") % (
			time.strftime("%H:%M:%S", time.localtime(event["at"])), event["free"], report["total"],
			jobs, labels(event.get("other", [])), labels(event.get("used", []))))
		lines.append(_("  grupy %d/%d | rezerwa %d (timery %d) | kolejka %d, gotowe %d | warunki: %s") % (
			event["own"], report["limit"], event["reserve"], event["timer"],
			event["queued"], event["ready"], restrictions))
		if event.get("timer_error"):
			lines.append(_("  Blad timerow: %s") % event["timer_error"])
	lines.append(_("* tuner wspoldzielony; ? tuner jeszcze nieznany."))
	if report.get("save_error"):
		lines.append(_("Nie udalo sie zapisac raportu tunerow: %s") % report["save_error"])
	return "\n".join(lines)
