# hoer.jetzt — Gesamtanleitung

Von der leeren Maschine bis zum laufenden Betrieb. Wer es eilig hat, liest nur
[Der schnelle Weg](#der-schnelle-weg).

---

## Inhalt

1. [Was wo läuft](#was-wo-läuft)
2. [Vorbereitung](#vorbereitung)
3. [Der schnelle Weg](#der-schnelle-weg)
4. [Komponenten einzeln](#komponenten-einzeln)
5. [Netz über mehrere Hosts (Tailscale)](#netz-über-mehrere-hosts-tailscale)
6. [Audio-Knoten hinzufügen](#audio-knoten-hinzufügen)
7. [Premium vergeben](#premium-vergeben)
8. [Alles auf einmal ausrollen](#alles-auf-einmal-ausrollen)
9. [Aktualisieren](#aktualisieren)
10. [Wenn etwas ausfällt](#wenn-etwas-ausfällt)
11. [Fehlersuche](#fehlersuche)

---

## Was wo läuft

```
                     Internet
                        │
                  Reverse Proxy
                        │
            ┌───── core (8080) ─────┐
            │         │             │
      MariaDB      ai-radio     Audio-Knoten
      (extern)     (8091)       ┌────┴────┐
                      │      free      premium
                 Sprachmodell  (mehrere)  (starke
                 (optional)               Hardware)
```

Der Anwendungscode liegt in **vier Zweigen** ohne gemeinsame Historie:

| Zweig | Rolle | Braucht vor allem |
| --- | --- | --- |
| `main` | Compose, Doku, Gesamtinstaller | — |
| `core` | Bot und Weboberfläche | Arbeitsspeicher |
| `ai-radio` | Titelvorschläge fürs AI-Radio | fast nichts |
| `lavalink` | Audio | CPU und Bandbreite |

**Der Audiostrom läuft nie durch den Bot.** Lavalink schickt ihn direkt an
Discord. Deshalb trägt Lavalink die Last — und deshalb spielt die Musik weiter,
wenn der Bot neu startet.

---

## Vorbereitung

**Discord** — im [Developer Portal](https://discord.com/developers/applications):

- Anwendung anlegen, unter *Bot* einen Token erzeugen
- Bei *Privileged Gateway Intents* einschalten: **Server Members** und
  **Message Content**
- Unter *OAuth2* Client-ID und Client-Secret notieren

**Datenbank** — MariaDB 10.6+, erreichbar von den Containern:

```sql
CREATE DATABASE discordbot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'discordbot'@'%' IDENTIFIED BY '<passwort>';
GRANT ALL PRIVILEGES ON discordbot.* TO 'discordbot'@'%';
```

Das Schema legt der Bot beim ersten Start selbst an. `utf8mb4` ist Pflicht —
Discord-Namen enthalten Emoji.

**Host** — Debian 12 oder Ubuntu 22.04/24.04, Docker. Fehlt Docker,
installiert der Installer ihn.

---

## Der schnelle Weg

Alles auf einem Host:

```bash
git clone -b main https://github.com/MarcoEckerlin/hoer.jetzt.git /opt/hoerjetzt/main
cd /opt/hoerjetzt/main
bash install.sh
```

Der Installer holt die drei Komponentenzweige, fragt alles ab, baut die
Abbilder und startet den Stack.

Danach:

1. Die ausgegebene Redirect-URL im Developer Portal eintragen — **zeichengenau**
2. Bot einladen:
   `https://discord.com/api/oauth2/authorize?client_id=<ID>&permissions=1101960178806&scope=bot%20applications.commands`
3. Einmal `/admin` aufrufen — wer die Anwendung besitzt, wird automatisch
   Eigentümer. Vorher kommt niemand hinein.
4. Audio-Knoten im Adminbereich eintragen (Passwort steht in `/opt/hoerjetzt/.env`)
5. KI-Chat, AI-Radio und Premium je Server freischalten

---

## Komponenten einzeln

Jeder Zweig bringt sein eigenes `install.sh` mit — für verteilte Hosts der
normale Weg.

**Bot und Weboberfläche:**

```bash
git clone -b core https://github.com/MarcoEckerlin/hoer.jetzt.git core
cd core && bash install.sh
```

**Music-Brain:**

```bash
git clone -b ai-radio https://github.com/MarcoEckerlin/hoer.jetzt.git ai-radio
cd ai-radio && bash install.sh
```

**Audio-Knoten:**

```bash
git clone -b lavalink https://github.com/MarcoEckerlin/hoer.jetzt.git knoten
cd knoten && bash install.sh
```

Jedes Skript prüft Docker, installiert es notfalls, fragt seine Werte ab, baut
und startet. Ein zweiter Lauf ersetzt den Container — Antworten werden nicht
gespeichert, also am besten gleich mitschreiben.

### Was gefragt wird

**`core`** — Bot-Token, Client-ID und Client-Secret aus dem Developer Portal;
die öffentliche Adresse (daraus wird die Redirect-URI gebildet); Zugangsdaten
der Datenbank; Instanz-Nummer (trennt mehrere Bots in derselben Datenbank);
Adresse und Passwort eines Audio-Knotens; auf welcher Adresse die
Weboberfläche lauschen soll — hinter einem Reverse Proxy `127.0.0.1`.

**`ai-radio`** — Zugangsdaten der Datenbank und die Adresse des Sprachmodells.
Eine nackte IP genügt, `http://` und Port `11434` ergänzt das Skript. Der
Dienst veröffentlicht keinen Port nach außen.

**`lavalink`** — Stufe (`free` oder `premium`), Port und Bindeadresse, dazu ein
Passwort. Auf Wunsch würfelt das Skript eines und gibt es am Ende aus.

**`main`** (Gesamtinstallation) — alles aus `core` plus die Frage, ob Tailscale
genutzt werden soll. Es schreibt `/opt/hoerjetzt/.env` mit Rechten 0600 und
startet den ganzen Stack über Compose.

Passwörter werden verdeckt eingegeben und erscheinen nicht im Protokoll. Was
danach dauerhaft gilt, steht in `/opt/hoerjetzt/.env` — diese Datei ist der
einzige Ort mit Token und Passwörtern im Klartext.

### Beim ersten Start

Der Bot legt sein Datenbankschema selbst an und ergänzt es bei jedem Start um
fehlende Spalten. Eine Migration von Hand ist nicht nötig, auch nicht beim
Wechsel auf diese Version.

`bot_admins` ist zunächst leer. Wer die Discord-Anwendung besitzt, wird beim
ersten Aufruf von `/admin` automatisch eingetragen — vorher kommt niemand in
den Adminbereich, auch der Serverinhaber nicht.

---

## Netz über mehrere Hosts (Tailscale)

Sobald Audio-Knoten auf eigenen Maschinen stehen, braucht der Bot einen Weg zu
ihnen. Ohne Tailscale heißt das: Port 2333 offen ins Internet, abgesichert nur
durch eine Firewall-Regel und das Knotenpasswort. Mit Tailscale liegen alle
Hosts in einem privaten Netz, und **2333 bleibt komplett zu**.

Der Installer fragt danach — `j` genügt. Wer es von Hand macht:

```bash
# Auf jedem Host einmalig
modprobe tun

# Hauptstack
docker compose -f docker-compose.tailscale.yml up -d

# Audio-Knoten auf einem anderen Host
TS_HOSTNAME=hoerjetzt-premium-1 TS_AUTHKEY=tskey-auth-... \
HJ_LAVALINK_PASSWORD=... LAVALINK_TIER=premium \
docker compose -f docker-compose.lavalink.tailscale.yml up -d
```

Den Auth-Key erzeugst du im Tailscale-Adminbereich unter *Settings → Keys*.
Sinnvoll: **Reusable** an, **Ephemeral** aus (sonst verschwindet der Knoten bei
jedem Neustart aus dem Netz), Tag `tag:hoerjetzt`.

### Wie es gebaut ist

Ein kleiner Sidecar-Container hängt am Netz, die übrigen Container teilen sich
seinen Netzwerk-Namensraum:

```
  Host A                              Host B
  ┌──────────────────────────┐        ┌──────────────────────┐
  │ tailscale  100.x.y.1     │        │ tailscale 100.x.y.2  │
  │  ├── core       :8080 ───┼── LAN ─┼──┐                   │
  │  ├── ai-radio   :8091    │        │  └── lavalink :2333   │
  │  └── lavalink   :2333    │        │      (premium)        │
  └──────────────────────────┘        └──────────────────────┘
         nur 8080 nach außen                 nichts nach außen
```

Drei Folgen davon:

- Die Dienste auf einem Host erreichen sich über `127.0.0.1` — `ai-radio`
  lauscht dort auch nur noch, statt im ganzen Netz.
- Der Webport wird am Sidecar veröffentlicht, nicht am `core`-Dienst. Für den
  Reverse Proxy ändert sich nichts.
- Ein Knoten auf einem fremden Host veröffentlicht **gar keinen** Port mehr.

### Knoten eintragen

Die Adresse steht nach dem Start fest:

```bash
docker exec hoerjetzt-node-tailscale tailscale ip -4
```

Im Adminbereich dann `http://100.x.y.z:2333` eintragen. **Die 100er-Adresse,
nicht den MagicDNS-Namen** — Tailscale schreibt in Containern ungern an
`/etc/resolv.conf`, deshalb steht `TS_ACCEPT_DNS` auf `false` und Namen werden
nicht aufgelöst. Die Adresse bleibt einem Knoten dauerhaft erhalten, solange er
nicht als *ephemeral* angemeldet wurde.

Wer MagicDNS trotzdem will: `TS_ACCEPT_DNS=true` setzen und im Auge behalten,
ob die Container noch öffentliche Namen auflösen — sonst erreicht der Bot
Discord nicht mehr.

### Was Tailscale nicht löst

Die Datenbank liegt weiterhin außerhalb. Steht sie nicht mit im Tailnet, bleibt
ihr Zugang so abzusichern wie bisher.

---

## Audio-Knoten hinzufügen

Auf dem neuen Host `install.sh` aus dem `lavalink`-Zweig laufen lassen und die
Stufe wählen. Das Skript gibt am Ende Adresse, Passwort und Stufe aus.

Danach **im Adminbereich unter *Lavalink* eintragen** — erst dieser Eintrag
entscheidet, welche Server auf dem Knoten landen:

| Feld | Bedeutung |
| --- | --- |
| Name | eindeutig, taucht in den Protokollen auf |
| Adresse | `http://<ip>:2333` |
| Passwort | vom Installer ausgegeben |
| Stufe | `free` oder `premium` |
| Obergrenze | gleichzeitige Wiedergaben, 0 = unbegrenzt |

Der Bot verteilt neue Server auf den **am wenigsten belasteten** Knoten der
passenden Stufe. Er liest dafür die echte CPU-Last vom Knoten und weicht von
selbst aus, bevor die harte Obergrenze greift.

**Firewall nicht vergessen:** Port 2333 gehört auf die Adresse des Bots
begrenzt. Das Passwort ist der einzige Schutz. Wer den Knoten stattdessen ins
Tailnet hängt, spart sich das — siehe
[Netz über mehrere Hosts](#netz-über-mehrere-hosts-tailscale).

---

## Premium vergeben

Premium ist eine Freischaltung je Server — wie KI-Chat und AI-Radio, und wie
diese nur durch einen Bot-Administrator. Zwei Stellen im Adminbereich:

1. **Lavalink** → beim Knoten die Stufe auf *Premium* setzen und eine
   Obergrenze eintragen (0 = unbegrenzt).
2. **Server** → beim gewünschten Server *Premium-Audio* einschalten.

Danach landet dieser Server auf den Premium-Knoten, alle anderen bleiben auf
Standard. Ein Tageslimit gibt es hier bewusst nicht — Premium ist eine
Zuteilung, kein Kontingent.

Ist gerade kein Premium-Knoten erreichbar, spielt die Musik auf einem
Standard-Knoten weiter und der Bot schreibt eine Warnung ins Protokoll. Lieber
schlechter platziert als still.

Dasselbe per SQL, falls es schneller gehen soll:

```sql
-- Knoten zum Premium-Knoten machen
UPDATE deployment_lavalink_nodes
   SET tier = 'premium', max_players = 20
 WHERE node_name = 'premium-1';

-- Server auf Premium heben
INSERT INTO guild_entitlements (bot_id, guild_id, feature, enabled)
VALUES (1, '123456789012345678', 'PREMIUM_AUDIO', 1)
ON DUPLICATE KEY UPDATE enabled = 1;
```

Die Änderung greift nach spätestens 60 Sekunden — so lange hält der
Zwischenspeicher.

---

## Alles auf einmal ausrollen

Für den Wechsel von der alten JAR-Installation auf den Docker-Stack — und für
jedes spätere „bau mir alles neu":

```bash
cd /opt/hoerjetzt/main && git pull
bash deploy-alles.sh
```

Das Skript geht der Reihe nach vor und fragt vor allem nach, was nicht
umkehrbar wäre:

1. **Alte Dienste** — `discordbot`, `lavalink` und `discordbot-music-brain`
   belegen dieselben Ports (8080, 2333, 8091). Sie werden gestoppt und aus dem
   Autostart genommen, nicht deinstalliert. Zurück geht es mit
   `systemctl enable --now <dienst>`.
2. **Sicherung** — liest die Zugangsdaten aus `/opt/discordbot/config/config.json`
   und legt einen Dump unter `/var/backups` ab, bevor irgendetwas startet.
3. **Zweige** — holt oder aktualisiert alle vier auf den neuesten Stand.
4. **Konfiguration** — nimmt `/opt/hoerjetzt/.env`, wenn es sie gibt. Sonst
   baut es sie aus der alten `config.json` und lässt dich einmal drüberschauen.
   Gibt es beides nicht, übernimmt der normale Installer.
5. **Bauen und starten** — steht ein `TS_AUTHKEY` in der `.env`, nimmt es von
   selbst die Tailscale-Variante.

Die Datenbank bleibt unangetastet. Das Schema wird beim Start nur um fehlende
Spalten ergänzt — bestehende Daten, Freigaben und Einstellungen bleiben.

Nach dem ersten Lauf lohnt ein Blick in den Adminbereich: die Knoten haben mit
*Stufe* und *Obergrenze* zwei neue Felder, und *Premium-Audio* ist eine neue
Freigabe je Server.

---

## Aktualisieren

Von Hand, alle vier Zweige auf den neuesten Stand:

```bash
cd /opt/hoerjetzt/main && bash deploy-alles.sh
```

`git pull` funktioniert hier nicht zuverlässig: Releases werden neu gebaut und
force-gepusht, die Historie ändert sich also. `deploy-alles.sh` benutzt deshalb
`fetch` + `reset --hard`.

### Jede Nacht von selbst

```bash
systemctl enable --now hoerjetzt-update.timer
```

Der Installer bietet das beim Einrichten an. Um 03:00 (plus bis zu 15 Minuten
Streuung) sieht der Timer nach, ob ein neueres Release vorliegt.

**Ein Release ist ein Tag `v…` auf `main`.** Die Datei `RELEASE` darin nennt
den zugehörigen Stand der drei Komponenten — die Zweige haben keine gemeinsame
Historie, ein Tag allein könnte sie nicht zusammenhalten. Ungetaggte Stände
werden ignoriert: ein Push landet nicht in derselben Nacht auf dem Server.

Vor dem Umbau fragt das Skript den Audio-Knoten, ob gerade jemand zuhört. Läuft
Musik, versucht es alle 15 Minuten erneut, höchstens achtmal — danach bleibt
alles, wie es ist, und die nächste Nacht bekommt eine neue Gelegenheit.

Gebaut wird, bevor umgeschaltet wird. Schlägt der Build fehl, läuft der alte
Stand unverändert weiter und es steht im Protokoll:

```bash
tail -50 /var/log/hoerjetzt-update.log
systemctl list-timers hoerjetzt-update.timer
```

Nur nachsehen, ohne etwas zu ändern:

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --pruefen
```

Und sofort, ohne Rücksicht auf Zuhörer:

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --jetzt
```

### Ein Release veröffentlichen

`branches-anlegen.sh` schreibt `RELEASE` und setzt den Tag. Die Version kommt
aus `VERSION`, sonst aus dem Datum:

```bash
VERSION=2026.08.20 bash branches-anlegen.sh
git push -f origin main core ai-radio lavalink
git push -f --tags origin
```

Ohne den zweiten Push sieht das Auto-Update nichts — es richtet sich nach
Tags, nicht nach den Zweigspitzen.

**Ein Neustart unterbricht die Musik nicht.** Der Bot merkt sich die
Lavalink-Session und meldet sich nach dem Start als dieselbe an — Lavalink gibt
die laufenden Player zurück, statt sie abzuräumen. Die Warteschlange liegt in
der Datenbank und wird ebenfalls zurückgeholt.

Das Zeitfenster dafür ist der `resume_timeout` des Knotens (Vorgabe 60 s).
Dauert ein Update länger, hört die Musik auf — dann bleibt die Warteschlange
trotzdem erhalten und der nächste `/play` setzt fort.

---

## Wenn etwas ausfällt

**Ein Audio-Knoten fällt aus.** Kurzer Aussetzer: die Verbindung wird zum
selben Knoten wiederhergestellt, Lavalink spielt derweil weiter — man hört
nichts. Ist der Knoten wirklich tot, ziehen alle betroffenen Server auf einen
anderen Knoten **derselben Stufe** um, mit Track, Position, Lautstärke und
Filtern. Ist kein Premium-Knoten mehr da, weicht der Bot auf Standard aus und
protokolliert das.

**Der Bot fällt aus.** Die laufende Wiedergabe geht weiter — der Ton kommt von
Lavalink, nicht vom Bot. Was ausfällt: Slash-Commands, Panel, Willkommens­
nachrichten, Tickets, Protokolle. Docker startet den Container neu; mit
gespeicherter Session ist auch die Musik nach dem Start wieder unter Kontrolle.

**Die Datenbank fällt aus.** Der Bot läuft weiter, kann aber nichts speichern
und keine Konfiguration lesen. Das ist der einzige Punkt ohne Ausweichweg —
Sicherungen sind hier keine Kür:

```bash
mariadb-dump --single-transaction --skip-ssl -u discordbot -p discordbot \
    | gzip > /var/backups/discordbot-$(date +%F).sql.gz
```

---

## Fehlersuche

**`invalid_redirect_uri` beim Anmelden** — die Adresse im Developer Portal
weicht ab. Sie muss zeichengenau stimmen, auch `http` gegen `https` und ein
abschließender Schrägstrich.

**Kein Zugang zum Adminbereich** — `bot_admins` ist leer, solange `/admin`
nicht einmal aufgerufen wurde. Wer die Anwendung besitzt, wird beim ersten
Aufruf eingetragen.

**Bot verbindet sich, spielt aber nichts** — Passwort des Knotens prüfen. Es
muss im Adminbereich und im Container übereinstimmen:

```bash
docker logs hoerjetzt-lavalink | tail -30
```

**Reaction-Roles vergeben keine Rolle** — die Rolle des Bots steht nicht über
der zu vergebenden Rolle. Discord erlaubt das grundsätzlich nicht.

**Bot antwortet in einem Kanal nicht** — Kanalrechte schlagen Serverrechte.
Meist fehlt *Kanäle ansehen* oder *Nachrichten senden* für die Bot-Rolle in
genau diesem Kanal.

**`/dev/net/tun fehlt`** — das Modul ist auf dem Host nicht geladen. Einmal
`modprobe tun`, dauerhaft `echo tun > /etc/modules-load.d/tun.conf`.

**`requested tags [...] are invalid or not permitted`** — der Container
startet in Endlosschleife neu. Ein Tag muss vorher im Tailscale-Adminbereich
unter *Access Controls* stehen:

```json
"tagOwners": { "tag:hoerjetzt": ["autogroup:admin"] }
```

Ohne ACL-Eintrag gehört `TS_EXTRA_ARGS` leer gelassen — die Vorgabe setzt
keinen Tag.

**Tailscale-Container bekommt keine Adresse** — meist ist der Auth-Key
abgelaufen oder schon verbraucht. `docker logs hoerjetzt-tailscale` sagt es
deutlich. Neuen Key erzeugen, `TS_AUTHKEY` in `.env` ersetzen, Container neu
anlegen (`docker compose up -d --force-recreate tailscale`).

**Bot findet den Knoten im Tailnet nicht** — im Adminbereich steht vermutlich
ein MagicDNS-Name statt der 100er-Adresse. Adresse holen mit
`docker exec hoerjetzt-node-tailscale tailscale ip -4` und eintragen.

**Das Auto-Update passiert nicht** — der Timer richtet sich nach Tags. Sind
keine gepusht, sieht er nichts:

```bash
git -C /opt/hoerjetzt/main ls-remote --tags origin
bash /opt/hoerjetzt/main/deploy/auto-update.sh --pruefen
```

**AI-Radio oder KI sagt „nicht freigeschaltet"** — erwartetes Verhalten, beide
sind je Server gesperrt. Freigabe im Adminbereich.

Ausführlicher: [`Kontext/Installation/06-fehlersuche.md`](Kontext/Installation/06-fehlersuche.md).

---

## Was wo liegt

```
/opt/hoerjetzt/main/        Compose, Doku, Gesamtinstaller
/opt/hoerjetzt/core/        Bot
/opt/hoerjetzt/ai-radio/    Music-Brain
/opt/hoerjetzt/lavalink/    Audio-Knoten
/opt/hoerjetzt/.env         Token und Passwörter, Rechte 0600
/opt/hoerjetzt/.installiert Welches Release gerade läuft
/var/log/hoerjetzt-update.log   Protokoll des Auto-Updates
```

Container: `hoerjetzt-core`, `hoerjetzt-ai-radio`, `hoerjetzt-lavalink`, im
Tailnet-Betrieb zusätzlich `hoerjetzt-tailscale`.

```bash
docker logs -f hoerjetzt-core
docker restart hoerjetzt-core
docker ps
```
