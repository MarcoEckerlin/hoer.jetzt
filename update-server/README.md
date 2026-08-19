# hoer.jetzt — Update-Server

Löst GitHub als Bezugsquelle ab. Läuft zuhause, liefert **Abbilder statt
Quellcode**.

Erreichbar unter `https://update.system.hoer.jetzt`.

---

## Warum nicht einfach ein privates Repository

Weil das den Kern des Problems nicht anfasst. Unter GitHub baut **jeder Knoten
selbst** — und dafür braucht er den vollständigen Quellbaum samt Historie,
Maven und ein JDK. Auf einem Audio-Knoten, der nichts weiter tut als Opus zu
kodieren, liegt damit der gesamte Quellcode. Wer eine dieser Maschinen
aufmacht, hat alles.

Der Update-Server dreht das um:

| | GitHub, bisher | Update-Server |
|---|---|---|
| Was der Knoten bekommt | Quellcode aller fünf Zweige | fertige Container |
| Was er dafür braucht | git, Maven, JDK, ~2 GB | Docker |
| Dauer eines Updates | 2–3 Minuten Build | Sekunden, nur Laden |
| Rückweg | Rebuild des alten Standes | ein Abbild-Tag |
| Zugang | SSH-Deploy-Key pro Host | Client-Zertifikat |

---

## Die drei Zugänge

Einer zum Tippen, zwei zum Mitnehmen.

### 1 — Knoten-Passwort

Kurz, Form `hj-XXXX-XXXX-XXXX-XXXX`. Alphabet ohne `0/O` und `1/l/I`, damit es
sich fehlerfrei abtippen lässt. Rund 80 Bit.

Öffnet **nur** `/knoten/` — das Installationsskript und die Compose-Dateien.
Keinen Schlüssel, kein Zugangsdatum. Wird beim Aufsetzen einmal gebraucht und
danach nirgends gespeichert.

```bash
curl -fsSLu knoten https://update.system.hoer.jetzt/knoten/aufsetzen.sh -o a.sh && bash a.sh
```

Das `-u knoten` ohne Doppelpunkt ist Absicht: curl fragt das Passwort selbst
ab, statt es in die Shell-Historie zu schreiben.

### 2 — Update-Ausweis (RSA-4096)

Ein Client-Zertifikat, ausgestellt von einer eigenen kleinen CA. Öffnet
`/v2/` (die Abbilder), `/release/` und `/tresor/`.

Bleibt dauerhaft auf dem Knoten. Docker legt ihn von sich aus vor, sobald er
unter `/etc/docker/certs.d/<host>/client.cert` liegt — **kein `docker login`,
kein Passwort im Speicher**. Der private Schlüssel verlässt die Platte nie; er
beweist nur, dass er da ist.

### 3 — Tresor-Schlüssel (RSA-4096)

Macht die gemeinsamen Zugangsdaten auf. Der Server bekommt nur den
öffentlichen Teil (`tresor.crt`) und kann damit verschlüsseln — **aufmachen
kann er den Tresor nicht.**

Selbst wer einen gültigen Ausweis hat und den Tresor herunterlädt, bekommt
einen CMS-Umschlag, den er nicht öffnet.

### Was das kostet

Das dauerhaft gespeicherte Zugangsdatum (der Ausweis) ist das harmloseste: er
zieht Container, die der Knoten ohnehin ausführt. Genau umgekehrt zu vorher,
wo der dauerhaft hinterlegte Deploy-Key den ganzen Quellcode aufschloss.

**Die Einschränkung, damit sie ausgesprochen ist:** wer das Knoten-Passwort
hat, kann den Installationsvorgang starten — und der fragt anschließend nach
den beiden Schlüsseln. Ohne sie kommt er nicht weiter. Die drei Stufen sind
also wirklich drei, aber das Passwort ist der Türöffner zum Verfahren, nicht
zu den Daten. `einrichten.sh` prüft das ausdrücklich: eine der Proben stellt
sicher, dass ein gültiger **Ausweis** `/knoten/` *nicht* öffnet und umgekehrt.

---

## Aufbau

```
                    Caddy  (TLS, client_auth: verify_if_given)
                      |
                      |--- forward_auth ---> Updater :8080   Adresse freigeschaltet?
                      |                      (nur im internen Docker-Netz)
                      |
   /v2/*      ------> Forgejo-Registry      Ausweis + Freigabe
   /release/* ------> Volume "ausliefern"   Ausweis + Freigabe
   /tresor/*  ------> Volume "ausliefern"   Ausweis + Freigabe + Tresor-Schlüssel
   /melden    ------> Updater :8080         Ausweis + Freigabe
   /knoten/*  ------> Volume "ausliefern"   Passwort

                             Updater :8081  Oberfläche, privates Netz
```

`verify_if_given` statt `require_and_verify`: `/knoten/` muss ohne Ausweis
erreichbar sein — ein frischer Knoten hat ja noch keinen. Der Zwang steht
deshalb an den Pfaden, nicht am Anschluss.

`/knoten/` ist auch der einzige Pfad **ohne** Adressprüfung, und zwar
absichtlich: ein frisch aufgesetzter Rechner ist noch nicht freigeschaltet,
und genau das Skript, das er dort holt, sagt ihm, dass er es werden muss.

Forgejos Oberfläche und das Git lauschen nur auf `127.0.0.1`. Für die
Verwaltung: `ssh -L 3000:127.0.0.1:3000`.

`ausliefern` ist ein benanntes Docker-Volume, kein Pfad auf dem Host — die
Bauschritte des CI-Runners laufen in eigenen Containern und kennen keine
Hostpfade.

---

## Einrichten

```bash
bash einrichten.sh
```

Erzeugt die Schlüssel und das Passwort, legt Forgejo an, meldet den Runner an,
startet Caddy — und prüft sich am Ende selbst. Die letzte Probe lädt ein
Testabbild hoch und wieder herunter; sie fällt auf alles herein, was die
anderen nicht sehen (falscher Dateiname in `certs.d`, fehlender
`/etc/hosts`-Eintrag, ein Leserecht, das Forgejo doch verlangt).

Dann den Tresor befüllen:

```bash
bash tresor.sh fuellen voll
bash tresor.sh fuellen lavalink
```

Der Unterschied ist der eigentliche Gewinn: `lavalink` enthält nur das
Lavalink-Passwort. Ein Audio-Knoten bekommt weder Datenbank noch Bot-Token —
er braucht beides nicht.

```bash
bash tresor.sh stand           # was da ist, ohne aufzumachen
bash tresor.sh zeigen voll     # nachsehen, braucht tresor.key
bash schluessel.sh zeigen      # Fingerabdrücke und Laufzeiten
```

---

## Der Updater — Freigaben, Knoten, Protokoll

Ein kleiner Spring-Boot-Dienst neben Caddy und Forgejo. Er macht zwei Dinge,
die zusammengehören, weil beide auf denselben Bestand schauen:

**1. Er ist das Tor.** Vor jedem Zugriff auf `/v2/`, `/release/`, `/tresor/`
und `/melden` fragt Caddy per `forward_auth` beim Updater nach, ob diese
Adresse freigeschaltet ist. Ein gültiger Ausweis reicht damit nicht mehr — es
braucht Ausweis **und** freigeschaltete Adresse.

Warum nicht Caddys eigenes `remote_ip`: das will die Liste in der
Konfigurationsdatei stehen haben. Jede Freischaltung wäre eine Dateiänderung
plus ein Neuladen des Webservers — womöglich während gerade ein Knoten zieht.
Hier ist es eine Zeile in der Datenbank und **gilt sofort**.

**2. Er ist die Übersicht.** Weil jeder Zugriff hier vorbeikommt und die Knoten
nach jedem Update-Lauf ihren Stand melden, weiß der Dienst von selbst, wer
wann da war und was er fährt.

### Zwei Ports, und der Unterschied steht im Compose

| | |
|---|---|
| **8080** Torwächter | Steht **nicht** unter `ports`. Erreichbar ausschließlich über das interne Docker-Netz, also durch Caddy. Kein Login davor — die Grenze ist das Netz. |
| **8081** Oberfläche | Wird auf `HJ_PULT_BIND` gelegt, Vorgabe `127.0.0.1:8090`. Für Zugriff von unterwegs die Tailscale-Adresse dieses Hosts eintragen. |

Die Trennung sitzt bewusst in der Compose-Datei und nicht nur in einer
Pfadregel: eine fehlende Zeile fällt beim Lesen auf, eine falsche Pfadregel
nicht. `PortTrennung.java` zieht dieselbe Grenze noch einmal in der Anwendung
und antwortet am falschen Port mit 404.

```bash
ssh -L 8090:127.0.0.1:8090 root@<update-server>
```

### Was auf den Seiten steht

- **Knoten** — laufendes Release, Rückweg, Containerzustand, wann zuletzt
  gesehen, letzte Adresse. Oben die Frage, mit der man die Seite aufruft:
  läuft überall dasselbe, und meldet sich noch jeder.
- **Freigaben** — Adressen und Bereiche in CIDR-Schreibweise, wahlweise
  befristet. Adressen, die es vergeblich versucht haben, lassen sich mit
  einem Klick übernehmen; abtippen ist die Stelle, an der man sich vertut.
- **Protokoll** — jeder Zugriff, erlaubte wie abgewiesene.

### Einen neuen Knoten freischalten

Die Reihenfolge ist umgekehrt zu dem, was man erwartet: **erst freischalten,
dann installieren.** `knoten-aufsetzen.sh` holt den Tresor, also die
Zugangsdaten — käme die Freischaltung danach, würde sie genau das nicht
schützen, worauf es ankommt.

1. Maschine anlegen, öffentliche Adresse notieren.
2. Im Updater unter **Freigaben** eintragen.
3. `aufsetzen.sh` laufen lassen.

Läuft es doch in der falschen Reihenfolge, bricht das Skript nicht mit
„Tresor nicht gefunden" ab, sondern nennt die eigene Adresse und sagt, wo sie
einzutragen ist.

### Sperren

Eine Freigabe zu sperren wirkt sofort — der Zwischenspeicher des Torwächters
wird bei jeder Änderung verworfen. Gesperrte Einträge bleiben stehen statt
gelöscht zu werden: die Frage „wer war das nochmal und wann hatte der Zugang"
stellt sich genau dann, wenn etwas passiert ist.

### Grundfreigaben

Beim allerersten Start legt der Updater `127.0.0.0/8`, `::1/128` und die
privaten Bereiche an. Ohne das sperrte sich der Server bei der Einrichtung
selbst aus: die Selbstprobe schiebt ein Testabbild durch Caddy, und
`/etc/hosts` zeigt die eigene Adresse auf `127.0.0.1`. Aus dem Internet ist
damit nichts erreichbar — diese Adressen werden dort nicht geroutet, und ein
gültiger Ausweis wird zusätzlich verlangt.

Anpassen über `hj.freigabe-start` bzw. die Umgebung.

### Verhältnis zum Agenten

`deploy/agent/hj-agent.sh` meldet jede Minute Zustand und Version an den
**Controller** und holt sich von dort Ziel-Release und Shard-Aufteilung. Das
bleibt, wie es ist. Der Herzschlag an den Update-Server läuft bewusst nur
**einmal je Update-Lauf** und trägt etwas anderes: ob das Update selbst
durchgelaufen ist und auf welchem Stand der Host danach steht. Beide benutzen
dieselbe Kennung (`HJ_NODE_NAME`), damit nicht zwei Listen entstehen, die
dasselbe meinen.

Die Überschneidung ist real und gewollt begrenzt — siehe die offenen Punkte
am Ende.

---

## Ein Release veröffentlichen

Wie bisher: `RELEASE` auf den gewünschten Stand bringen, Tag setzen, pushen.
Den Rest macht der Runner.

Von Hand:

```bash
bash veroeffentlichen.sh                  # Version aus RELEASE
bash veroeffentlichen.sh 2026.08.19.01    # Version vorgeben
bash veroeffentlichen.sh --nur-manifest   # nur umschalten, nichts bauen
```

Die Reihenfolge ist Absicht: erst **alle** Abbilder bauen und hochladen, ganz
zum Schluss das Manifest umschreiben. Bricht ein Build ab, zeigt das Manifest
weiter auf das vorige Release und kein Knoten merkt etwas. Ein Manifest, das
auf ein fehlendes Abbild zeigt, wäre ein Ausfall auf allen Hosts gleichzeitig
— jede Nacht um drei aufs Neue.

---

## Der Umzug — der eine Handgriff, der bleibt

Der Ausweis kann nicht über GitHub kommen; ein privates Repository ist kein
Geheimnisspeicher. Ablauf:

1. Update-Server aufsetzen, Tresor befüllen, ein Release veröffentlichen.
2. Ein **letztes** Release über GitHub ausrollen — es enthält die neue
   `deploy/auto-update.sh`, die den Update-Server statt GitHub befragt.
3. Die Adressen aller bestehenden Hosts im Updater unter **Freigaben**
   eintragen — **vor** Schritt 4. Ohne das kommen sie weder an den
   Tresor noch an die Abbilder. Die privaten Bereiche stehen von
   selbst drin, öffentliche Hetzner-Adressen nicht.
4. Auf jedem Host einmal `knoten-aufsetzen.sh` laufen lassen. Es erkennt eine
   vorhandene `.env`, sichert sie und ergänzt nur die Werte aus dem Tresor —
   `HJ_SPOCK`, eigene Ports und Tailscale-Angaben bleiben stehen.
5. Probe: `bash /opt/hoerjetzt/main/deploy/auto-update.sh --pruefen`
   Danach sollte der Host in der Knotenübersicht auftauchen.

Fehlt der Ausweis, bricht `auto-update.sh` mit genau dieser Anleitung ab statt
stillschweigend nichts zu tun.

---

## Wenn ein Update schiefgeht

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --zurueck
```

Setzt auf das zuvor gelaufene Release zurück — in Sekunden, weil das alte
Abbild noch da ist. `image prune` räumt nur weg, was älter als eine Woche ist;
ein `-f` ohne Filter hätte den Rückweg sofort mitgenommen.

---

## Wenn ein Knoten verloren geht

Alle Knoten teilen sich einen Ausweis. Geht eine Maschine verloren, ist der
Weg:

```bash
bash schluessel.sh erneuern    # neuer Ausweis, gleiche CA
```

und den neuen auf die verbliebenen Hosts bringen. Die CA bleibt, der alte
Ausweis gilt weiter — für echte Sperrung bräuchte es eine Sperrliste oder
einen Ausweis je Knoten. Beides ist mit dieser CA jederzeit nachrüstbar; für
den jetzigen Umfang wäre es Aufwand ohne Gegenwert.

---

## Offen

**Der Ausweis ist noch für alle Knoten derselbe.** Die Adressfreigabe gibt
jetzt den Widerruf, der vorher fehlte — aber sie hängt an der IP, und die
wechselt, wenn eine Hetzner-Maschine neu aufgesetzt wird. Der nächste Schritt
wäre ein Ausweis **je Knoten**, ausgestellt im Moment der Freischaltung: die
CA steht, das ist ein Einzeiler in `schluessel.sh`. Dann hinge der Widerruf
nicht mehr an der Adresse, und die Freigabeliste wäre die zweite Schicht
statt der einzigen Reißleine.

**Knotenübersicht und Controller überschneiden sich.** Der Controller kennt
den Live-Zustand im Minutentakt, der Updater das Ergebnis des letzten
Updates. Beides hat seinen Grund — der Update-Server muss auch dann
auskunftsfähig sein, wenn die Steuer-Node steht. Ob das auf Dauer zwei
Ansichten bleiben sollen, ist noch nicht entschieden.

**„Update vormerken" wirkt erst beim nächsten Lauf** von `auto-update.sh`,
also nachts um drei oder wenn der Agent es auslöst. Es geht keine Verbindung
vom Update-Server zu den Knoten — die stehen hinter fremdem NAT. Soll es
schneller gehen, ist der Weg über den Controller der richtige, nicht ein
zweiter Melde-Timer.
