# hoer.jetzt

Discord-Bot mit Weboberfläche: Musik, AI-Radio, KI-Chat und Community-Module —
konfigurierbar über ein Panel im Discord-Stil, statt über Slash-Commands.

Der Bot ist nicht auf eine Installation festgelegt. Branding, Token, Datenbank
und Rechte kommen aus der Konfiguration; mehrere Instanzen können sich eine
Datenbank teilen und werden über `bot_id` getrennt gehalten.

## Aufbau

```
src/                    Bot und Weboberfläche (Spring Boot, JDA, Lavalink)
music-brain-service/    eigener Dienst: Titelvorschläge für das AI-Radio
deploy/                 Installation, Update, Paketbau
Kontext/Installation/   Dokumentation von Anforderungen bis Fehlersuche
config/                 lokale Konfiguration (nicht im Repo)
```

### Komponenten zur Laufzeit

```
              nginx  (80)
                 │
       discordbot (8080)  ──  MariaDB (3306)
          │          │
     Lavalink     Music-Brain (8091)
       (2333)          │
                  Sprachmodell (11434, optional)
```

Nach außen ist nur nginx offen. Alles andere hört auf `127.0.0.1`.

## Installation

Auf einem frischen Debian 12 oder Ubuntu 22.04/24.04 als root:

```bash
git clone https://github.com/MarcoEckerlin/hoer.jetzt.git /opt/discordbot-src
cd /opt/discordbot-src
bash deploy/install.sh
```

Das Skript erkennt selbst, ob es aus dem Quellcode bauen muss oder ob fertige
JARs beiliegen, und fragt dann in fünf Abschnitten alles ab: Datenbank,
Discord, Weboberfläche, Audio, Zusatzdienste.

Ausführlich in [`Kontext/Installation/`](Kontext/Installation/) — Einstieg über
[`02-erstinstallation.md`](Kontext/Installation/02-erstinstallation.md).

## Betrieb

```bash
bash deploy/update.sh              # bauen, sichern, neu starten, bei Fehler zurückrollen
bash deploy/make-package.sh /root  # fertiges Paket mit allen JARs schnüren
journalctl -u discordbot -f        # mitlesen
```

## Rechte und Freischaltungen

Zwei Ebenen, die oft verwechselt werden:

**Bot-Administratoren** verwalten die Instanz. Wer die Discord-Anwendung
besitzt, wird beim ersten Aufruf von `/admin` automatisch eingetragen und kann
weitere Admins anlegen. Ein Bot-Admin umgeht auf jedem Server sämtliche
Rollenprüfungen — auch dort, wo er kein Mitglied ist.

**Rollenrechte** gelten je Discord-Server und werden im Serverpanel vergeben:
Webpanel öffnen, Musik steuern, Warteschlange verwalten, Module einstellen,
Tickets bearbeiten, Logs einsehen, KI nutzen, Rechte verwalten. Solange nichts
eingetragen ist, gilt: wer *Server verwalten* darf, darf alles.

**KI-Chat und AI-Radio** sind je Server gesperrt und werden nur von einem
Bot-Admin freigegeben — beide kosten Rechenzeit, die Sperre ist Absicht.

## Discord-Berechtigungen

Zwei Intents sind Pflicht: **Server Members** und **Message Content**.

Einladung mit allen Modulen:

```
https://discord.com/api/oauth2/authorize
    ?client_id=<CLIENT-ID>
    &permissions=1101960178806
    &scope=bot%20applications.commands
```

Ohne Moderationsbefehle genügt `permissions=2150747200`. Details in
[`03-discord-berechtigungen.md`](Kontext/Installation/03-discord-berechtigungen.md).

## Technik

Java 21 · Spring Boot 3.4 · JDA 6 · Lavalink 4 · MariaDB · Thymeleaf

Die Weboberfläche ist bewusst ohne Framework gebaut: ein CSS, ein JS je Panel,
keine Build-Kette für das Frontend.

## Konfiguration

`config/config.json` enthält Bot-Token und Discord-Secrets und steht deshalb
in `.gitignore`. Vorlage: `config/config.template.json`. Auf einem
installierten Server liegt die echte Datei unter
`/opt/discordbot/config/config.json` mit Rechten `0600`.
