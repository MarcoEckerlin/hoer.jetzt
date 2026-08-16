# hoer.jetzt — Gesamtanleitung

Von der leeren Maschine bis zum laufenden Betrieb. Wer es eilig hat, liest nur
[Der schnelle Weg](#der-schnelle-weg).

Wer nur den Befehl sucht und die Erklärung schon kennt: [BEFEHLE.md](BEFEHLE.md).

---

## Inhalt

1. [Was wo läuft](#was-wo-läuft)
2. [Vorbereitung](#vorbereitung)
3. [Der schnelle Weg](#der-schnelle-weg)
4. [Komponenten einzeln](#komponenten-einzeln)
5. [Netz über mehrere Hosts](#netz-über-mehrere-hosts)
6. [Audio-Knoten hinzufügen](#audio-knoten-hinzufügen)
7. [Premium vergeben](#premium-vergeben)
8. [Mehrere Knoten auf einem Host](#mehrere-knoten-auf-einem-host)
9. [Alles auf einmal ausrollen](#alles-auf-einmal-ausrollen)
10. [Privates Repository](#privates-repository)
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
                    web (React)
                        │
            ┌───── core (8080) ─────┐
            │         │             │
   PostgreSQL      ai-radio     Audio-Knoten
   + Redis         (8091)       ┌────┴────┐
                      │      free      premium
                 Sprachmodell  (mehrere)  (starke
                 (optional)               Hardware)
```

Der Anwendungscode liegt in **fünf Zweigen** ohne gemeinsame Historie:

| Zweig | Rolle | Braucht vor allem |
| --- | --- | --- |
| `main` | Compose, Doku, Betriebsskripte | — |
| `core` | Bot, Schnittstelle, Steuerung | Arbeitsspeicher |
| `web` | Weboberfläche (React) | fast nichts |
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

**Datenbank** — PostgreSQL läuft im Stack mit, es ist nichts vorzubereiten.
Das Schema legt der Bot beim ersten Start selbst an.

Wer von einer bestehenden MariaDB kommt: `deploy/umzug-postgres.sh` bringt die
Daten hinüber und zählt gegen.

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
   Eigentümer. Vorher kommt niemand hinein. **Der Adminbereich liegt seit dem
   Umbau nicht mehr in der Weboberfläche**, sondern in einer eigenen
   Installation auf Port 8081 (siehe *Adminbereich getrennt installieren*).
   Wurde er beim Setup nicht mitinstalliert, ist `/admin` auf dieser Maschine
   nicht erreichbar.
4. Audio-Knoten im Adminbereich eintragen (Passwort steht in `/opt/hoerjetzt/.env`)
5. KI-Chat, AI-Radio und Premium je Server freischalten

---

## Zwei Nodes hinter einem Load Balancer

Der Aufbau Cloudflare → Load Balancer → zwei vollständige Nodes funktioniert,
aber **eine Einstellung am Load Balancer ist Pflicht**: *Sticky Sessions* muss
an sein.

Der Grund: die Anmeldung liegt in einer Sitzung im Arbeitsspeicher des
jeweiligen Bots. Sie wird nicht zwischen den Nodes geteilt. Verteilt der Load
Balancer die Anfragen abwechselnd, landet der Browser mit einem Cookie, das
Node A ausgestellt hat, auf Node B — und die kennt es nicht. Das Ergebnis ist
kein sauberer Fehler, sondern der unangenehmste Fall überhaupt: es
funktioniert etwa jedes zweite Mal. Man wird beim Speichern hinausgeworfen,
meldet sich neu an, und beim nächsten Klick wieder.

In der Hetzner-Konsole: Load Balancer → Dienst → *Sticky Sessions* aktivieren.

Die saubere Lösung wäre, die Sitzungen in Redis abzulegen, das ohnehin auf
jeder Node läuft. Das ist bewusst noch nicht umgesetzt: Redis wird zwischen
den Nodes absichtlich **nicht** repliziert, es müsste also erst ein geteilter
Redis dazukommen. Sticky Sessions kosten einen Haken und lösen dasselbe
Problem.

### Sitzungen: warum man ständig hinausfliegt

Eine Anmeldung lag bisher im Arbeitsspeicher genau des Bots, bei dem man sich
angemeldet hat. Das fällt auf zwei Arten auf, und beide fühlen sich gleich an:

- **Bei zwei Nodes** schickt der Load Balancer die nächste Anfrage an die
  andere, die das Cookie nicht kennt.
- **Bei jedem Neustart** — Update, `docker compose up -d`, Absturz — sind alle
  angemeldet gewesen. Das nächtliche Auto-Update macht das jede Nacht, und das
  trifft auch eine Installation mit nur einer Node.

Sticky Sessions am Load Balancer beheben nur den ersten Punkt.

Beides zusammen behebt `HJ_SESSION_STORE=datenbank` in der `.env`: die
Sitzungen liegen dann in PostgreSQL, die ohnehin auf allen Nodes dieselbe ist.
Die Tabellen legt der Bot beim ersten Start selbst an — **danach einmal
`spock-einrichten.sh anlegen` laufen lassen**, sonst werden sie nicht zwischen
den Nodes abgeglichen und es bleibt beim alten Verhalten.

Geht dabei etwas schief, ist der Rückweg die Zeile aus der `.env` zu nehmen und
neu zu starten — kein neues Release nötig.

### Was sich die Nodes teilen und was nicht

| | geteilt | getrennt |
|---|---|---|
| PostgreSQL | ja, Multi-Master über Spock | |
| Redis | | je Node, absichtlich |
| Sitzungen der Weboberfläche | mit `HJ_SESSION_STORE=datenbank` | sonst je Node |
| Discord-Shards | Zuteilung über den Verbund | jede Node fährt ihre eigenen |

### Was auf jeder Node verschieden sein muss

- `HJ_NODE_NR` — **niemals zweimal dieselbe.** Sie bestimmt den Zahlenraum der
  fortlaufenden Nummern in der Datenbank. Zwei Nodes mit derselben Nummer
  vergeben dieselben Kennungen und kollidieren beim Abgleich.
- `HJ_PRIVAT_IP` — die eigene 10.x-Adresse.

Gleich sein müssen `HJ_BOT_TOKEN`, `HJ_BOT_ID` und `HJ_WEB_BASE_URL`.

Die Shards verteilt der Verbund selbst; von Hand gesetzte `HJ_SHARD_VON` /
`HJ_SHARD_BIS` überschreiben das und dürfen sich **nie** überlappen — Discord
erlaubt je Shard-Nummer genau eine Verbindung und wirft beide hinaus, die sich
darum streiten.

### Der Health Check

Vorgabe `/` mit Statuscodes 2??/3?? passt: die Weboberfläche antwortet dort mit
der Startseite. Er prüft damit allerdings nur, ob nginx lebt — nicht, ob der
Bot dahinter antwortet. Wer das will, stellt den Pfad auf `/api/public/stats`.

### TLS und die Kopfzeilen

Der Load Balancer muss als **HTTPS**-Dienst eingerichtet sein, nicht als TCP.
Nur dann schickt er `X-Forwarded-Proto: https`, und nur dann kann der Bot
richtige Adressen bauen. Bei Cloudflare gehört der SSL-Modus auf **Full
(strict)**; auf „Flexible" spricht Cloudflare den Load Balancer unverschlüsselt
an und man bekommt *400 Bad Request — The plain HTTP request was sent to HTTPS
port*.

`HJ_WEB_BASE_URL` gehört auf `https://hoer.jetzt` — mit `https`, ohne Port,
ohne Schrägstrich am Ende, und zeichengenau so im Discord Developer Portal.

---

## Adminbereich getrennt installieren

Der Adminbereich ist seit dem Umbau eine eigene Anwendung mit eigenem Abbild
und eigenem Container. Vorher lief er in derselben Oberfläche wie das
Server-Dashboard — auf **jeder** Node, auch auf denen, auf denen niemand etwas
verwalten soll. Geschützt war er allein durch die Rechteprüfung in core. Die
hält, aber eine Tür, die es nicht gibt, hält besser.

`install.sh` fragt danach; die Vorgabe ist **nein**. Nachträglich:

```bash
git clone -b admin --single-branch <repo> /opt/hoerjetzt/admin
cd /opt/hoerjetzt/main/deploy/docker
docker compose -f docker-compose.yml -f docker-compose.admin.yml up -d --build admin
```

Danach erreichbar auf `127.0.0.1:8081`. **Nicht** auf `0.0.0.0` binden — davor
gehört ein Reverse Proxy mit TLS, sonst geht die Sitzung im Klartext über das
Netz und die Rechteprüfung ist wertlos.

Die Anmeldung hängt an einem Cookie, das core setzt. Der Adminbereich muss
deshalb unter demselben Ursprung erreichbar sein wie core — entweder über
denselben Hostnamen im äußeren Proxy oder über einen eigenen Hostnamen, der im
Developer Portal als zusätzliche Redirect-URL hinterlegt ist.

Was drin ist:

| Seite | Wofür |
|---|---|
| **Verbund** | Alle Nodes, ihre Shards, ihr Release, wann sie sich zuletzt gemeldet haben. Hier wird auch das Ziel für den ganzen Verbund gesetzt. |
| **Audio-Knoten** | Auslastung je Lavalink-Knoten, welche Server darauf liegen, *Neu verbinden* je Knoten, Knotentabelle neu einlesen, Server neu verteilen. |
| **/admin** | Die bisherige Verwaltungsoberfläche von core — Knoten eintragen, Bot-Admins, Premium. Sie kann noch Dinge, die die neuen Seiten nicht können, und wandert deshalb mit hierher statt zu verschwinden. |

`auto-update.sh` erkennt an `/opt/hoerjetzt/admin`, ob der Bereich installiert
ist, und gibt die Zusatzdatei bei Build und Start automatisch mit. Ist er nicht
installiert, überspringt das Update ihn kommentarlos — das ist der Normalfall.

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

**`main`** (Gesamtinstallation) — alles aus `core` plus die Nummer dieser Node
im Verbund. Die private Adresse findet es selbst. Es schreibt `/opt/hoerjetzt/.env` mit Rechten 0600 und
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

## Netz über mehrere Hosts

Mehrere Maschinen brauchen einen Weg zueinander, über den die Datenbank und
die Steuerung laufen können — ohne dass beides im offenen Netz steht.

Bei Hetzner ist das ein **Private Network**: in der Konsole anlegen, die
Server hineinhängen, fertig. Jede Maschine bekommt eine `10.x`-Adresse, die
Verbindung untereinander kostet nichts und verlässt das Rechenzentrum nie.
Die Adresse kommt in die `.env` als `HJ_PRIVAT_IP`; `hetzner-autoinstall.sh`
findet sie von selbst.

Die Firewall macht daraus zwei Zonen: nach außen nur 22, 80 und 443 — im
privaten Bereich alles. Ohne die zweite Regel finden sich die Nodes nicht;
ohne die erste steht die Datenbank offen.

**Achtung, eine Falle:** Docker umgeht `ufw` bei veröffentlichten Ports. Ein
`-p 5432:5432` landet direkt in iptables und ist offen, egal was `ufw` sagt.
Deshalb bindet der Stack gezielt an Adressen statt an `0.0.0.0` — siehe
`HJ_WEB_BIND` und `HJ_PRIVAT_IP`. Nach dem Start einmal nachsehen:

```bash
ss -tlnp | grep docker
```

> Früher lief das über einen Tailscale-Sidecar. Der ist entfallen: er war eine
> zusätzliche Abhängigkeit von einem fremden Dienst für etwas, das Hetzner
> mitliefert — und die Tag-Verwaltung hat mehr Zeit gekostet als das Netz
> selbst.

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

### Ohne Neustart

Der Bot muss dafür **nicht** neu gestartet werden. Drei Wege, die alle zum
selben führen:

- **Speichern im Adminbereich.** Der Abgleich läuft sofort mit; die Meldung
  sagt, wie viele Knoten übernommen wurden.
- **Knotenwache.** Alle 30 Sekunden liest der Bot die Knotentabelle nach.
  Damit kommt auch das an, was nicht über die Oberfläche kam — ein Eintrag
  direkt in der Datenbank, ein Knoten, der beim Speichern noch nicht lief, oder
  eine korrigierte Adresse. Takt über `HJ_LAVALINK_WATCH_SECONDS`, `0` schaltet
  sie ab.
- **Knopf *Knoten jetzt einlesen*** auf der Lavalink-Seite, wenn es schneller
  gehen soll.

Verglichen wird nicht nur der Name, sondern auch Adresse, Passwort, Zeitlimit
und Resuming. Wer also einen Tippfehler in der Adresse korrigiert, bekommt
einen neu verbundenen Knoten statt eines Eintrags, der nur in der Liste anders
aussieht.

Was dabei passiert, steht im Protokoll:

```
[LAVALINK] Knoten premium-2 neu dazugekommen.
[LAVALINK] 1 Aenderung(en) an den Knoten uebernommen - ohne Neustart.
[LAVALINK] 3 Server auf die passende Stufe gezogen.
```

Ein Knoten, der aus der Liste verschwindet oder auf eine neue Adresse wechselt,
gibt seine laufenden Wiedergaben ab — Track, Position und Lautstärke ziehen auf
einen anderen Knoten um. Hörbar ist höchstens ein kurzer Aussetzer. Ist es der
**einzige** Knoten, gibt es keinen, auf den umgezogen werden könnte; dann steht
die Musik, bis er wieder antwortet.

### Einen Knoten neu verbinden

Unter jedem Knoten steht der Knopf **Neu verbinden**. Er trennt die Verbindung
und baut sie sofort wieder auf — gegen eine hängende Sitzung, einen Knoten, der
zwar antwortet aber nichts mehr abspielt, oder eine Verbindung, die nach einem
Netzausfall nicht sauber zurückkam.

Was er **nicht** tut: den Lavalink-Dienst neu starten. Dazu müsste der Bot auf
dem fremden Host Befehle ausführen können, und genau das soll er nicht. Ein
echter Neustart läuft auf dem Knoten-Host:

```bash
docker restart hoerjetzt-lavalink-1          # Knoten-Host
docker compose restart lavalink-free-1       # im Stack
```

---

## Wenn die Standard-Knoten voll sind

Die Stufentrennung ist eine Zuteilung, keine Mauer. Sind alle Standard-Knoten
am Anschlag, bekäme der nächste Server sonst gar keinen Ton — während nebenan
eine Premium-Maschine leer läuft. Deshalb weichen Standard-Server dann auf
Premium aus. Im Adminbereich steht bei ihnen **Überlauf** statt „liegt falsch",
und im Protokoll:

```
[LAVALINK] Standard-Knoten ausgelastet - Server 123… weicht auf einen Premium-Knoten aus.
```

Drei Bremsen sorgen dafür, dass Premium trotzdem Premium bleibt:

- Der Überlauf greift erst, wenn **kein einziger** Standard-Knoten mehr Luft
  hat. „Voll" heißt: Obergrenze erreicht **oder** Systemlast über 85 %.
- Er nimmt nur Premium-Knoten, die selbst noch Luft haben, und lässt dort
  einen Platz frei — der gehört einem Premium-Server.
- In der Bewertung bekommt ein Überlauf-Knoten einen kräftigen Aufschlag. Jeder
  noch so belastete Standard-Knoten gewinnt gegen einen leeren Premium-Knoten.

Zurück geht es von selbst: der Stufenabgleich zieht die Server auf einen
Standard-Knoten, sobald dort wieder Platz ist.

Wer das nicht will — Premium bleibt unter allen Umständen leer:

```bash
echo 'HJ_LAVALINK_FREE_OVERFLOW=false' >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d core
```

Feinjustierung, falls die Schwelle nicht passt:

| Variable | Vorgabe | Bedeutung |
| --- | --- | --- |
| `HJ_LAVALINK_OVERFLOW_CPU` | `0.85` | ab welcher Systemlast ein Knoten voll ist |
| `HJ_LAVALINK_PREMIUM_RESERVE` | `1` | freigehaltene Plätze je Premium-Knoten |

Die Reserve wirkt nur bei gesetzter **Obergrenze**. Steht dort `0`
(unbegrenzt), gibt es keine Plätze zum Zählen — dann entscheidet allein die
Last.

**Firewall nicht vergessen:** Port 2333 gehört auf die Adresse des Bots
begrenzt. Das Passwort ist der einzige Schutz. Wer den Knoten stattdessen ins
Tailnet hängt, spart sich das — siehe
[Netz über mehrere Hosts](#netz-über-mehrere-hosts).

---

## Klangqualität

Auf dem Knoten, nicht im Bot. Drei Voreinstellungen:

| | Opus | Resampling | wofür |
| --- | --- | --- | --- |
| `hoch` | 10 | HIGH | Vorgabe, beste Qualität, meiste CPU |
| `mittel` | 8 | MEDIUM | kaum hörbar schlechter, spürbar weniger Last |
| `sparsam` | 5 | LOW | schwache Hardware oder viele Wiedergaben |

Der Installer fragt danach — `hoch` bei Premium-Knoten, sonst `mittel`. Ändern:

```bash
# im Stack
echo 'LAVALINK_QUALITAET=mittel' >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && docker compose up -d lavalink-free-1

# auf einem eigenen Knoten
docker rm -f hoerjetzt-lavalink
docker run -d --name hoerjetzt-lavalink --restart unless-stopped -p 2333:2333 \
    -e LAVALINK_SERVER_PASSWORD=... -e LAVALINK_TIER=premium \
    -e LAVALINK_QUALITAET=sparsam hoerjetzt-lavalink:latest
```

Beim Start schreibt der Knoten die geltenden Werte ins Log:

```
==> Lavalink startet
    Stufe:  premium
    Klang:  hoch (Opus 10, Resampling HIGH)
```

Einzelne Werte gehen auch direkt und schlagen die Voreinstellung:
`LAVALINK_OPUS_QUALITY`, `LAVALINK_RESAMPLING`, `LAVALINK_BUFFER_MS`,
`LAVALINK_FRAME_BUFFER_MS`.

**Was hier nicht hilft:** die Bitrate des Discord-Sprachkanals deckelt alles.
Ein Kanal mit 64 kbit/s klingt nicht besser, egal was auf dem Knoten steht —
das ist eine Servereinstellung in Discord und hängt am Boost-Level (bis 96,
128, 256 bzw. 384 kbit/s). Wer mehr Klang will, dreht **zuerst dort**.

Die beiden Puffer betreffen nicht den Klang, sondern Aussetzer: größer heißt
robuster gegen Netzwerkhaken, minimal mehr Verzögerung.

---

## Mehrere Knoten auf einem Host

Zwei Wege, je nachdem ob die Knoten zum Hauptstack gehören oder für sich stehen.

**Mit Compose, aus dem Hauptstack heraus:**

```bash
cd /opt/hoerjetzt/main/deploy/docker
cp /opt/hoerjetzt/.env .env
docker compose -f docker-compose.nodes.yml up -d node-2 node-3 node-4
```

`node-1` ist der Knoten aus `docker-compose.yml` auf 2333, die weiteren liegen
auf 2334, 2335 und 2336. Stufe und Qualität je Knoten über die `.env`:

```
NODE2_TIER=free
NODE3_TIER=free
NODE4_TIER=premium
NODE4_QUALITAET=hoch
```

**Einzeln, mit dem Installer aus dem `lavalink`-Zweig:**

```bash
cd /opt/hoerjetzt-node && bash install.sh     # fragt nach der Nummer
```

Die Nummer bestimmt Containernamen (`hoerjetzt-lavalink-2`) und den
vorgeschlagenen Port (`2334`). Mehrfach ausführbar — jede Nummer bekommt ihren
eigenen Container, statt den vorherigen zu ersetzen.

**Danach im Adminbereich** jeden Knoten einzeln unter *Lavalink* eintragen, mit
eindeutigem Namen und der Adresse samt Port. Ohne diesen Eintrag existiert er
für den Bot nicht.

### Was das bringt — und was nicht

Mehrere Knoten auf **einem** Host teilen sich dieselbe CPU. Das bringt keine
Leistung, sondern **Trennung**: eigene Obergrenzen je Knoten und einen
Premium-Pool, in den nur wenige Server dürfen. Wenn der Grund für Premium
„läuft flüssiger" ist, braucht es eine zweite Maschine — sonst konkurrieren
Premium und Standard weiterhin um dieselben Kerne.

Sinnvoll ist es trotzdem: mit Obergrenzen lässt sich verhindern, dass zwanzig
Standard-Server einen Knoten füllen, auf dem auch der Premium-Server liegt.

---

## Wenn Titel nicht spielen

Meist YouTube. Der erste Blick gilt der Frage, ob **alles** hakt oder nur
einzelne Titel — das sind verschiedene Ursachen und verschiedene Lösungen.

```bash
docker logs --tail 200 hoerjetzt-lavalink-free-1-1 \
    | grep -E "sig function|requires login|AllClientsFailed"
```

**Entschlüsselung — wenn gar nichts mehr geht.**

```
Problematic YouTube player script /s/player/03d0c0e0/base.js detected
(issue detected with script: must find sig function)
```

YouTube versteckt die eigentliche Stream-Adresse hinter einer Funktion im
Player-Skript und tauscht dieses Skript im Wochentakt aus. Eine neue
Plugin-Version gibt es alle paar Monate. Wenn das auseinanderläuft, spielt
nichts mehr — auch nichts Jugendfreies.

Deshalb läuft im Stack ein kleiner Dienst mit, der genau diesen Schritt
übernimmt: `yt-cipher`. Er wird deutlich häufiger nachgezogen als das Plugin.
Er hängt im internen Docker-Netz und hat keinen Port nach außen.

```bash
cd /opt/hoerjetzt/main/deploy/docker
docker compose pull yt-cipher
docker compose up -d yt-cipher lavalink-free-1
docker logs --tail 20 hoerjetzt-yt-cipher-1
```

Beim Start sagt jeder Knoten, woran er ist:

```
    Cipher:  http://yt-cipher:8001
```

Steht dort stattdessen „im Plugin", ist `YT_CIPHER_URL` leer — dann rechnet das
Plugin wieder selbst, mit genau dem Risiko von oben.

Hilft auch das nicht, bleibt der Entwicklungsstand des Plugins. Die Versionen
heißen dort nach dem Commit und stehen unter
`https://maven.lavalink.dev/snapshots/dev/lavalink/youtube/youtube-plugin/`:

```bash
cat >> /opt/hoerjetzt/.env <<'EOF'
YOUTUBE_PLUGIN_SNAPSHOT=true
YOUTUBE_PLUGIN_VERSION=<Commit-Hash>
EOF
cd /opt/hoerjetzt/main/deploy/docker && cp /opt/hoerjetzt/.env .env
docker compose up -d lavalink-free-1
```

Das ist ungetesteter Code. Als Notnagel gut, als Dauerzustand nicht — sobald
eine reguläre Version erscheint, wieder zurück auf `false`.

**Altersbeschränkung.** YouTube liefert „18+"-Videos nur an angemeldete
Clients. Das trifft ganze Kataloge — Rammstein etwa fast vollständig, viel
Metal und Rap ebenso.

Von den YouTube-Clients können damit überhaupt nur zwei umgehen: `WEBEMBEDDED`
eingeschränkt und ohne Anmeldung, `TV` vollständig — aber **nur mit OAuth**.
Beide sind eingetragen; ohne Anmeldung bleibt es also bei dem, was
`WEBEMBEDDED` hergibt, und das ist bei Rammstein nichts. Führt kein Weg dran
vorbei:

```bash
echo 'YOUTUBE_OAUTH=true' >> /opt/hoerjetzt/.env
cd /opt/hoerjetzt/main/deploy/docker && docker compose up -d lavalink-free-1
docker logs -f hoerjetzt-lavalink-free-1-1
```

Im Log erscheint ein Gerätecode und eine Adresse. Dort bestätigen, danach steht
der `refreshToken` im Log:

```bash
echo 'YOUTUBE_REFRESH_TOKEN=<token>' >> /opt/hoerjetzt/.env
docker compose up -d lavalink-free-1
```

Ohne den Token wiederholt sich die Bestätigung bei jedem Neustart. Beim Start
sagt der Knoten selbst, woran er ist:

```
    YouTube: angemeldet (Token hinterlegt)
```

**Nimm dafür ein Wegwerf-Konto**, kein privates. Das Konto ist danach für
YouTube ein Abspielgerät wie jedes andere — auffälliges Verhalten kann es
kosten.

Auf einem eigenständigen Knoten-Host fragt `install.sh` beides mit ab —
Anmeldung und Refresh-Token. Denselben Token können alle Knoten benutzen; er
muss nicht pro Host neu bestätigt werden.

**Wenn die Anmeldung steht und es trotzdem nicht geht.** Sieht das Log so aus:

```
YouTube access token refreshed successfully
Client [TVHTML5] failed: This video requires login.
```

dann ist der Bot fertig mit seinem Teil, und es liegt am Konto oder am Titel:

- Google verlangt in der EU für „18+" eine echte Altersverifikation, Ausweis
  oder Kreditkarte. Ein frisches Konto mit bloßem Geburtsdatum gilt als
  minderjährig und bekommt die Videos nicht — auch angemeldet nicht.
- Der Titel ist zusätzlich regionsgesperrt. Dagegen hilft kein Konto.

In beiden Fällen sucht der Bot denselben Titel auf SoundCloud. Das ist Absicht:
eine andere Quelle ist besser als Stille. Im Log steht dann `Fallback`.

**Bot-Erkennung.** Meldet das Log `AllClientsFailedException` bei praktisch
allem, hält YouTube den Knoten für einen Bot. Dagegen hilft ein poToken-Paar:

```
YOUTUBE_PO_TOKEN=...
YOUTUBE_VISITOR_DATA=...
```

**Was nicht daran liegt:** einzelne Titel, die im Browser gehen und im Bot
nicht, sind fast nie ein Netzwerkproblem. Prüfen lässt sich das mit einem
anderen Titel derselben Band — geht der, ist es die Altersbeschränkung.

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

Die Datenbank bleibt unangetastet. Das Schema wird beim Start nur um fehlende
Spalten ergänzt — bestehende Daten, Freigaben und Einstellungen bleiben.

Nach dem ersten Lauf lohnt ein Blick in den Adminbereich: die Knoten haben mit
*Stufe* und *Obergrenze* zwei neue Felder, und *Premium-Audio* ist eine neue
Freigabe je Server.

---

## Privates Repository

Solange das Repository öffentlich ist, kommt jeder Host anonym an den Code.
Wird es privat, gilt das für deine eigenen Hosts genauso — `install.sh`,
`deploy-alles.sh` und das nächtliche Update laufen sonst in eine
Passwortabfrage und bleiben stehen.

**Auf jedem Host einmal:**

```bash
bash /opt/hoerjetzt/main/deploy/zugang-einrichten.sh
```

Das Skript legt einen SSH-Schlüssel an, zeigt dir den öffentlichen Teil zum
Eintragen, prüft den Zugang und stellt alle vier Arbeitsverzeichnisse auf SSH
um. Eingetragen wird er unter *Settings → Deploy keys* im Repository — **ohne**
Häkchen bei *Allow write access*.

**Pro Host ein eigener Schlüssel.** Ein Deploy-Key gilt für genau ein
Repository; mehrere Hosts können denselben verwenden, aber dann sperrt das
Entfernen alle gleichzeitig aus. Ein Schlüssel je Host kostet nichts und lässt
sich einzeln zurückziehen.

Der Schlüssel hat bewusst keine Passphrase — er wird um 03:00 von einem Timer
benutzt, eine Passphrase müsste also im Klartext danebenliegen und wäre keine.
Er liegt unter `/root/.ssh/hoerjetzt_deploy` mit Rechten 0600 und verlässt den
Host nicht.

**Bei einer Neuinstallation** danach mit SSH-Adresse klonen:

```bash
GIT_SSH_COMMAND="ssh -i /root/.ssh/hoerjetzt_deploy" \
  git clone -b main git@github.com:MarcoEckerlin/hoer.jetzt.git /opt/hoerjetzt/main
cd /opt/hoerjetzt/main && REPO=git@github.com:MarcoEckerlin/hoer.jetzt.git bash install.sh
```

`REPO` landet in der `.env` und gilt ab dann für alle weiteren Läufe.

### Was das schützt — und was nicht

Ein privates Repository hält den Quelltext aus Suchmaschinen und von
Gelegenheitskopien fern. Es macht aus dem Projekt keine Festung: wer auf einem
deiner Hosts root ist, hat den Code ohnehin — er liegt dort im Klartext, und
das gebaute JAR lässt sich zurückübersetzen. Für fremde Hardware wäre der
sichere Weg, gar keinen Quelltext auszuliefern und stattdessen fertige Abbilder
aus einer privaten Registry zu ziehen.

---

## Aktualisieren

Von Hand, alle vier Zweige auf den neuesten Stand:

```bash
cd /opt/hoerjetzt/main && bash deploy-alles.sh
```

`git pull` funktioniert hier nicht zuverlässig: Releases werden neu gebaut und
force-gepusht, die Historie ändert sich also. `deploy-alles.sh` benutzt deshalb
`fetch` + `reset --hard`.

### Einzelne Audio-Knoten

Knoten auf eigenen Hosts hängen nicht am nächtlichen Timer — der kennt nur den
Stack. Dort stattdessen:

```bash
cd /opt/hoerjetzt-node
bash update.sh          # alle Knoten auf diesem Host
bash update.sh 2        # nur hoerjetzt-lavalink-2
```

Das Skript holt den neuen Stand, baut das Abbild und ersetzt die Container —
**mit genau den Einstellungen, die sie schon haben**. Passwort, Stufe, Port und
Qualität liest es aus dem laufenden Container, statt sie neu abzufragen. Ein
versehentlich geändertes Passwort würde den Eintrag im Adminbereich ungültig
machen und den Knoten stumm schalten.

Knoten aus `docker-compose.nodes.yml` gehen den Compose-Weg:

```bash
cd /opt/hoerjetzt/main/deploy/docker
docker compose -f docker-compose.nodes.yml up -d --build
```

Während des Neustarts ziehen die betroffenen Server auf einen anderen Knoten um
und kommen zurück, sobald dieser wieder da ist. Bei mehreren Knoten auf einem
Host lohnt es sich deshalb, sie **einzeln** zu aktualisieren.

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
```json
"tagOwners": { "tag:hoerjetzt": ["autogroup:admin"] }
```

Ohne ACL-Eintrag gehört `TS_EXTRA_ARGS` leer gelassen — die Vorgabe setzt
keinen Tag.

**Bot findet den Knoten im Tailnet nicht** — im Adminbereich steht vermutlich
ein MagicDNS-Name statt der 100er-Adresse. Adresse holen mit

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

```bash
docker logs -f hoerjetzt-core
docker restart hoerjetzt-core
docker ps
```
