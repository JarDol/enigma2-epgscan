# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Advertise the client API through Avahi without changing its configuration."""

import os
import shutil
import subprocess

from Components.config import config

from .i18n import _

SERVICE_FILE = "/etc/avahi/services/epgscan-e2stream.service"
MARKER = "<!-- Managed by EPG Scan -->"
SERVICE_TYPE = "_epgscan._tcp"
_status = "mDNS: nieaktywne"


def _openwebif_port(name):
	webif = getattr(config, "OpenWebif", None)
	element = getattr(webif, name, None)
	try:
		value = int(element.value)
	except (AttributeError, TypeError, ValueError):
		return None
	return value if 1 <= value <= 65535 else None


def _service_xml(port):
	rows = [
		'<?xml version="1.0" standalone="no"?>',
		MARKER,
		'<!DOCTYPE service-group SYSTEM "avahi-service.dtd">',
		"<service-group>",
		'  <name replace-wildcards="yes">%h EPG Scan</name>',
		"  <service>",
		"    <type>%s</type>" % SERVICE_TYPE,
		"    <port>%d</port>" % port,
		"    <txt-record>api=1</txt-record>",
	]
	for name, key in (("port", "webif-port"), ("streamport", "stream-port")):
		value = _openwebif_port(name)
		if value is not None:
			rows.append("    <txt-record>%s=%d</txt-record>" % (key, value))
	rows += ["  </service>", "</service-group>", ""]
	return "\n".join(rows)


def _owned():
	if not os.path.exists(SERVICE_FILE):
		return False
	with open(SERVICE_FILE, encoding="utf-8") as file:
		return MARKER in file.read()


def _daemon_status():
	daemon = shutil.which("avahi-daemon")
	if not daemon:
		for path in ("/usr/sbin/avahi-daemon", "/sbin/avahi-daemon"):
			if os.path.isfile(path) and os.access(path, os.X_OK):
				daemon = path
				break
	if not daemon:
		return "mDNS: brak Avahi"
	try:
		result = subprocess.run([daemon, "--check"], stdout=subprocess.DEVNULL,
			stderr=subprocess.DEVNULL, timeout=3, check=False)
	except (OSError, subprocess.SubprocessError) as e:
		raise RuntimeError(_("Nie mozna sprawdzic Avahi: %s") % e)
	return "mDNS: Avahi dziala" if result.returncode == 0 else "mDNS: Avahi nie dziala"


def publish(port):
	global _status
	port = int(port)
	if not 1 <= port <= 65535:
		raise ValueError(_("Nieprawidlowy port uslugi mDNS: %d") % port)
	if os.path.exists(SERVICE_FILE) and not _owned():
		raise ValueError(_("Plik uslugi mDNS istnieje i nie nalezy do EPG Scan: %s") % SERVICE_FILE)
	directory = os.path.dirname(SERVICE_FILE)
	if not os.path.isdir(directory):
		os.makedirs(directory)
	tmp = SERVICE_FILE + ".tmp"
	try:
		with open(tmp, "w", encoding="utf-8") as file:
			file.write(_service_xml(port))
		os.replace(tmp, SERVICE_FILE)
	except OSError:
		if os.path.exists(tmp):
			os.unlink(tmp)
		raise
	_status = _daemon_status()
	return _status


def unpublish():
	global _status
	if _owned():
		os.unlink(SERVICE_FILE)
	_status = "mDNS: nieaktywne"


def status():
	return _status
