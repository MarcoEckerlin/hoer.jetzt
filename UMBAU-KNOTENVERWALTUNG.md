# Umbau: zentrale Knotenverwaltung

Phase 1 der Spezifikation — Bestandsaufnahme, Kollisionen, Migrationsweg.
Stand: 20.08.2026.

Die Spezifikation schliesst mit dem Satz, der diesem Dokument seinen Zweck
gibt: *nicht einfach eine zweite parallele Architektur bauen.* Dieses Dokument
haelt fest, was schon da ist — und an welchen sechs Stellen die Vorgabe etwas
voraussetzt, das es so nicht gibt.

---

## 1. Was bereits existiert

### Update-Server — steht, laeuft, deckt etwa die Haelfte ab

`main/update-server/`. Spring-Boot-Dienst ("Updater") neben Caddy und einer
Forgejo-Registry. SQLite, drei Tabellen: `freigabe`, `knoten`, `zugriff`.

| Anforderung | Zustand |
|---|---|
| Node Registry | teilweise — Tabelle `knoten`, aber ohne Identitaet |
| Node Authentication | **ein gemeinsames Passwort fuer alle Knoten** |
| IP Whitelist | vollstaendig, mit Sofortwirkung und Protokoll |
| Updates | vollstaendig, Abbilder statt Quellcode |
| Installer | `/knoten/aufsetzen.sh`, eigenes kurzes Passwort |
| Secrets | `tresor.sh`, **Klartext**, zwei Profile |
| Admin Interface | Thymeleaf, Port 8081, privates Netz |
| Release Upload | `veroeffentlichen.sh` — Abbilder zuerst, Manifest zuletzt |
| Audit Logs | `zugriff`, jeder Zugriff, erlaubte wie abgewiesene |
| Backups | fehlt |
| Node Capabilities | fehlt |
| Node Maintenance | fehlt |

Die Zugangspruefung sitzt in `Torwaechter` und `Zugang`, Caddy fragt per
`forward_auth` an. Reihenfolge Passwort → Adresse, und zwar bewusst: wer das
Passwort nicht hat, soll nichts ueber die Freigabeliste erfahren.

### Controller — existiert, aber nicht als Server

Was die Spezifikation "Controller Server" nennt, ist heute **kein eigener
Dienst**. Es ist der Core-Bot auf derjenigen Node, bei der
`HJ_CONTROLLER_TOKEN` gesetzt ist. Endpunkt `/api/verbund/anmelden`, Tabellen
`cluster_nodes` und `cluster_ziel`.

Web laeuft bereits getrennt (nginx + React), die Datenbank ebenfalls
(PostgreSQL 16 mit Spock-5-Multi-Master ueber `docker-compose.spock.yml`).
Die geforderte Aufteilung Controller / Webinterface / Datenbank ist damit
**schon vorhanden** — sie heisst nur anders.

### Agent — zwei Stueck, beide einteilig

1. `deploy/agent/hj-agent.sh` — systemd-Timer, jede Minute. Meldet an den
   Controller, setzt Shard-Aufteilung und Release um.
2. `lavalink/agent/einrichten.sh` — je Lavalink-Instanz, Port 8098+N, kann
   Container neu starten.

Keiner von beiden verwaltet mehrere Module. Der geforderte Multi-Service-Agent
ist Neubau, aber auf vorhandener Grundlage.

### Hetzner — bereits angebunden, fuer Autoscaling

`HJ_AUTOSCALE_*` deckt Server-Typ, Standort, Abbild, SSH-Schluessel, Netz,
Firewall und Load Balancer ab. Es fehlen: Placement Groups, Labels, Storage,
Templates und Resize.

---

## 2. Die sechs Kollisionen

### K1 — Es gibt keine `PasswordEncryption` im Core

Abschnitt 31 sagt: *"Falls bereits eine PasswordEncryption-Komponente im Core
vorhanden ist, soll diese analysiert und als Grundlage verwendet werden."*

Sie ist nicht vorhanden. Geprueft wurden alle 211 Java-Dateien des Core-Zweigs.
Geheimnisse liegen heute im Klartext: in der `.env` des Hosts (0600) und im
Tresor des Update-Servers.

**Folge:** Das Verfahren wird neu entworfen statt erweitert. Damit daraus keine
Eigenbau-Kryptografie wird: ausschliesslich JDK-Bordmittel in
Standardkombinationen, kein selbst erdachtes Format.

**Nachtrag nach der Umsetzung.** Der erste Entwurf sah X25519 mit HKDF und
AES-256-GCM vor. Daraus wurden **zwei** Verfahren, weil die Gegenseiten
verschieden sind — und der Unterschied ist keine Geschmacksfrage:

| | Wofuer | Verfahren | Warum |
|---|---|---|---|
| `Umschlag` (Update-Server) | Tresor an einen Knoten | RSA-OAEP + AES-256-CBC + HMAC-SHA256 | Der Gegenpart ist ein Bash-Skript. `openssl enc` kann den Authentifizierungsanhang von GCM **nicht**, das JDK kann CMS nicht ohne Fremdbibliothek, und Pythons Standardbibliothek kennt kein AES. |
| `Geheimtext` (Core) | API-Token in der Datenbank | AES-256-GCM | Hier liest und schreibt nur der Bot. Dann ist GCM richtig: verschluesselt und beglaubigt in einem Schritt, und man kann die Reihenfolge nicht falsch herum bauen. |

Beim CBC-Verfahren wird der HMAC **vor** dem Entschluesseln geprueft. Andersherum
verraet das Auffuellmuster einem Angreifer den Klartext Byte fuer Byte. Diese
Reihenfolge steht in `Umschlag.java` und in `tresor-oeffnen.sh` ausdruecklich
festgeschrieben.

Beide Seiten wurden gegeneinander gemessen: Java verschliesst, `openssl` allein
oeffnet — und weist eine veraenderte Datei sowie einen fremden Schluessel ab.

### K2 — "Controller Server" waere eine zweite Architektur

Die Spezifikation beschreibt den Controller als eigenen Servertyp mit eigenem
Installer. Gebaut als neuer Dienst waere das genau der Fehler, vor dem
Abschnitt 71 warnt: der Core kennt bereits alle Knoten, alle Server, alle
Shards — ein zweiter Dienst daneben braeuchte dieselben Daten noch einmal.

**Folge:** `install-controller` richtet den Core-Stack in der Controller-Rolle
ein (Core + Web + PostgreSQL + Spock), statt einen neuen Dienst zu erfinden.
Die Rolle wird ein Schalter, kein Programm.

### K3 — Die Domains stimmen nicht mit dem Betrieb ueberein

| Spezifikation | Betrieb heute |
|---|---|
| `repo.updates.hoer.jetzt` | `update.system.hoer.jetzt` |
| `admin.updates.hoer.jetzt` | Port 8090 ueber SSH-Tunnel |
| `first.controller.system.hoer.jetzt` | feste IP in `HJ_CONTROLLER_URL` |

> Seit dem 20.08.2026 heisst der Name `repository.hoer.jetzt` - kuerzer, und
> ohne das "updates", das im Namen ohnehin nichts erklaerte. Der Zeitpunkt war
> guenstig: es war noch kein Abbild veroeffentlicht und kein Knoten
> aufgesetzt. Der Name steckt in jedem Abbild-Tag
> (`veroeffentlichen.sh`: `REGISTRY="${HJ_UPDATE_HOST}/hoerjetzt"`), spaeter
> haette die Umbenennung also alles neu veroeffentlichen bedeutet und jede
> Knoten-`.env` betroffen.

Der Admin-Bereich liegt heute bewusst **nicht** im Internet. Ihn unter
`admin.updates.hoer.jetzt` erreichbar zu machen, ist eine Sicherheitsentscheidung
und keine Umbenennung — dahinter liegen Freigaben, Tresor und Release-Steuerung.

**Folge:** Umbenennung ja, Veroeffentlichung des Admin-Bereichs nur mit
ausdruecklicher Entscheidung. Vorschlag: `admin.updates.hoer.jetzt` nur ueber
Tailscale aufloesen.

### K4 — KI-Radio laesst sich von hier aus nicht umbauen

Das Umbenennen `ai-radio` → `ki-radio` betrifft Compose, Konfiguration und
Oberflaeche — machbar. Der Dienst selbst nicht: sein Quellzweig fehlt in der
Arbeitskopie, das Abbild bindet auf 127.0.0.1, ignoriert alle `HJ_`-Variablen
und enthaelt ausschliesslich einen MariaDB-Treiber. Das ist derselbe Grund,
aus dem Aufgabe 18 offen steht.

**Folge:** Rename in allem, was hier liegt. Der Dienst selbst braucht zuerst
seinen Quellzweig.

### K5 — Ein Passwort fuer alle Knoten

Heute teilen sich **alle** Knoten `HJ_TOKEN_KNOTEN`. Dazu kommen drei weitere
globale Geheimnisse: `HJ_NODE_TOKEN`, `HJ_AGENT_TOKEN`, `HJ_CONTROLLER_TOKEN`.
Der Tresor liegt im Klartext und kennt genau zwei Profile.

Das ist die groesste Luecke zwischen Vorgabe und Bestand — und der
Update-Server nennt sie in seinem eigenen README als obersten offenen Punkt.
Ein kompromittierter Audio-Knoten gibt heute Bot-Token, Datenbank-Passwort und
Client-Secret preis.

**Folge:** Das ist der Anfang des Umbaus, nicht ein Punkt unter vielen.
Alles andere — Capabilities, Secret-Verteilung, Maintenance ueber API —
haengt daran, dass ein Knoten sich als *dieser* Knoten ausweisen kann.

### K6 — SQLite traegt die Registry, aber nicht die Backups

Drei Tabellen und ein Schreiber: fuer den heutigen Zweck richtig gewaehlt.
Die Spezifikation verlangt in Abschnitt 58 rund fuenfzehn Konzepte und in
Abschnitt 52 Drei-Stunden-Sicherungen samt Historie.

SQLite schafft das — aber `maximum-pool-size: 1` und `mode: always` auf einem
`IF NOT EXISTS`-Schema tragen keine Wanderungen. Sobald Spalten sich aendern,
braucht es eine Versionierung.

**Folge:** Bei SQLite bleiben, aber eine schlichte Wanderungsmechanik
einziehen, bevor die erste Tabelle sich aendert. `sqlite3 .backup` fuer die
Sicherung — konsistent im laufenden Betrieb, anders als eine Dateikopie.

---

## 3. Reihenfolge

Die Phasen der Spezifikation, sortiert nach dem, was tatsaechlich aufeinander
aufbaut:

```
K5 Knoten-Identitaet          <- alles Weitere haengt hier dran
   |
   +-- Capabilities           <- braucht Identitaet
   |
   +-- Node-Schluessel (K1)   <- braucht Identitaet
   |      |
   |      +-- Secret-Verteilung je Knoten
   |
   +-- Maintenance ueber API  <- braucht Identitaet
   |
   +-- Multi-Service-Agent    <- braucht Capabilities
          |
          +-- Modularer Installer
                 |
                 +-- Hetzner-Templates
                        |
                        +-- Admin-Oberflaeche
```

Was **unabhaengig** davon laufen kann und deshalb nebenher erledigt wird:
Backups (K6), KI-Radio-Rename (K4), Domain-Umstellung (K3).

---

## 4. Was der Umbau nicht anfasst

Damit klar ist, wo die Grenze liegt:

- **Spock-Replikation.** Laeuft. Jeder Compose-Aufruf braucht weiterhin beide
  `-f`-Dateien, sonst setzt Compose Postgres auf ein leeres Volume zurueck.
- **Die Abbild-statt-Quellcode-Entscheidung.** Der Grund dafuer steht im README
  des Update-Servers und gilt unveraendert.
- **Reihenfolge beim Veroeffentlichen.** Erst alle Abbilder, dann das Manifest.
- **Passwort vor Adresse** in der Zugangspruefung.

---

## 5. Stand nach der Umsetzung (20.08.2026)

| Phase | Zustand | Womit geprueft |
|---|---|---|
| 1 Analyse | fertig | dieses Dokument |
| 2 Knoten-Identitaet, Capabilities | fertig | 22 Proben in `PfadrechteTest` |
| 3 Node-Schluessel, Secret-Verteilung | fertig | 6 Proben in `UmschlagTest` + Gegenprobe mit echtem `openssl` |
| 4 Multi-Service-Agent | fertig | Sperre, Umgebung, Module einzeln durchgespielt |
| 5 Modularer Installer | fertig | Modulaufloesung und `--pruefen` durchgespielt |
| 6 Wartungsmodus | fertig | Schema und Shard-Verteilung |
| 7 Hetzner-Vorlagen, Resize | fertig | uebersetzt, keine neuen Fehler |
| 8 Sicherungen, Domains | fertig | uebersetzt, Skriptsyntax |
| 9 KI-Chat-Provider | fertig | uebersetzt, keine neuen Fehler |
| KI-Radio-Umbenennung | teilweise, siehe K4 | Dienst und Anzeige umbenannt |

---

## 6. Die vier Entscheidungen (20.08.2026)

Vier Punkte waren offen und sind beantwortet. Sie haben den Code veraendert,
nicht nur die Anleitung.

### Ausrollen: Update-Server ueber GitHub, der Rest von Hand

Der Update-Server ist der einzige Teil, der sich nicht von sich selbst holen
kann - er ist die Bezugsquelle. Deshalb geht genau dieser eine Weg weiter
ueber GitHub:

```bash
curl -fsSL https://raw.githubusercontent.com/MarcoEckerlin/hoer.jetzt/main/deploy/install-update-server.sh | bash
```

Alles Uebrige (`install-node.sh`, die Einzelinstaller) bezieht seinen Stand
danach vom Update-Server. `bootstrap.sh --rolle update-server` fuehrt jetzt
auf denselben Weg; vorher lief es durch dieselbe Kette wie die anderen und
haette sich beim Herunterladen selbst gesucht.

Neu dazu: `.github/workflows/update-server.yml`. Es baut nichts und
veroeffentlicht nichts - das bleibt beim Runner auf dem Update-Server. Es
prueft, was diese Kette braucht: uebersetzt der Updater, laufen die Proben,
sind die Skripte heil, ist keine CRLF-Datei eingecheckt. Der Grund ist der
Weg selbst: was in `main` liegt, laeuft auf dem naechsten frisch aufgesetzten
Server, ohne dass jemand vorher baut.

### Admin-Bereich: bleibt hinter dem Tunnel

Kein DNS-Name, weiterhin `ssh -L 8090:127.0.0.1:8090`. K3 ist damit nur zur
Haelfte umgesetzt, und das ist die Absicht: `repository.hoer.jetzt` ist
umgestellt, `admin.updates.hoer.jetzt` gibt es nicht.

### Knoten: kein Uebergang noetig

Alle Knoten werden neu aufgesetzt. Damit entfaellt der Uebergangspfad - und
mit ihm sein Risiko:

**`hj.token.gemeinsam-erlauben` steht ab Werk auf `false`.**

Ein Knoten ohne eigenes Geheimnis kommt damit nirgends mehr durch. Der
Schalter bleibt fuer den Notfall, muss aber ausdruecklich gesetzt werden.
Das war der letzte Rest der alten Lage aus K5, und er ist weg.

### Schluessel: erzeugt sich beim Befuellen des Tresors

`tresor.sh fuellen voll` legt `HJ_GEHEIMNIS_SCHLUESSEL` an, ohne zu fragen.

Wichtig dabei: **ein vorhandener wird uebernommen, nie ersetzt.** Ein neuer
Schluessel machte saemtliche bereits hinterlegten API-Token unlesbar, und
zwar stillschweigend - der KI-Chat scheiterte danach mit "Token abgelehnt",
und niemand kaeme auf den Tresor als Ursache.

Beim Einrichten mit vorgegebenem Passwort gilt:

```bash
bash install-update-server.sh --passwort-stdin < geheim.txt   # sauber
bash install-update-server.sh --passwort 'GEHEIM'             # warnt
```

Die zweite Form erfuellt Abschnitt 10 buchstaeblich und ist trotzdem die
schlechtere: der Wert steht danach in `~/.bash_history` und stand waehrend
des Laufs in `ps aux`. Das Skript sagt es und nennt den Weg zurueck.

Bei der Gelegenheit ist ein echtes Leck weggefallen: `einrichten.sh` gab das
Verwalter-Passwort als Argument an `docker run` weiter - sichtbar in
`ps aux`. Es geht jetzt ueber die Standardeingabe.

---

## 7. Was noch von Hand geschehen muss

1. **Das KI-Radio-Abbild umbenennen.** Der Dienst heisst jetzt `ki-radio`,
   der Abbildpfad zeigt weiter auf `ai-radio` - siehe K4. Beim ersten
   Hochfahren `--remove-orphans` mitgeben, sonst laeuft der alte Container
   weiter und haelt Port und Datenbankverbindungen belegt.
2. **Die frueher in diesem Chatverlauf gezeigten Geheimnisse tauschen.**
