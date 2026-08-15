# hoer.jetzt — Befehle

Spickzettel. Ausführliche Erklärungen stehen in [ANLEITUNG.md](ANLEITUNG.md).

Es gibt zwei Sorten Host, und fast jeder Unterschied kommt daher:

| | Verzeichnis | Was drauf ist |
| --- | --- | --- |
| **Stack-Host** | `/opt/hoerjetzt/` | Bot, Weboberfläche, AI-Radio, ein Audio-Knoten |
| **Knoten-Host** | `/opt/hoerjetzt-node/` | nur ein Audio-Knoten |

---

## Dieses Release ausrollen — v2026.08.15.5

**Achtung, dieses Release wechselt die Datenbank.** MariaDB wird zu PostgreSQL,
und Redis kommt dazu. Vor dem Start einmal umziehen:

```bash
ALT_DB_HOST=<alte-adresse> ALT_DB_USER=<nutzer> ALT_DB_PASSWORD=<passwort> \
    bash /opt/hoerjetzt/main/deploy/umzug-postgres.sh
```

Das Skript sichert, legt das Schema an, überträgt per `pgloader` und zählt
gegen. Bricht etwas ab, läuft der alte Stand unverändert weiter — es gibt
keinen Punkt, an dem beide Datenbanken kaputt wären. Erst wenn alle Zahlen
stimmen, umschalten.

Der Bot läuft ab jetzt als **Shard-Verbund**. Mit einer Node und ohne Angabe
ändert sich nichts: er fragt Discord nach der empfohlenen Zahl, meist eine.

---

## Frühere Releases — v2026.08.15.3

**Wichtig:** dieses Release repariert einen Fehler in v2026.08.15.1/.2, der
noch nie ausgerollt war — Embed-Vorlagen, Einladungslink, Entzugsrollen und die
Kanalnummer wurden gespeichert, aber beim Lesen ignoriert und waren nach einem
Neustart weg. Wer .1 oder .2 nicht ausgerollt hat, merkt davon nichts.

Neu darin: **Titel setzen sich nicht mehr zurück** — nach einem Abriss der
Sprachverbindung lief der Titel von vorne los; **Lavalink-Verbindung**
belastbarer (Resuming 180 s statt 60, wird bei jeder Anmeldung neu gesetzt,
`trackStuckThresholdMs` 30 s statt 10). Aus dem vorigen Stand: **Nachrichten-Vorlagen** — ein voller Embed-Editor für Willkommen,
Verify, Reaction-Roles und Tickets, wahlweise als gemeinsame Vorlage;
**Einladungslink** `hoer.jetzt/invite/deinname`; Join-to-Create vergibt die
**kleinste freie Nummer** und kennt mehr Platzhalter; **Rollen entziehen** bei
Verify und Reaction-Roles; die **Serverauswahl überlebt F5**; der
**Ticket-Fehler** (`complete() in callback threads`) ist behoben.
Aus dem vorigen Stand: die **Sitzungsnachricht** in Discord ist neu geordnet;
**Überlauf** — sind alle Standard-Knoten voll, weichen
Standard-Server auf Premium aus und kommen von selbst zurück; **Neu
verbinden** je Knoten im Adminbereich; die **Knotenwache** — neue oder
geänderte Audio-Knoten kommen ohne Neustart des Bots dazu; der Cipher-Dienst
`yt-cipher` (gegen `must find sig function`, das alle Titel betraf);
YouTube-Anmeldung auch auf reinen Knoten-Hosts; und das Suchfeld im Webplayer
wird nicht mehr alle fünf Sekunden geleert.

**1 — Bundle nach GitHub** (auf dem Rechner mit Schreibzugang):

```bash
bash /tmp/hochladen.sh /tmp/hoerjetzt-v2026.08.15.3.bundle
```

**2 — Stack-Host.** Der Cipher-Dienst kommt dabei neu dazu und wird mitgebaut:

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --jetzt
docker compose -f /opt/hoerjetzt/main/deploy/docker/docker-compose.yml ps
```

**3 — Jeder Knoten-Host** einzeln:

```bash
cd /opt/hoerjetzt-node
git fetch origin lavalink && git reset --hard origin/lavalink
bash update.sh
```

`update.sh` übernimmt alle bisherigen Einstellungen und erneuert einen
vorhandenen Cipher-Dienst mit. Es fragt **nicht** nach der YouTube-Anmeldung —
die gibt es nur über `install.sh`. Wer sie auf einem Knoten nachrüsten will:

```bash
cd /opt/hoerjetzt-node && bash install.sh
```

**4 — Nachsehen, ob beides greift:**

```bash
docker logs hoerjetzt-lavalink-free-1-1 | grep -E "Cipher|Plugin|YouTube"
docker logs --tail 20 hoerjetzt-yt-cipher-1
```

Erwartet wird eine Zeile `Cipher:  http://…` — steht dort „im Plugin", ist
`YT_CIPHER_URL` leer und es rechnet wieder der alte Weg.

---

## Aktualisieren

**Stack-Host** — holt das neueste Release, baut, startet:

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --jetzt
```

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --pruefen   # nur nachsehen
bash /opt/hoerjetzt/main/deploy/auto-update.sh             # wartet auf Ruhe
tail -50 /var/log/hoerjetzt-update.log
systemctl list-timers hoerjetzt-update.timer
```

`git pull` funktioniert hier **nicht** — Releases werden force-gepusht. Immer
`fetch` + `reset --hard`, und genau das machen die Skripte.

**Knoten-Host:**

```bash
cd /opt/hoerjetzt-node
git fetch origin lavalink && git reset --hard origin/lavalink
bash update.sh              # alle Knoten auf diesem Host
bash update.sh 2            # nur hoerjetzt-lavalink-2
```

Fragt nichts neu ab — Passwort, Stufe, Port und Qualität kommen aus dem
laufenden Container.

**Knoten aus `docker-compose.nodes.yml`:**

```bash
cd /opt/hoerjetzt/main/deploy/docker
docker compose -f docker-compose.nodes.yml up -d --build
```

**Nachtlauf einrichten** (nur Stack-Host):

```bash
install -m 0644 /opt/hoerjetzt/main/deploy/systemd/hoerjetzt-update.* /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now hoerjetzt-update.timer
```

---

## Neu aufsetzen

**Stack-Host, leere Maschine:**

```bash
apt update && apt install -y git curl
git clone -b main https://github.com/MarcoEckerlin/hoer.jetzt.git /opt/hoerjetzt/main
cd /opt/hoerjetzt/main && bash install.sh
```

**Knoten-Host:**

```bash
git clone -b lavalink https://github.com/MarcoEckerlin/hoer.jetzt.git /opt/hoerjetzt-node
cd /opt/hoerjetzt-node && bash install.sh
```

**Von einer alten JAR-Installation umsteigen** (stoppt die alten Dienste, sichert
die Datenbank, übernimmt die alte `config.json`):

```bash
cd /opt/hoerjetzt/main && bash deploy-alles.sh
```

---

## Einstellungen ändern

Alles über `/opt/hoerjetzt/.env`, danach neu starten. Beispiele:

```bash
nano /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d                                    # ohne Tailscale
docker compose -f docker-compose.tailscale.yml up -d     # mit Tailscale
```

| Variable | Wofür |
| --- | --- |
| `HJ_WEB_PORT_HOST` | Host-Port der Weboberfläche, wenn 8080 belegt ist |
| `HJ_WEB_BIND` | Lauschadresse — `127.0.0.1` nur hinter einem Proxy auf demselben Host |
| `LAVALINK_QUALITAET` | `hoch`, `mittel`, `sparsam` |
| `YOUTUBE_OAUTH` | `true` für altersbeschränkte Titel |
| `YOUTUBE_REFRESH_TOKEN` | nach der einmaligen Bestätigung |
| `YT_CIPHER_URL` | Cipher-Dienst; leer setzen schaltet ihn ab |
| `YT_CIPHER_PASSWORD` | dessen `API_TOKEN`, muss auf beiden Seiten gleich sein |
| `YOUTUBE_PLUGIN_SNAPSHOT` | `true` = Entwicklungsstand des YouTube-Plugins |
| `TS_AUTHKEY` | gesetzt = Tailscale-Variante wird genommen |
| `HJ_LAVALINK_WATCH_SECONDS` | Takt der Knotenwache; `0` = aus |
| `HJ_LAVALINK_FREE_OVERFLOW` | `false` = Standard darf nie auf Premium |
| `HJ_LAVALINK_OVERFLOW_CPU` | ab welcher Last ein Knoten voll ist (`0.85`) |
| `HJ_LAVALINK_PREMIUM_RESERVE` | freigehaltene Plätze je Premium-Knoten (`1`) |
| `NODE2_TIER` … `NODE4_TIER` | Stufe der Zusatzknoten |
| `REPO` | SSH-Adresse, wenn das Repository privat ist |

**Einzelnen Dienst neu starten:**

```bash
cd /opt/hoerjetzt/main/deploy/docker
docker compose up -d core
docker compose up -d lavalink-free-1
```

---

## Audio-Knoten

**Weitere Knoten auf dem Stack-Host** (Ports 2334–2336):

```bash
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose -f docker-compose.nodes.yml up -d node-2 node-3 node-4
```

Diese Knoten hängen im Netz des Hauptstacks — nur so erreichen sie den
Cipher-Dienst. Der Hauptstack muss also laufen. Heißt das Netz anders,
`HJ_NETZ=` in der `.env` setzen (`docker network ls` zeigt den Namen).

**Weiterer Knoten auf einem eigenen Host** — `install.sh` fragt nach einer
Nummer, daraus werden Containername und Port:

```bash
cd /opt/hoerjetzt-node && bash install.sh
```

Danach **im Adminbereich unter *Audio-Knoten* eintragen** — ohne diesen Eintrag
existiert der Knoten für den Bot nicht. Name muss eindeutig sein.

**Der Bot muss dafür nicht neu gestartet werden.** Er liest die Knotentabelle
alle 30 Sekunden nach; Speichern im Adminbereich löst den Abgleich sofort aus,
und der Knopf *Knoten jetzt einlesen* ebenso. Das gilt auch für geänderte
Adressen und Passwörter, nicht nur für neue Einträge.

```bash
# Takt ändern oder abschalten
echo 'HJ_LAVALINK_WATCH_SECONDS=30' >> /opt/hoerjetzt/.env   # 0 = aus
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d core

# Nachsehen, was übernommen wurde
docker logs hoerjetzt-core-1 | grep LAVALINK | tail -20
```

**Premium vergeben:** Adminbereich → *Audio-Knoten* → Stufe `Premium`, dann
→ *Server* → *Premium-Audio* einschalten.

```bash
# Server auf die passende Stufe zurückziehen (auch als Knopf im Panel)
curl -s -X POST http://127.0.0.1:8080/api/admin/actions/rebalance-audio

# Einen Knoten trennen und sofort neu verbinden (Knopf: "Neu verbinden")
curl -s -X POST http://127.0.0.1:8080/api/admin/audio/nodes/premium-1/reconnect
```

Das ist **kein** Neustart des Lavalink-Dienstes. Der läuft auf dem Host:

```bash
docker restart hoerjetzt-lavalink-1          # Knoten-Host
docker compose restart lavalink-free-1       # im Stack
```

**Überlauf:** Sind alle Standard-Knoten voll — Obergrenze erreicht oder
Systemlast über 85 % — weichen Standard-Server auf Premium aus und ziehen von
selbst zurück, sobald wieder Platz ist. Im Panel steht bei ihnen *Überlauf*.

```bash
# Abschalten: Premium bleibt unter allen Umständen leer
echo 'HJ_LAVALINK_FREE_OVERFLOW=false' >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d core

docker logs hoerjetzt-core-1 | grep "weicht auf einen Premium"
```

---

## YouTube spielt gar nichts mehr

Erst nachsehen, welcher der beiden Fehler es ist:

```bash
docker logs --tail 200 hoerjetzt-lavalink-free-1-1 | grep -E "sig function|requires login"
```

**`must find sig function`** — betrifft alle Titel. Das Plugin kommt mit
YouTubes aktuellem Player-Skript nicht mehr klar. Der Cipher-Dienst läuft seit
diesem Release im Stack mit; falls er fehlt oder alt ist:

```bash
cd /opt/hoerjetzt/main/deploy/docker
docker compose pull yt-cipher && docker compose up -d yt-cipher lavalink-free-1
docker logs hoerjetzt-yt-cipher-1 --tail 20
```

Auf einem Knoten-Host macht das `update.sh` mit.

Hilft das nicht, den Entwicklungsstand des Plugins nehmen — Hash von
`https://maven.lavalink.dev/snapshots/dev/lavalink/youtube/youtube-plugin/`:

```bash
cat >> /opt/hoerjetzt/.env <<'EOF'
YOUTUBE_PLUGIN_SNAPSHOT=true
YOUTUBE_PLUGIN_VERSION=<Commit-Hash>
EOF
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d lavalink-free-1
```

**`This video requires login`** — betrifft nur 18+. Weiter im nächsten
Abschnitt.

---

## YouTube: altersbeschränkte Titel

```bash
echo 'YOUTUBE_OAUTH=true' >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d lavalink-free-1
docker logs -f hoerjetzt-lavalink-free-1-1     # Gerätecode erscheint
```

Code bestätigen, dann den `refreshToken` aus dem Log übernehmen:

```bash
echo 'YOUTUBE_REFRESH_TOKEN=<token>' >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d lavalink-free-1
```

Nimm ein Wegwerf-Konto. Prüfen, ob es greift:

```bash
docker logs hoerjetzt-lavalink-free-1-1 | grep -E "initialised with clients|OAuth"
```

Dort muss `TV` unter den Clients stehen — nur der kann 18+ mit Anmeldung.

Kommt `This video requires login` **trotz** `access token refreshed
successfully`, liegt es nicht mehr am Bot: Google verlangt in der EU für 18+
eine echte Altersverifikation (Ausweis oder Kreditkarte). Ein Wegwerf-Konto mit
bloßem Geburtsdatum gilt als minderjährig. Manche Titel sind zusätzlich
regionsgesperrt — dagegen hilft gar kein Konto. Der Bot weicht dann auf
SoundCloud aus.

**Knoten-Host** — dort wird die Anmeldung seit diesem Release mit abgefragt:

```bash
cd /opt/hoerjetzt-node && bash install.sh
docker logs -f hoerjetzt-lavalink-1 | grep -iE 'oauth|refresh'
```

Denselben Refresh-Token können alle Knoten benutzen — `install.sh` fragt danach.

---

## Privates Repository

Auf **jedem** Host einmal, jeweils mit eigenem Schlüssel:

```bash
# Stack-Host
bash /opt/hoerjetzt/main/deploy/zugang-einrichten.sh

# Knoten-Host
bash /opt/hoerjetzt-node/zugang-einrichten.sh
```

Den ausgegebenen öffentlichen Schlüssel unter *Settings → Deploy keys*
eintragen, **ohne** Schreibrecht.

---

## Nachsehen, wenn etwas klemmt

```bash
cd /opt/hoerjetzt/main/deploy/docker && docker compose ps
docker logs --tail 80 hoerjetzt-core-1
docker logs --tail 80 hoerjetzt-lavalink-free-1-1
docker logs --tail 40 hoerjetzt-tailscale
```

| Symptom | Erster Griff |
| --- | --- |
| Login wirft auf die Startseite zurück | `docker logs hoerjetzt-core-1 \| grep LOGIN` |
| Konfiguration kommt nicht an | `docker logs hoerjetzt-core-1 \| grep "\[Config\]"` |
| Knoten fehlt im Bot | `docker logs hoerjetzt-core-1 \| grep "angemeldet"` |
| Plugin lädt nicht | `docker exec hoerjetzt-lavalink-free-1-1 getent hosts maven.lavalink.dev` |
| YouTube spielt gar nichts | `docker logs hoerjetzt-lavalink-free-1-1 \| grep "sig function"` |
| Cipher-Dienst | `docker logs --tail 30 hoerjetzt-yt-cipher-1` |
| Discord nicht erreichbar | `docker exec hoerjetzt-core-1 getent hosts discord.com` |
| Tailscale-Adresse | `docker exec hoerjetzt-tailscale tailscale ip -4` |

**Datenbank sichern:**

```bash
mariadb-dump --single-transaction --skip-ssl -h <host> -u <user> -p <db> \
    | gzip > /var/backups/hoerjetzt-$(date +%F).sql.gz
```

**Aufräumen, wenn die Platte voll läuft:**

```bash
docker image prune -f
docker system df
```

---

## Release veröffentlichen

Nur auf dem Rechner mit Schreibzugang:

```bash
VERSION=2026.08.20 bash branches-anlegen.sh
git push -f origin main core ai-radio lavalink
git push -f --tags origin
```

Ohne den zweiten Push sieht das Auto-Update nichts — es richtet sich nach Tags,
nicht nach den Zweigspitzen.
