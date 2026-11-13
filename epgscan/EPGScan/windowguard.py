# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Close EPG Scan dialogs when the receiver enters standby."""

from enigma import eTimer

from .common import log


def _module(value):
	obj = value if isinstance(value, type) else value.__class__
	return getattr(obj, "__module__", "")


def _is_plugin_entry(entry):
	parts = entry if isinstance(entry, (tuple, list)) else (entry,)
	return any(_module(part).startswith("Plugins.Extensions.EPGScan") for part in parts if part is not None)


def _dialog(entry):
	parts = entry if isinstance(entry, (tuple, list)) else (entry,)
	return next((part for part in parts if callable(getattr(part, "close", None))), None)


def close_plugin_dialogs(session):
	stack = getattr(session, "dialog_stack", None)
	if stack is None:
		stack = getattr(session, "dialogStack", None)
	if not isinstance(stack, (tuple, list)):
		current = getattr(session, "current_dialog", None)
		if current is not None and _is_plugin_entry(current):
			_close_dialog(current)
			return 1
		return 0

	owned = [index for index, entry in enumerate(stack) if _is_plugin_entry(entry)]
	if not owned:
		return 0
	first_owned = min(owned)
	closed = 0
	seen = set()
	for entry in reversed(list(stack[first_owned:])):
		dialog = _dialog(entry)
		if dialog is None or id(dialog) in seen:
			continue
		seen.add(id(dialog))
		if _close_dialog(dialog):
			closed += 1
	return closed


def _close_dialog(dialog):
	try:
		dialog._epgscan_standby_close = True
		name = dialog.__class__.__name__
		if name in ("EPGScanSetup", "SatWatchSetup"):
			dialog.cancel()
		else:
			dialog.close()
		return True
	except Exception as e:
		log("Czuwanie: nie mozna zamknac okna %s: %s" % (dialog.__class__.__name__, e))
		return False


class StandbyWindowWatchdog(object):
	def __init__(self, session):
		self.session = session
		self.was_standby = False
		self.timer = eTimer()
		self.timer.callback.append(self.check)
		self.timer.start(1000, False)

	def check(self):
		try:
			import Screens.Standby
			in_standby = bool(Screens.Standby.inStandby)
		except Exception as e:
			log("Czuwanie: nie mozna sprawdzic stanu dekodera: %s" % e)
			in_standby = False
		if in_standby and not self.was_standby:
			count = close_plugin_dialogs(self.session)
			if count:
				log("Czuwanie: zamknieto %d okien EPG Scan; skan w tle jest kontynuowany" % count)
		self.was_standby = in_standby
		self.timer.start(1000, False)


def start(session):
	return StandbyWindowWatchdog(session)
