# hoer.jetzt — Befehle

Spickzettel. Ausführliche Erklärungen stehen in [ANLEITUNG.md](ANLEITUNG.md).

Es gibt zwei Sorten Host, und fast jeder Unterschied kommt daher:

| | Verzeichnis | Was drauf ist |
| --- | --- | --- |
| **Stack-Host** | `/opt/hoerjetzt/` | Bot, Weboberfläche, AI-Radio, ein Audio-Knoten |
| **Knoten-Host** | `/opt/hoerjetzt-node/` | nur ein Audio-Knoten |

---

## Dieses Release ausrollen — v2026.08.16.12

**Knoten melden sich selbst an, und der Bot kann sie neu starten.** Bisher
endete `install.sh` mit einem Zettel, den man von Hand in den Adminbereich
übertrug — wer das vergaß, hatte einen laufenden Lavalink, den der Bot nicht
kannte, ohne jede Fehlermeldung.

**Vor dem Ausrollen** zwei Geheimnisse in `/opt/hoerjetzt/.env` anlegen. Ohne
sie bleibt alles beim Alten, es geht nichts kaputt:

```bash
echo "HJ_NODE_TOKEN=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)"  >> /opt/hoerjetzt/.env
echo "HJ_AGENT_TOKEN=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)" >> /opt/hoerjetzt/.env
```

`HJ_NODE_TOKEN` ist der Weg hin (jeder Knoten kennt es), `HJ_AGENT_TOKEN` der
Weg zurück. Getrennt, damit ein abgegriffenes Anmeldetoken nicht ausreicht, um
auf allen Hosts Container neu zu starten. **Ohne gesetztes `HJ_NODE_TOKEN` ist
der Anmeldeendpunkt geschlossen, nicht offen.**

Autoscaling ist zusätzlich, freiwillig und ohne Token aus:

```bash
cat >> /opt/hoerjetzt/.env <<'EOF'
HJ_HETZNER_TOKEN=<Read-&-Write-Token aus der Cloud Console>
HJ_AUTOSCALE=true
HJ_AUTOSCALE_TYPE=cx33
HJ_AUTOSCALE_LOCATION=hel1
HJ_AUTOSCALE_MAX=4
EOF
```

Dann wie immer:

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --jetzt
```

Auf jedem **Knoten-Host** einmal `update.sh` — es richtet den Agenten mit ein
und fragt nichts erneut ab:

```bash
cd /opt/hoerjetzt-node
git fetch origin lavalink && git reset --hard origin/lavalink
bash update.sh
```

Nachsehen:

```bash
systemctl status hoerjetzt-knoten-agent --no-pager
docker logs hoerjetzt-core-1 | grep "hat sich angemeldet"
```

Weiter darin: **Knotenansicht neu** (Kennzahlen oben, je Knoten eine Zeile,
Erreichbarkeit zum Loadbalancer und zu allen Agenten, Container-Neustart und
Aktualisierung je Knoten); **Knoten anlegen** im Webinterface mit zweitem
Faktor (TOTP — das Autoscaling läuft bewusst ohne, es muss auch nachts
reagieren); **Knotenname ist der Rechnername**, nicht mehr `free-1`; Server-
Symbole und Betrieb-Zugang durchgängig; **Hell/Dunkel gemessen** statt
geschätzt — drei Stellen lagen im hellen Modus unter der Lesbarkeitsgrenze.

---

## Frühere Releases — v2026.08.16.11

**Der Adminbereich war unter PostgreSQL für niemanden erreichbar.** Die
Admin-Liste wurde mit `FIELD(role,…)` sortiert — das gibt es nur in MariaDB.
Unter PostgreSQL scheiterte die Abfrage, der Fehler wurde nur als WARN
geloggt, und die Liste blieb leer: kein Bot-Admin, `/admin` antwortete mit
403. Auch `HJ_BOT_ADMIN_IDS` half nicht — die Notfalltür trägt den Eintrag
zwar ein, gelesen wurde er über genau diese Abfrage.

Dieselbe Ursache steckte an vier weiteren Stellen, die den Umzug von MariaDB
überlebt hatten: `INSERT IGNORE` (Altimport der Admins, Rechtematrix) sowie
`ON DUPLICATE KEY UPDATE`, `UNIX_TIMESTAMP()` und `NOW() - INTERVAL ? SECOND`
im Wiedergabezustand. Praktisch hieß das: die Rechtematrix ließ sich nicht
speichern, und ein Update riss die Wiedergabe ab, statt sie fortzusetzen —
beides stumm, weil diese Schreibvorgänge ihre Fehler verschlucken.

Dazu ein Nebenfund: PostgreSQL kennt kein `ON UPDATE current_timestamp`.
`updated_at` wird beim Upsert jetzt ausdrücklich gesetzt, sonst hätte
`loadRecent()` den Schnappschuss nach Ablauf des Fensters nie wieder gefunden.

Nur der Zweig `core` ändert sich — keine Schemaänderung, kein Umzug:

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --jetzt
docker logs hoerjetzt-core-1 | grep -i "Bot-Admins konnten nicht geladen"
```

Die zweite Zeile muss **leer** bleiben. Danach am Webdashboard anmelden — der
Betriebsbereich unter `#/betrieb/…` ist wieder sichtbar.

---

## Frühere Releases — v2026.08.15.5

**Dieses Release wechselte die Datenbank** von MariaDB auf PostgreSQL, und
Redis kam dazu. Das Umzugsskript ist entfallen — es hat seinen Zweck erfüllt,
und eine Anleitung für einen Weg, den niemand mehr geht, ist nur eine
Fehlerquelle mehr.

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

## Zweite Node dazunehmen

Die Reihenfolge zählt. Erst das Netz, dann die Datenbanken, dann der Bot.

**1 — Hetzner Private Network** in der Konsole anlegen und beide Server
hineinhängen. Jede Maschine bekommt eine `10.x`-Adresse; die kommt in die
`.env` als `HJ_PRIVAT_IP`. Nach außen bleibt der Datenbank-Port zu.

**2 — Auf jeder Node** eine eigene Nummer setzen:

```bash
# Node 1
echo 'HJ_NODE_NR=1' >> /opt/hoerjetzt/.env
# Node 2
echo 'HJ_NODE_NR=2' >> /opt/hoerjetzt/.env
```

Die Nummer bestimmt den Zahlenraum der Datenbank: Node 1 vergibt 1, 1001,
2001 — Node 2 vergibt 2, 1002, 2002. **Zwei Nodes mit derselben Nummer
kollidieren beim ersten Abgleich.**

**3 — Postgres mit Spock starten**, auf beiden:

```bash
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose -f docker-compose.yml -f docker-compose.spock.yml up -d postgres
```

**4 — Koppeln.** Jeweils auf der genannten Maschine:

```bash
# Node 1
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh anlegen
# Node 2
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh anlegen
# Node 1
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh verbinden <ip-node2> 2
# Node 2
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh verbinden <ip-node1> 1
```

**Beide Richtungen sind Pflicht.** Nur eine anzulegen ergibt eine
Einbahnstraße — und die fällt erst auf, wenn Daten fehlen.

**5 — Nachmessen**, auf beiden:

```bash
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh pruefen
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh zeigen
```

Die Schreibprobe muss in **beide** Richtungen ankommen. Eine zu prüfen und
die andere anzunehmen ist der häufigste Fehler dabei.

**6 — Shards aufteilen.** Erst wenn die Replikation steht:

```bash
# Node 1
HJ_SHARDS_GESAMT=4  HJ_SHARD_VON=0  HJ_SHARD_BIS=1
# Node 2
HJ_SHARDS_GESAMT=4  HJ_SHARD_VON=2  HJ_SHARD_BIS=3
```

Die Bereiche dürfen sich **nie** überlappen — Discord erlaubt je Shard-Nummer
genau eine Verbindung und wirft beide hinaus, die sich streiten.

**Nach jedem Release, das Tabellen hinzufügt**, auf einer Node:

```bash
bash /opt/hoerjetzt/main/deploy/spock-einrichten.sh anlegen
```

Der Aufruf ist wiederholbar und nimmt neue Tabellen in den Abgleich. Ohne ihn
bleibt eine neue Tabelle auf der Node, die sie angelegt hat.

---

## Zentrale Steuerung

Ab drei Maschinen lohnt sie sich: der Controller vergibt die Shard-Nummern und
nennt ein Ziel-Release, die Agenten holen es sich ab.

**Auf der Steuer-Node** ein Token setzen — damit meldet sich jeder Agent an:

```bash
echo "HJ_CONTROLLER_TOKEN=$(openssl rand -hex 32)" >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d core
```

**Auf jeder Node** — auch auf der Steuer-Node selbst:

```bash
cat >> /opt/hoerjetzt/.env <<'EOF'
HJ_CONTROLLER_URL=http://10.0.0.2:8080
HJ_CONTROLLER_TOKEN=<dasselbe Token>
HJ_NODE_NAME=node1
EOF

install -m 0644 /opt/hoerjetzt/main/deploy/systemd/hj-agent.* /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now hj-agent.timer
```

**Nachsehen:**

```bash
systemctl list-timers hj-agent.timer
journalctl -u hj-agent -n 30
curl -s http://127.0.0.1:8080/api/verbund/nodes | python3 -m json.tool   # angemeldet als Admin
```

**Ein Release für alle setzen** — im Betriebsbereich unter *Verbund*, oder:

```bash
curl -s -X POST http://127.0.0.1:8080/api/verbund/ziel \
     -H 'Content-Type: application/json' \
     -d '{"releaseVersion":"2026.08.20","shardsGesamt":4}'
```

Die Nodes übernehmen es beim nächsten Lauf des Agenten, spätestens nach einer
Minute. Das Release zieht `auto-update.sh` nach — **das wartet auf Ruhe**, ein
Shard mitten in laufender Wiedergabe neu zu starten reißt den Ton ab.

**Was der Controller nicht tut:** eine Node ohne Rückmeldung sofort ersetzen.
Erst nach fünf Minuten Stille werden ihre Shards neu verteilt. Kürzer wäre
gefährlich — Shards umzuverteilen reißt jede laufende Wiedergabe dort ab, und
das für eine Node zu tun, die gleich wieder da ist, wäre der teuerste Weg, gar
nichts zu gewinnen.

**Fällt der Controller aus**, ändert sich nichts: die Agenten lassen alles
stehen, wie es ist. Ein Agent, der bei Funkstille anfängt umzubauen, wäre
gefährlicher als gar keiner.

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

**Leere Hetzner-Maschine — alles in einem Lauf:**

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/MarcoEckerlin/hoer.jetzt/main/deploy/hetzner-autoinstall.sh)
```

Fragt nur nach dem, was es nicht selbst herausfinden kann: Bot-Token,
Client-ID, Client-Secret, Adresse, Node-Nummer. Private Adresse, Rechnername
und Ausstattung liest es aus der Maschine. Danach laufen Docker, alle Zweige,
eine `.env` mit erzeugten Passwörtern, die Firewall und der Stack.

Bei Auswahl **2** wird daraus ein reiner Audio-Knoten statt eines Stacks.

**Von Hand, Stack-Host:**

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

---

## Einstellungen ändern

Alles über `/opt/hoerjetzt/.env`, danach neu starten. Beispiele:

```bash
nano /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d
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

## Datenbank mit einem Werkzeug ansehen

HeidiSQL, pgAdmin oder `psql` auf die Postgres eines Knotens. Zwei Wege — der
erste öffnet nichts.

### Weg 1: SSH-Tunnel (empfohlen)

HeidiSQL bringt das mit. Neue Sitzung anlegen:

| Reiter | Feld | Wert |
|---|---|---|
| Einstellungen | Netzwerktyp | **PostgreSQL (SSH tunnel)** |
| Einstellungen | Hostname | `127.0.0.1` |
| Einstellungen | Benutzer / Passwort | `HJ_DB_USER` / `HJ_DB_PASSWORD` aus der `.env` |
| Einstellungen | Datenbank | `discordbot` |
| SSH-Tunnel | SSH-Host / Port | Adresse des Knotens, `22` |
| SSH-Tunnel | Benutzer | `root` |
| SSH-Tunnel | Privater Schlüssel | dein Schlüssel (`.ppk`) |
| SSH-Tunnel | Lokaler Port | z. B. `55432` |

Damit ist nichts offen: die Verbindung läuft durch SSH, das ohnehin erreichbar
ist. Auf dem Knoten muss dafür der Port lokal veröffentlicht sein — das tut
Weg 2 mit `HJ_DB_BIND=127.0.0.1`.

Ohne HeidiSQL geht es genauso von Hand:

```bash
ssh -N -L 55432:127.0.0.1:5432 root@<knoten>
```

### Weg 2: Port veröffentlichen

In die `.env` des Knotens (`/opt/hoerjetzt/.env`):

```
HJ_DB_ZUGANG=true
HJ_DB_BIND=127.0.0.1
```

Dann:

```bash
cd /opt/hoerjetzt && bash main/deploy/auto-update.sh
```

`HJ_DB_BIND` bestimmt, wer herankommt:

| Wert | Wer erreicht die Datenbank |
|---|---|
| `127.0.0.1` | nur der Host selbst — zusammen mit dem SSH-Tunnel oben |
| `10.x.x.x` | die Maschinen im privaten Netz |
| `0.0.0.0` | **alle** |

> **`ufw` schützt hier nicht.** Docker veröffentlicht Ports direkt in iptables,
> vor den ufw-Ketten. `ufw deny 5432` meldet Erfolg und bewirkt nichts. Bei
> `0.0.0.0` steht die Datenbank im Netz — Port 5432 wird durchgehend
> abgescannt, und der einzige Schutz ist dann das Passwort.
>
> Wirksam ist nur eine Firewall **außerhalb** des Hosts: bei Hetzner die
> Cloud-Firewall, mit der Quelle auf die eigene Adresse begrenzt. Die sitzt vor
> der Maschine und weiß von Docker nichts.

Die Einstellung steht in der `.env` und nicht in der Compose-Datei: der
Deploy-Stand wird als Tarball über das Verzeichnis gepackt, eine Änderung an
`docker-compose.yml` wäre beim nächsten Aufsetzen lautlos weg.

Zentral über *Vorgaben* lässt sich das **nicht** setzen — es würde sonst alle
Knoten gleichzeitig öffnen. Der Katalog lehnt die Schlüssel ab, und der Knoten
lehnt sie ein zweites Mal ab.

## Datenbank umziehen oder zurückholen

Die Daten liegen im Postgres-Container des Knotens, nicht auf dem Host. Ein
Umzug ist deshalb Dump und Einspielen, keine Dateikopie.

**1 — Auf dem Quellknoten sichern.**

```bash
bash /opt/hoerjetzt/main/deploy/sicherung.sh --nur-lokal
ls -lh /opt/hoerjetzt/sicherungen/
```

**2 — Datei auf das Ziel bringen.**

```bash
scp /opt/hoerjetzt/sicherungen/<datei>.sql.gz root@<ziel>:/root/
```

**3 — Auf dem Ziel einspielen.** Erst nachsehen, dann übernehmen:

```bash
bash /opt/hoerjetzt/main/deploy/uebernehmen.sh --datei /root/<datei>.sql.gz --pruefen
```

```bash
bash /opt/hoerjetzt/main/deploy/uebernehmen.sh --datei /root/<datei>.sql.gz
```

Das Skript hält `core` und `web` an, sichert den jetzigen Stand, leert das
Schema und spielt ein — beides in **einer** Transaktion. Bricht das Einspielen
ab, steht die alte Datenbank unverändert da. Danach fährt es die Dienste wieder
an und zeigt die Zeilenzahlen der größten Tabellen; daran erkennt man sofort,
ob die richtige Datei drin ist.

Der Weg zurück ist derselbe Befehl mit der Datei, die es vorher angelegt hat
(`vor-uebernahme-*.sql.gz`).

> **Nicht von Hand mit `docker compose` hantieren.** Fährt ein Knoten
> Spock-Replikation, liegen die Daten im Volume `pgdaten-spock`; ohne
> `-f docker-compose.spock.yml` greift die Basisdatei auf `postgres-daten` und
> Postgres startet auf einem **leeren** Volume. Es sieht dann aus, als seien die
> Daten weg. Die Skripte nehmen das Overlay von selbst mit.

> **Nach dem Einspielen auf einem Spock-Knoten** müssen die Replikationssätze
> neu gebaut werden: `bash deploy/spock-einrichten.sh`. Sonst repliziert er
> einen Stand, den der andere Knoten nicht kennt. Das Skript weist darauf hin.

## Nachsehen, wenn etwas klemmt

```bash
cd /opt/hoerjetzt/main/deploy/docker && docker compose ps
docker logs --tail 80 hoerjetzt-core-1
docker logs --tail 80 hoerjetzt-lavalink-free-1-1
docker logs --tail 40 hoerjetzt-postgres-1
docker logs --tail 40 hoerjetzt-web-1
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
| Private Adresse | `ip -4 -o addr show \| grep -E '10\.\|172\.\|192\.168'` |
| Ports offen? | `ss -tlnp \| grep docker` |

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



---

## Neue Oberfläche, alte Oberfläche

Unter `/dashboard` liegt die neue Oberfläche. Die bisherige ist seit
v2026.08.16.5 **nicht mehr erreichbar** — sie war der Rückweg, solange die neue
noch nicht alle Module hatte. Jetzt hat sie alle dreizehn, und zwei
Oberflächen nebeneinander sind ab da kein Sicherheitsnetz mehr, sondern zwei
Stellen, an denen dieselbe Einstellung anders aussieht.

Im Bot liegen die alten Vorlagen weiterhin; erreichbar sind sie nur noch über
den Betriebsbereich, der `/admin` weiterleitet.

Der Betriebsbereich liegt in der Weboberfläche, im Benutzermenü unten links —
sichtbar nur für Bot-Administratoren.

**Aus dem Bundle hochladen** — ein Klon aus einem Bundle legt *keine* lokalen
Zweige an, nur Fernverweise. `git push origin web` scheitert dann mit
„Src-Refspec web entspricht keiner Referenz". Richtig ist:

```bash
git clone hoerjetzt-arbeitsstand-<version>.bundle hj
cd hj
git remote set-url origin git@github.com:MarcoEckerlin/hoer.jetzt.git
git push origin \
    refs/remotes/origin/main:refs/heads/main \
    refs/remotes/origin/core:refs/heads/core \
    refs/remotes/origin/ai-radio:refs/heads/ai-radio \
    refs/remotes/origin/lavalink:refs/heads/lavalink \
    refs/remotes/origin/web:refs/heads/web
git push origin --tags
```

Danach zur Gegenprobe `git ls-remote --heads <repo>` — es müssen **fünf**
Zweige sein. Fehlt einer, bricht `auto-update.sh` ab, weil `RELEASE` einen
Stand nennt, den es nicht gibt.
