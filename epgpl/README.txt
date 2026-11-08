epgpl 0.20.0 - polski program TV (teleman.pl, onet.pl, pilot.wp.pl, programtv.naziemna.info) w formacie XMLTV dla EPG Scan

Instalacja / aktualizacja na serwerze (Debian / Ubuntu):
  cd /tmp && rm -rf epgpl && unzip -o epgpl-0.20.0.zip && cd epgpl && sudo bash install.sh
  Instalator sprawdza kolejno: system, pakiety, Node.js 22, uzytkownika epgpl, grabber, konfiguracje, uslugi systemd,
  regule sudo, zapore (ufw / firewalld), Avahi. Co jest spelnione - [OK]; co trzeba zmienic - pyta (T/n).
  Przy pierwszej instalacji pyta o adres dekodera (sprawdza, czy OpenWebif odpowiada), port i godzine pobierania.
  Bez pytan: sudo bash install.sh -y --dekoder 192.168.1.20      Opcje: --bez-avahi  --bez-zapory

Aktualizacja z GitHub (ustawienia zostaja):  sudo bash /opt/epgpl/update.sh   (automatycznie: AUTOUPDATE=1 w /opt/epgpl/config.env)
Zmiana ustawien:  sudo bash /opt/epgpl/configure.sh   (--podstawowe: dekoder, port, godzina, adres;  --pobieranie: kolejnosc serwisow, opoznienie, polaczenia, dni)
Usuniecie razem z konfiguracja (kopia ustawien w /root):  sudo bash /opt/epgpl/uninstall.sh
  (dla instalacji sprzed 0.13.0: sudo bash uninstall.sh z rozpakowanej paczki)
Pobranie teraz:  sudo systemctl start --no-block epgpl.service; tail -f /opt/epgpl/work/grab.log

Pliki:
  /opt/epgpl/config.env    ustawienia (adres dekodera, zrodla SITES, liczba dni DAYS, zakres)
  /opt/epgpl/aliases.txt   Twoje dopasowania nazw kanalow (aliases.dist - dostarczane z pakietem)
  /opt/epgpl/log/          logi kolejnych uruchomien
  http://SERWER:8088/raport.txt   ktore kanaly maja program i z ktorego serwisu, ktorych brakuje

Odbiornik - najprosciej (EPG Scan 2.3.1+): Ustawienia -> Program z internetu -> zolty (zrodla XMLTV) -> niebieski ->
  "Szukaj serwera epgpl w sieci (Avahi i podsiec)" -> wybierz serwer. Instalator oglasza serwer przez Avahi (_epgpl._tcp);
  bez Avahi (AVAHI=0 sudo bash install.sh) EPG Scan znajdzie go przeszukujac podsiec odbiornika (port 8088).

Odbiornik recznie (jednorazowo, wymaga EPG Scan 1.8.2 lub nowszego):
  mkdir -p /etc/enigma2/epgscan_sources/wlasne
  wget -O /etc/enigma2/epgscan_sources/wlasne/epgpl.sources.xml http://SERWER:8088/epgpl.sources.xml
  potem w EPG Scan: Ustawienia -> zrodla XMLTV (zolty) -> zaznacz "EPG PL (epgpl)" -> Zapisz
  (alternatywnie w oknie zrodel: niebieski "Dodaj z adresu" i adres powyzej)

Porownanie zrodel (onet / teleman / WP Pilot) na tych samych kanalach, z EPG z satelity jako wzorcem:
  sudo -u epgpl python3 /opt/epgpl/tools/porownaj.py --kanalow 12 --dni 2

Kontrola kopii kanalow (ta sama nazwa, rozne referencje) - czy sa w mapie i maja EPG na odbiorniku:
  sudo -u epgpl python3 /opt/epgpl/tools/kopie.py            # tylko kanaly z problemem
  sudo -u epgpl python3 /opt/epgpl/tools/kopie.py --wszystkie

Bukiety do dopasowania (EPG Scan 2.3.10+): Dopasowanie kanalow -> niebieski "Lista" (wszystkie / wybrane / do skanowania).
Wybor trafia na serwer (/opt/epgpl/work/bukiety.json) i mapa jest od razu przeliczana - np. bez bukietow IPTV.

Dopasowanie kanalow z tunera (EPG Scan 1.10.0+): okno zrodel XMLTV -> "EPG PL (epgpl)" -> niebieski -> Dopasowanie kanalow.
Wskazania zapisywane sa w /opt/epgpl/wskazania.txt (mozna tez edytowac recznie).
