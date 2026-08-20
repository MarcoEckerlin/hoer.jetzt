# hoer.jetzt — Update-Server

Löst GitHub als Bezugsquelle ab. Läuft zuhause, liefert **Abbilder statt
Quellcode**.

Erreichbar unter `https://repo.updates.hoer.jetzt`.

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
| Zugang | SSH-Deploy-Key pro Host | Passwort + freigeschaltete Adresse |

---

## Die zwei Passwörter

Keine Zertifikate. Client-Zertifikate überleben keinen Reverse-Proxy — und vor
diesem Dienst stehen Cloudflare und der Nginx Proxy Manager. Ein Passwort
kommt durch.

### 1 — Aufsetz-Passwort

Kurz, Form `hj-XXXX-XXXX-XXXX-XXXX`. Alphabet ohne `0/O` und `1/l/I`, damit es
sich fehlerfrei abtippen lässt. Rund 80 Bit.

Öffnet **nur** `/knoten/` — das Installationsskript und die Compose-Dateien.

```bash
curl -fsSLu knoten https://repo.updates.hoer.jetzt/knoten/aufsetzen.sh -o a.sh && bash a.sh
```

Das `-u knoten` ohne Doppelpunkt ist Absicht: curl fragt das Passwort selbst
ab, statt es in die Shell-Historie zu schreiben.

### 2 — Knoten-Passwort

**4096 Bit** Zufall, rund 684 Zeichen. Öffnet Abbilder, Release, Tresor und die
Meldestelle. Bleibt dauerhaft in der `.env` des Knotens (0600).

Kein bcrypt darauf: der Updater vergleicht es unmittelbar und in konstanter
Zeit. bcrypt schnitte nach 72 Byte ab — von 4096 Bit blieben 576 — und der
Hash enthielte Dollarzeichen, die Docker Compose in der `.env` als Variablen
liest.

### Und die Adresse

Passwort allein reicht nicht. Jede Adresse muss im Updater freigeschaltet sein.
Die Reihenfolge der Prüfung ist bewusst erst Passwort, dann Adresse: wer das
Passwort nicht hat, soll nicht erfahren, ob seine Adresse freigeschaltet wäre.

---

## Aufbau

```
   Knoten
     |  https, Basic-Auth mit dem Knoten-Passwort
     v
   Cloudflare (Proxy, orange Wolke)  -->  CF-Connecting-IP trägt die echte Adresse
     |
   Nginx Proxy Manager  -->  hält das Zertifikat
     |  http, nur im LAN
     v
   Caddy :8091
     |
     |--- forward_auth ---> Updater :8080   Passwort ok? Adresse frei?
     |                      (nur im internen Docker-Netz)
     |
   /v2/*      ------> Forgejo-Registry      Knoten-Passwort + Freigabe
   /release/* ------> Volume "ausliefern"   Knoten-Passwort + Freigabe
   /tresor/*  ------> Volume "ausliefern"   Knoten-Passwort + Freigabe
   /melden    ------> Updater :8080         Knoten-Passwort + Freigabe
   /knoten/*  ------> Volume "ausliefern"   Aufsetz-Passwort, keine Freigabe

                      Updater :8081  --> :8090 am Host   Oberfläche
```

**Cloudflare darf auf Proxy stehen.** Der Updater liest die echte Adresse aus
`CF-Connecting-IP`, das Cloudflare selbst setzt und dabei überschreibt.

**Und wenn der Port offen im Netz steht?** Dann greift `Vorfeld.java`: den
Weiterleitungs-Köpfen wird nur geglaubt, wenn die Verbindung von einem
bekannten Proxy kommt (`hj.proxy.vertrauen`, Vorgabe localhost plus die
privaten Bereiche). Wer direkt anklopft, wird mit **seiner echten Adresse**
gemessen; was er in `CF-Connecting-IP` geschrieben hat, wird verworfen.

Ohne diese Prüfung wäre die Adressfreigabe bei offenem Port wirkungslos — ein
Header genügt, und im Protokoll stünde die erfundene Adresse. Das ist der
Grund, warum der Port früher zwingend ins LAN gehörte.

Was der offene Port **nicht** löst: hier läuft unverschlüsseltes HTTP. Ohne
TLS davor gehen beide Passwörter im Klartext über die Leitung.

`/knoten/` ist der einzige Pfad ohne Adressprüfung — ein frisch aufgesetzter
Rechner ist noch nicht freigeschaltet, und genau das Skript, das er dort holt,
sagt ihm, dass er es werden muss.

Forgejo bleibt als einziges auf `127.0.0.1`. Für die
Verwaltung: `ssh -L 3000:127.0.0.1:3000`.

`ausliefern` ist ein benanntes Docker-Volume, kein Pfad auf dem Host — die
Bauschritte des CI-Runners laufen in eigenen Containern und kennen keine
Hostpfade.

---

## Der Tresor

Die gemeinsamen Zugangsdaten, nach Profil getrennt: `voll` bekommt Datenbank,
Bot-Token und Client-Secret, `lavalink` nur das Lavalink-Passwort. Ein
Audio-Knoten braucht nichts weiter — und soll nichts weiter bekommen.

Er wird beim Abruf **an den öffentlichen Schlüssel des fragenden Knotens
gerichtet**. Zwei Knoten bekommen zwei verschiedene Antworten, und keiner kann
die des anderen öffnen. Der private Schlüssel entsteht beim Aufsetzen und
verlässt den Host nie.

Verfahren: RSA-OAEP für den Sitzungsschlüssel, AES-256-CBC mit HMAC-SHA256 als
Encrypt-then-MAC. Nicht GCM — der Gegenpart ist ein Bash-Skript, und
`openssl enc` kann den Authentifizierungsanhang nicht. Der HMAC wird **vor**
dem Entschlüsseln geprüft.

> Hier stand vorher, der Tresor liege im Klartext im Auslieferungsverzeichnis
> und der Server könne die Zugangsdaten mitlesen. Das galt, solange alle Knoten
> ohnehin dasselbe Passwort teilten — dann schützte Verschlüsselung vor
> niemandem. Mit eigener Identität je Knoten ändert sich die Rechnung.

```bash
bash tresor.sh fuellen voll
bash tresor.sh fuellen lavalink
bash tresor.sh stand
bash tresor.sh zeigen voll
```

---
## Einrichten

Auf einer frischen Maschine — **von GitHub**, nicht von hier:

```bash
curl -fsSL https://raw.githubusercontent.com/MarcoEckerlin/hoer.jetzt/main/deploy/install-update-server.sh | bash
```

Das ist der einzige Installer, der GitHub braucht, und der Grund ist banal: er
legt den Update-Server gerade erst an. Jeder andere holt seinen Stand von
`repo.updates.hoer.jetzt` — dieser kann das nicht, er *ist* das Ziel.

Ein Initialpasswort für die Oberfläche lässt sich vorgeben:

```bash
bash install-update-server.sh --passwort-stdin < geheim.txt
```

`--passwort 'GEHEIM'` geht auch, warnt aber: der Wert steht danach in
`~/.bash_history` und stand während des Laufs in `ps aux`. Ohne Angabe wird
eines erzeugt und einmal angezeigt — das ist die beste der drei Varianten.

Wenn die Quellen schon liegen, genügt:

```bash
bash einrichten.sh
```

Fragt nach Name, internem Port, Forgejo-Konto und der Adresse für die
Updater-Oberfläche. Erzeugt beide Passwörter, legt Forgejo an, meldet den
Runner an, baut den Updater, startet alles — und prüft sich am Ende selbst.

Am Ende zeigt es **einmal** beide Passwörter und die Zugänge. Danach steht in
der `.env` nur noch das, was verglichen wird; das Passwort der Oberfläche
liegt als bcrypt-Hash da.

**Danach im Nginx Proxy Manager anlegen:**

| | |
|---|---|
| Domain | `repo.updates.hoer.jetzt` |
| Weiterleiten an | `http://<host>:8091` |
| Zertifikat | wie üblich über den NPM |
| Upload-Grenze | **aus** (`client_max_body_size 0`) |

Die Upload-Grenze ist kein Detail: Abbild-Schichten sind hunderte Megabyte, und
NPM bricht sonst mittendrin ab — mit einer Meldung, die nach einem Fehler in
der Registry aussieht.


### Passwort der Oberfläche vergessen

```bash
bash passwort.sh
```

Erzeugt ein neues, zeigt es einmal an, tauscht den Hash in der `.env` und
startet den Updater neu. Eigenes Passwort mit `--stdin` oder `--datei`,
Benutzername mit `--name`.

Der Klartext steht nach dem Einrichten nirgends — gespeichert ist nur der
bcrypt-Hash. Bricht `einrichten.sh` zwischen dem Schreiben der `.env` und der
Schlussanzeige ab, ist das Passwort weg, bevor es jemand gesehen hat. Vorher
half nur ein kompletter Neuaufbau; für ein vergessenes Passwort einer
Weboberfläche ist das absurd.

### Die Selbstprobe

Sie prüft, was man sonst erst im Betrieb merkt:

- beide Passwörter öffnen **je nur ihren** Bereich, nicht den des anderen
- eine freigeschaltete Adresse kommt durch, eine fremde nicht
- ohne Passwort gibt es **keine** Auskunft darüber, ob eine Adresse frei wäre
- der Torwächter-Port liegt **nicht** auf dem Host
- ein Testabbild geht wirklich hoch und wieder herunter

Zusätzlich liest sie die geschriebene `.env` einmal durch Docker Compose zurück
und vergleicht. Grund: Compose ersetzt in der `.env` Variablen, und ein
bcrypt-Hash besteht aus Dollarzeichen. Ohne Maskierung bekommt Caddy einen
zerlegten Hash, Compose warnt kryptisch über nicht gesetzte Variablen, und das
Forgejo-Konto lässt sich nicht anlegen. Genau das ist einmal passiert.

## Der Updater — Freigaben, Knoten, Protokoll

Ein kleiner Spring-Boot-Dienst neben Caddy und Forgejo. Er macht zwei Dinge,
die zusammengehören, weil beide auf denselben Bestand schauen:

**1. Er ist das Tor.** Vor jedem Zugriff auf `/v2/`, `/release/`, `/tresor/`
und `/melden` fragt Caddy per `forward_auth` beim Updater nach, ob diese
Adresse freigeschaltet ist. Das Knoten-Passwort allein reicht damit nicht — es
braucht Passwort **und** freigeschaltete Adresse.

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
das Knoten-Passwort wird zusätzlich verlangt.

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

Das Knoten-Passwort kann nicht über GitHub kommen; ein privates Repository ist
kein Geheimnisspeicher. Ablauf:

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

Fehlt das Passwort, bricht `auto-update.sh` mit genau dieser Anleitung ab statt
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

Jeder Knoten hat sein eigenes Geheimnis. Der Widerruf betrifft deshalb nur
ihn — und nicht mehr alle.

Im Updater unter **Verwalten** den Knoten **sperren**. Das wirkt sofort: der
Zwischenspeicher wird bei jeder Änderung verworfen, offene Aufsetz-Token
werden mitwiderrufen. Gesperrt statt gelöscht, damit die Spur bleibt.

Zusätzlich lässt sich unter **Freigaben** die Adresse sperren. Beides
zusammen ist der Gürtel-und-Hosenträger-Fall; die Knotensperre allein reicht,
weil sie am Knoten hängt und nicht an einer IP, die sich beim Neuaufsetzen
ändert.

Soll der Knoten weiterlaufen, aber mit neuem Geheimnis: **Geheimnis
tauschen**. Er ist danach ausgesperrt, bis der neue Wert bei ihm in der
`.env` steht — es geht keine Verbindung von hier zu ihm.

> Das war früher anders und ist der Kern des Umbaus: bis dahin teilten sich
> alle Knoten ein Passwort, und ein aufgemachter Audio-Knoten gab Bot-Token,
> Datenbank-Passwort und Client-Secret preis. Widerrufen ließ sich nur die
> Adresse — die wechselt, sobald eine Hetzner-Maschine neu aufgesetzt wird.

Ein Passwort **je Knoten** wäre die saubere Lösung — der Updater müsste dafür
eine Liste statt eines Wertes vergleichen. Steht unter „Offen".

## Offen

~~**Alle Knoten teilen ein Passwort.**~~ **Erledigt.** Jeder Knoten hat eine
eigene Kennung und ein eigenes Geheimnis; der Benutzername in Basic-Auth
trägt die Kennung, `docker login` bleibt unverändert. Was ein Knoten holen
darf, ergibt sich aus seinen Modulen (`Faehigkeit.java`). Ein Audio-Knoten
kommt nicht mehr an den Core-Tresor.

~~**Der Tresor liegt im Klartext.**~~ **Erledigt.** Er wird beim Abruf an den
öffentlichen Schlüssel des anfragenden Knotens gerichtet — zwei Knoten
bekommen zwei verschiedene Antworten, und keiner kann die des anderen öffnen.
Der private Schlüssel entsteht beim Aufsetzen und verlässt den Host nie.

Verfahren: RSA-OAEP für den Sitzungsschlüssel, AES-256-CBC mit HMAC-SHA256 als
Encrypt-then-MAC. Nicht GCM — der Gegenpart ist ein Bash-Skript, und
`openssl enc` kann den Authentifizierungsanhang nicht. Der HMAC wird **vor**
dem Entschlüsseln geprüft; andersherum verrät das Auffüllmuster von CBC den
Klartext Byte für Byte.

**Der Server kann den Tresor weiterhin lesen.** Er hält den Klartext, um ihn
verschlüsseln zu können. Das ist inhärent daran, dass er ihn verteilt — was
sich geändert hat, ist der Weg dorthin und der Kreis der Empfänger.

**Der Knoten braucht wieder ein Schlüsselpaar.** Hier stand vorher, das sei
bewusst abgeschafft worden — „ein Passwort reicht für alles". Das ist
zurückgenommen, und zwar nicht aus Reue über die damalige Entscheidung: sie
war richtig, solange alle Knoten ohnehin dasselbe Passwort teilten. Sobald
jeder eine eigene Identität hat, kostet ein Schlüsselpaar fast nichts mehr —
`install-node.sh` erzeugt es beim Aufsetzen, und niemand muss es abtippen
oder verwahren.

Nicht CMS, wie es die alte Fassung vorsah: das JDK kann es ohne
Fremdbibliothek nicht, und eine Bibliothek mehr im Abbild für einen Umschlag
ist ein schlechter Tausch.

**„Update vormerken" wirkt erst beim nächsten Lauf** von `auto-update.sh`, also
nachts um drei oder wenn der Agent es auslöst. Es geht keine Verbindung vom
Update-Server zu den Knoten — die stehen hinter fremdem NAT.

**Knotenübersicht und Controller überschneiden sich.** Der Controller kennt den
Live-Zustand im Minutentakt, der Updater das Ergebnis des letzten Updates.
Beides hat seinen Grund; ob es auf Dauer zwei Ansichten bleiben sollen, ist
nicht entschieden.
