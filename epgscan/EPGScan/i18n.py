# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Plugin-local translations; never change Enigma2's global language."""

import gettext
import logging
import os
import re

_selection = lambda: "enigma"
_catalog = None
_choice_labels = {}
_language_warning = False


def configure(selection):
	global _selection
	_selection = selection


def language():
	global _language_warning
	selected = _selection()
	if selected in ("pl", "en"):
		return selected
	try:
		from Components.Language import language as enigma_language
		code = enigma_language.getLanguage().lower().replace("-", "_")
		_language_warning = False
		return "pl" if code.split("_", 1)[0] == "pl" else "en"
	except Exception as e:
		if not _language_warning:
			logging.getLogger(__name__).warning("Cannot read Enigma2 language; using Polish: %s", e)
			_language_warning = True
		return "pl"


def _(message):
	global _catalog
	if language() != "en":
		return message
	if _catalog is None:
		try:
			_catalog = gettext.translation(
				"EPGScan", os.path.join(os.path.dirname(__file__), "locale"), languages=["en"])
		except OSError as e:
			logging.getLogger(__name__).warning("Cannot load EPG Scan English translations: %s", e)
			_catalog = gettext.NullTranslations()
	return _catalog.gettext(message)


def display_scan_summary(summary):
	match = re.match(r"^(\d+)/(\d+) transponderow, (\d+) bledow(?:, (\d+) pominietych)?, czas (\d+):(\d\d)$", summary)
	if match:
		done, total, errors, skipped, minutes, seconds = match.groups()
		extra = _(", %d pominietych") % int(skipped) if skipped else ""
		return _("%d/%d transponderow, %d bledow%s, czas %d:%02d") % (
			int(done), int(total), int(errors), extra, int(minutes), int(seconds))
	match = re.match(r"^doczytywanie: (\d+)/(\d+) transponderow, zasieg wydluzony na (\d+) "
		r"\(maks\. \+(\d+\.\d+) h\), czas (\d+):(\d\d)$", summary)
	if match:
		done, total, extended, hours, minutes, seconds = match.groups()
		return _("doczytywanie: %d/%d transponderow, zasieg wydluzony na %d (maks. +%.1f h), czas %d:%02d") % (
			int(done), int(total), int(extended), float(hours), int(minutes), int(seconds))
	if summary.startswith("import XMLTV"):
		rest = summary[len("import XMLTV"):]
		if rest.startswith(" po wyczyszczeniu EPG"):
			rest = _(" po wyczyszczeniu EPG") + rest[len(" po wyczyszczeniu EPG"):]
		return _("import XMLTV") + rest
	return _(summary)


def display_engine_note(note):
	prefixes = (
		("Skan parametrow podczas EPG nieudany: ", _("Skan parametrow podczas EPG nieudany: ")),
		("Skan parametrow nieudany: ", _("Skan parametrow nieudany: ")),
		("Zapis czesciowych wynikow PAT/PMT nieudany: ", _("Zapis czesciowych wynikow PAT/PMT nieudany: ")),
		("Kopia EPG: ", _("Kopia EPG: ")),
		("Import XMLTV: ", _("Import XMLTV: ")),
	)
	for prefix, translated in prefixes:
		if note.startswith(prefix):
			return translated + note[len(prefix):]
	return display_scan_summary(note)


def translate_choices(element):
	"""Update display labels only; preserve persisted selection values."""
	try:
		raw = element.choices.choices
		set_choices = element.setChoices
	except AttributeError:
		return
	if isinstance(raw, dict):
		current = list(raw.items())
	else:
		current = [(item[0], item[1]) if isinstance(item, tuple) else (item, item) for item in raw]
	key = id(element)
	if key not in _choice_labels or _choice_labels[key][0] is not element:
		_choice_labels[key] = (element, dict(current))
	original = _choice_labels[key][1]
	for value, label in current:
		if value not in original:
			original[value] = label
	choices = [(value, _(original[value])) for value, label in current]
	if choices != current:
		value = element.value
		set_choices(choices, default=getattr(element, "default", value))
		element.value = value
