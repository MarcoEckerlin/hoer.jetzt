# hoer.jetzt — lavalink

Audio-Knoten. Baut auf dem offiziellen Lavalink-Abbild auf und ergänzt
Konfiguration aus Umgebungsvariablen sowie die Stufenkennzeichnung.

```bash
bash install.sh     # fragt alles ab und startet
bash update.sh      # später: neuen Stand holen, Container ersetzen
```

| Variable | Vorgabe | Bedeutung |
| --- | --- | --- |
| `LAVALINK_SERVER_PASSWORD` | — | Pflicht, der einzige Schutz des Knotens |
| `LAVALINK_TIER` | `free` | `free` oder `premium`, nur zur Anzeige |
| `LAVALINK_PORT` | `2333` | |
| `LAVALINK_QUALITAET` | `hoch` | `hoch`, `mittel`, `sparsam` |
| `YOUTUBE_PLUGIN_VERSION` | `1.18.2` | Version oder Commit-Hash |
| `YOUTUBE_PLUGIN_SNAPSHOT` | `false` | `true` = Entwicklungsstand statt Release |
| `YOUTUBE_OAUTH` | `false` | `true` für altersbeschränkte Titel |
| `YOUTUBE_REFRESH_TOKEN` | — | nach der einmaligen Bestätigung |
| `YT_CIPHER_URL` | — | Adresse des Cipher-Dienstes, leer = Plugin rechnet selbst |
| `YT_CIPHER_PASSWORD` | — | dessen `API_TOKEN` |

**Verbindlich ist der Eintrag im Adminbereich**, nicht die Variable hier. Erst
dort wird festgelegt, welche Stufe ein Knoten hat und wie viele gleichzeitige
Wiedergaben er annimmt.

Läuft der Knoten auf einem eigenen Host, muss Port 2333 erreichbar sein — dann
unbedingt per Firewall auf die Adresse des Bots begrenzen.

---

## Wenn YouTube nichts mehr liefert

Es gibt zwei verschiedene Fehler, die leicht verwechselt werden. Der erste
betrifft **alle** Titel, der zweite nur altersbeschränkte.

### 1. Entschlüsselung — betrifft alles

```
Problematic YouTube player script /s/player/…/base.js detected
(issue detected with script: must find sig function)
```

YouTube versteckt die eigentliche Stream-Adresse hinter einer Funktion, die im
Player-Skript steckt. Das Skript wird im Wochentakt getauscht, eine
Plugin-Veröffentlichung gibt es alle paar Monate. Steht diese Zeile im Log,
läuft schlicht nichts mehr — auch nichts Jugendfreies.

Der Ausweg ist ein Cipher-Dienst: ein kleiner Container, der genau diesen
Schritt übernimmt und viel schneller nachgezogen wird.

```bash
docker run -d --name hoerjetzt-cipher-1 --restart unless-stopped \
    --network hoerjetzt-knoten-1 -e PORT=8001 -e API_TOKEN=geheim \
    -e OVERRIDE_PLAYER_VARIANT=IAS ghcr.io/kikkia/yt-cipher:master
```

Dann am Knoten `YT_CIPHER_URL=http://hoerjetzt-cipher-1:8001` und
`YT_CIPHER_PASSWORD=geheim` setzen. `install.sh` macht das auf Wunsch selbst.

Zweiter Weg, ohne Zusatzdienst: den Entwicklungsstand des Plugins nehmen.

```bash
YOUTUBE_PLUGIN_SNAPSHOT=true
YOUTUBE_PLUGIN_VERSION=<Commit-Hash>
```

Die Hashes stehen unter
`https://maven.lavalink.dev/snapshots/dev/lavalink/youtube/youtube-plugin/`.
Das ist ungetesteter Code — als Notnagel gut, als Dauerzustand nicht.

### 2. Anmeldung — betrifft nur 18+

```
Client [TVHTML5] failed: This video requires login.
```

Altersbeschränkte Titel gibt YouTube nur an angemeldete Clients aus, und von
den Clients hier kann das genau einer: `TV`. Der taucht im Log als `TVHTML5`
auf. Voraussetzung ist `YOUTUBE_OAUTH=true` plus hinterlegter Refresh-Token.

Steht die Meldung **trotz** funktionierender Anmeldung da (im Log:
`YouTube access token refreshed successfully`), liegt es nicht mehr am Bot:

- Das Konto ist bei Google nicht altersverifiziert. Ein Geburtsdatum reicht in
  der EU nicht mehr; Google verlangt Ausweis oder Kreditkarte. Ohne das gilt
  das Konto als minderjährig und bekommt 18+ nicht.
- Der Titel ist zusätzlich regionsgesperrt. Dann hilft auch ein verifiziertes
  Konto nicht, sondern nur ein Ausgang in einem anderen Land.

In beiden Fällen greift die SoundCloud-Suche als Ersatz — im Log als
`Fallback` sichtbar. Das ist Absicht: lieber eine andere Quelle als Stille.

### Nachsehen

```bash
docker logs <container> | grep -E "initialised with clients|OAuth|Cipher"
```

`TV` muss in der Client-Liste stehen, sonst ist 18+ von vornherein aussichtslos.
