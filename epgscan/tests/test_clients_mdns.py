import importlib
import json
import os
import sys
import tempfile
import types
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch


ROOT = os.path.dirname(os.path.dirname(__file__))
PACKAGE = "clients_mdns_test_plugin"
package = types.ModuleType(PACKAGE)
package.__path__ = [os.path.join(ROOT, "EPGScan")]
sys.modules[PACKAGE] = package

common = types.ModuleType(PACKAGE + ".common")
common.E2 = tempfile.gettempdir()
common.VERSION = "test"
common.log = lambda _message: None
common.cfg = types.SimpleNamespace(cli_api=types.SimpleNamespace(value=True),
	cli_port=types.SimpleNamespace(value=8770), cli_token=types.SimpleNamespace(value=""))
sys.modules[common.__name__] = common
i18n = types.ModuleType(PACKAGE + ".i18n")
i18n._ = lambda text: text
sys.modules[i18n.__name__] = i18n

enigma = types.ModuleType("enigma")
enigma.eServiceReference = lambda value: value
enigma.eServiceCenter = object
components = types.ModuleType("Components")
components.__path__ = []
config_module = types.ModuleType("Components.config")
config_module.config = types.SimpleNamespace(OpenWebif=types.SimpleNamespace(
	port=types.SimpleNamespace(value=8080), streamport=types.SimpleNamespace(value=8002)))
with patch.dict(sys.modules, {"enigma": enigma, "Components": components, "Components.config": config_module}):
	clients = importlib.import_module(PACKAGE + ".clients")
	mdns = importlib.import_module(PACKAGE + ".mdns")
	channelparams = importlib.import_module(PACKAGE + ".channelparams")
	store = importlib.import_module(PACKAGE + ".channelparamsstore")
for module in (clients, mdns, channelparams, store):
	sys.modules[module.__name__] = module


class ClientMigrationTests(unittest.TestCase):
	def setUp(self):
		self.directory = tempfile.TemporaryDirectory()
		self.addCleanup(self.directory.cleanup)
		self.patchers = [
			patch.object(clients, "DIR", self.directory.name),
			patch.object(clients, "COMMON", os.path.join(self.directory.name, "common.json")),
			patch.object(mdns, "SERVICE_FILE", os.path.join(self.directory.name, "epgscan.service")),
		]
		for patcher in self.patchers:
			patcher.start()
			self.addCleanup(patcher.stop)

	def write(self, name, data):
		with open(os.path.join(self.directory.name, name), "w", encoding="utf-8") as file:
			json.dump(data, file)

	def read(self, name):
		with open(os.path.join(self.directory.name, name), encoding="utf-8") as file:
			return json.load(file)

	def test_start_cleans_all_old_profiles_once_and_preserves_common(self):
		original = {"data": {"pmts_10": {"v": "old"}, "audio_x": {"v": "42|pol|aac"}},
			"device": {"name": "TV"}}
		self.write("device-one.json", original)
		self.write("device-two.json", {"data": {"pmts_11": {"v": "old"}, "engine": {"v": "exo"}}})
		self.write("device-clean.json", {"data": {"audio_x": {"v": "42|pol|aac"}}})
		self.write("common.json", {"data": {"pmts_10": {"v": "shared"}}})
		with patch.object(clients, "_listen"):
			clients.start()
			self.assertEqual(self.read("device-one.json")["data"], {"audio_x": {"v": "42|pol|aac"}})
			self.assertEqual(self.read("device-one.json")["device"], original["device"])
			self.assertEqual(self.read("device-two.json")["data"], {"engine": {"v": "exo"}})
			self.assertEqual(self.read("common.json")["data"]["pmts_10"]["v"], "shared")
			backups = sorted(name for name in os.listdir(self.directory.name) if ".bak-" in name)
			self.assertEqual(len(backups), 2)
			self.assertEqual(self.read(next(name for name in backups if name.startswith("device-one"))), original)
			clients.start()
			self.assertEqual(backups, sorted(name for name in os.listdir(self.directory.name) if ".bak-" in name))

	def test_push_removes_legacy_client_pmts_but_keeps_new_shared_pmt(self):
		original = {"data": {"pmts_10": {"v": "old", "t": 1}, "audio_x": {"v": "old", "t": 1}}}
		self.write("device-tv.json", original)
		self.write("common.json", {"data": {"pmts_10": {"v": "shared", "t": 2}}})
		body = {"device": {"device": {"name": "TV"}, "data": {
			"pmts_10": {"v": "new", "t": 3}, "audio_x": {"v": "new", "t": 4},
			"discoverySubnet": {"v": "10.1.2.0/24", "t": 4}}},
			"common_add": {"pmts_11": {"v": "extra", "t": 5}}}
		result = clients.push("tv", body)
		self.assertNotIn("pmts_10", result["device"]["data"])
		self.assertEqual(self.read("device-tv.json")["data"]["audio_x"]["v"], "new")
		self.assertEqual(self.read("common.json")["data"]["pmts_10"]["v"], "new")
		self.assertEqual(self.read("common.json")["data"]["pmts_11"]["v"], "extra")
		self.assertNotIn("discoverySubnet", self.read("common.json")["data"])
		backup = next(name for name in os.listdir(self.directory.name) if name.startswith("device-tv.json.bak-"))
		self.assertEqual(self.read(backup), original)

	def test_failed_backup_aborts_push_without_losing_profile_or_common(self):
		original = {"data": {"pmts_10": {"v": "old"}, "audio_x": {"v": "keep"}}}
		self.write("device-tv.json", original)
		self.write("common.json", {"data": {"pmts_10": {"v": "shared"}}})
		with patch.object(store, "_backup", side_effect=OSError("disk full")):
			with self.assertRaisesRegex(OSError, "disk full"):
				clients.push("tv", {"device": {"data": {"pmts_10": {"v": "new"}}}})
		self.assertEqual(self.read("device-tv.json"), original)
		self.assertEqual(self.read("common.json")["data"]["pmts_10"]["v"], "shared")

	def test_save_failure_is_reported_and_leaves_existing_profile_intact(self):
		original = {"data": {"audio_x": {"v": "old"}}}
		self.write("device-tv.json", original)
		with patch.object(clients.os, "replace", side_effect=OSError("disk full")):
			with self.assertRaisesRegex(OSError, "disk full"):
				clients.push("tv", {"device": {"data": {"audio_x": {"v": "new", "t": 3}}}})
		self.assertEqual(self.read("device-tv.json"), original)
		self.assertFalse(os.path.exists(os.path.join(self.directory.name, "device-tv.json.tmp")))

	def test_push_rejects_corrupt_profile_instead_of_overwriting_it(self):
		path = os.path.join(self.directory.name, "device-tv.json")
		with open(path, "w", encoding="utf-8") as file:
			file.write("{broken")
		with self.assertRaises(ValueError):
			clients.push("tv", {"device": {"data": {"audio_x": {"v": "new"}}}})
		with open(path, encoding="utf-8") as file:
			self.assertEqual(file.read(), "{broken")
		self.assertFalse(os.path.exists(clients.COMMON))

	def test_api_lifecycle_publishes_and_unpublishes_service(self):
		server = types.SimpleNamespace(stopListening=lambda: "stopped")
		internet = types.ModuleType("twisted.internet")
		internet.reactor = types.SimpleNamespace(listenTCP=lambda port, _site: server)
		webserver = types.ModuleType("twisted.web.server")
		webserver.Site = lambda resource: resource
		with patch.dict(sys.modules, {"twisted.internet": internet, "twisted.web.server": webserver}), \
				patch.object(clients, "_resource", return_value=object()), \
				patch.object(mdns, "publish", return_value="mDNS: Avahi dziala") as publish, \
				patch.object(mdns, "unpublish") as unpublish, \
				patch.object(mdns, "status", return_value="mDNS: Avahi dziala"):
			clients._listen()
			publish.assert_called_once_with(8770)
			self.assertIn("mDNS: Avahi dziala", clients.summary())
			self.assertEqual(clients.stop(), "stopped")
			unpublish.assert_called_once()

	def test_failed_mdns_publication_does_not_stop_api_and_is_visible(self):
		server = types.SimpleNamespace(stopListening=lambda: None)
		internet = types.ModuleType("twisted.internet")
		internet.reactor = types.SimpleNamespace(listenTCP=lambda port, _site: server)
		webserver = types.ModuleType("twisted.web.server")
		webserver.Site = lambda resource: resource
		with patch.dict(sys.modules, {"twisted.internet": internet, "twisted.web.server": webserver}), \
				patch.object(clients, "_resource", return_value=object()), \
				patch.object(mdns, "publish", side_effect=OSError("read-only filesystem")), \
				patch.object(mdns, "unpublish"):
			clients._listen()
			self.assertIs(clients._server, server)
			self.assertIn("read-only filesystem", clients.summary())
			clients.stop()


class MdnsTests(unittest.TestCase):
	def setUp(self):
		self.directory = tempfile.TemporaryDirectory()
		self.addCleanup(self.directory.cleanup)
		self.path = os.path.join(self.directory.name, "services", "epgscan.service")
		patcher = patch.object(mdns, "SERVICE_FILE", self.path)
		patcher.start()
		self.addCleanup(patcher.stop)
		mdns.config.OpenWebif.port.value = 8080
		mdns.config.OpenWebif.streamport.value = 8002

	def test_publish_xml_and_update_ports_then_unpublish(self):
		with patch.object(mdns, "_daemon_status", return_value="mDNS: Avahi dziala"):
			self.assertEqual(mdns.publish(8770), "mDNS: Avahi dziala")
			root = ET.parse(self.path).getroot()
			self.assertEqual(root.findtext("./service/type"), "_epgscan._tcp")
			self.assertEqual(root.findtext("./service/port"), "8770")
			self.assertEqual([item.text for item in root.findall("./service/txt-record")],
				["api=1", "webif-port=8080", "stream-port=8002"])
			mdns.config.OpenWebif.port.value = 8181
			mdns.publish(9000)
			root = ET.parse(self.path).getroot()
			self.assertEqual(root.findtext("./service/port"), "9000")
			self.assertIn("webif-port=8181", [item.text for item in root.findall("./service/txt-record")])
		mdns.unpublish()
		self.assertFalse(os.path.exists(self.path))
		self.assertEqual(mdns.status(), "mDNS: nieaktywne")

	def test_foreign_service_is_neither_overwritten_nor_removed(self):
		os.makedirs(os.path.dirname(self.path))
		with open(self.path, "w", encoding="utf-8") as file:
			file.write("foreign service")
		with self.assertRaisesRegex(ValueError, "nie nalezy"):
			mdns.publish(8770)
		mdns.unpublish()
		with open(self.path, encoding="utf-8") as file:
			self.assertEqual(file.read(), "foreign service")

	def test_missing_or_stopped_daemon_is_reported(self):
		with patch.object(mdns.shutil, "which", return_value=None), \
				patch.object(mdns.os.path, "isfile", return_value=False):
			self.assertEqual(mdns._daemon_status(), "mDNS: brak Avahi")
		with patch.object(mdns.shutil, "which", return_value="/usr/sbin/avahi-daemon"), \
				patch.object(mdns.subprocess, "run", return_value=types.SimpleNamespace(returncode=1)):
			self.assertEqual(mdns._daemon_status(), "mDNS: Avahi nie dziala")

	def test_write_error_preserves_old_service_and_is_reported(self):
		with patch.object(mdns, "_daemon_status", return_value="mDNS: Avahi dziala"):
			mdns.publish(8770)
		with open(self.path, "rb") as file:
			old = file.read()
		with patch.object(mdns.os, "replace", side_effect=OSError("disk full")):
			with self.assertRaisesRegex(OSError, "disk full"):
				mdns.publish(9000)
		with open(self.path, "rb") as file:
			self.assertEqual(file.read(), old)
		self.assertFalse(os.path.exists(self.path + ".tmp"))


if __name__ == "__main__":
	unittest.main()
