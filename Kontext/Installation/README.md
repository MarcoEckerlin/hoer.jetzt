# Installation

Alles, was der Bot auf einem Server braucht — von der leeren Debian-Maschine
bis zum laufenden Betrieb.

## Schnellstart

Auf einem frischen Debian 12 als root:

```bash
apt-get update && apt-get install -y git
git clone <REPOSITORY-URL> /opt/discordbot-src
cd /opt/discordbot-src
bash deploy/install.sh
```

Das Skript fragt alles Nötige ab und richtet den kompletten Stack ein. Danach
ist die Weboberfläche erreichbar.

## Aufbau dieser Unterlagen

| Datei | Inhalt |
| --- | --- |
| [01-serveranforderungen.md](01-serveranforderungen.md) | Hardware, Betriebssystem, Pakete, Ports |
| [02-erstinstallation.md](02-erstinstallation.md) | Der geführte Setup-Verlauf Schritt für Schritt |
| [03-discord-berechtigungen.md](03-discord-berechtigungen.md) | Intents, Serverrechte, Einladungslink |
| [04-datenbank.md](04-datenbank.md) | Lokal oder entfernt, Benutzer, Tabellen |
| [05-betrieb.md](05-betrieb.md) | Dienste, Logs, Updates, Sicherung |
| [06-fehlersuche.md](06-fehlersuche.md) | Die Fehler, die tatsächlich vorkommen |

## Was am Ende läuft

```
                    Internet
                       │
                       ▼
              nginx  (Port 80)
                       │
                       ▼
   ┌─────────── discordbot (Port 8080) ───────────┐
   │                   │                          │
   ▼                   ▼                          ▼
 MariaDB          Lavalink                  Music-Brain
 (3306)           (2333, nur lokal)         (8091, nur lokal)
                       │                          │
                       ▼                          ▼
                   YouTube                  Sprachmodell
                                            (11434, optional)
```

Vier Dienste, davon drei ausschließlich auf `127.0.0.1`. Nach außen ist nur
nginx offen.
