#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - zapis godziny pobierania z config.env (GRAB_TIME) do timera systemd
# Uzycie po zmianie GRAB_TIME:  sudo bash /opt/epgpl/timer.sh
BASE=${EPGPL_BASE:-/opt/epgpl}
T=$(sed -n 's/^GRAB_TIME=\([0-9:,]*\).*/\1/p' "$BASE/config.env")
T=${T:-01:15}
cat > /etc/systemd/system/epgpl.timer <<TIMER
[Unit]
Description=epgpl - codzienne pobieranie programu ($T)

[Timer]
OnCalendar=*-*-* $T
Unit=epgpl-auto.service
Persistent=true

[Install]
WantedBy=timers.target
TIMER
systemctl daemon-reload
systemctl enable epgpl.timer >/dev/null 2>&1
systemctl restart epgpl.timer
echo "Pobieranie ustawione na: $T"
