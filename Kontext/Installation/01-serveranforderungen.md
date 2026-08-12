# 1. Serveranforderungen

## Maschine

| | Minimum | Empfohlen |
| --- | --- | --- |
| CPU | 2 Kerne | 4 Kerne |
| RAM | 2 GB | 4 GB |
| Platte | 15 GB | 30 GB |
| Netz | 10 Mbit/s | 50 Mbit/s |

Der Speicherbedarf verteilt sich grob so: Bot 512 MB bis 1 GB, Lavalink 512 MB
bis 1 GB, Music-Brain 384 MB, MariaDB 256 MB. Mit 2 GB läuft es, aber ohne
Reserve — bei mehreren gleichzeitigen Sprachverbindungen wird es eng.

Ein Sprachmodell auf derselben Maschine ist etwas anderes: dafür sind 8 GB RAM
die Untergrenze, und ohne GPU sind die Antwortzeiten hoch. Besser auf einem
eigenen Host.

## Betriebssystem

Debian 12 (Bookworm) oder Ubuntu 22.04/24.04. Andere Distributionen gehen auch,
dann trägt das Installationsskript aber nicht.

Wichtig: die Zeitzone sollte stimmen, sonst passen die Zeitstempel in Logs und
Statistiken nicht zusammen.

```bash
timedatectl set-timezone Europe/Berlin
```

## Pakete

Das Installationsskript setzt diese selbst:

| Paket | Wofür |
| --- | --- |
| `openjdk-21-jdk-headless` | Laufzeit **und** Compiler |
| `maven` | Build |
| `mariadb-server` oder `mariadb-client` | Datenbank |
| `nginx` | Reverse Proxy (optional) |
| `curl`, `git`, `ca-certificates`, `python3` | Hilfsmittel |

**Ein JRE reicht nicht.** Ohne `javac` greift Maven auf einen Ersatzcompiler
zurück, der Java 21 nicht kennt, und bricht mit *„Releaseversion 17 nicht
unterstützt"* ab. Deshalb das JDK, nicht das JRE.

## Ports

| Port | Dienst | Sichtbar |
| --- | --- | --- |
| 80 | nginx | öffentlich |
| 443 | nginx mit TLS | öffentlich, falls eingerichtet |
| 8080 | Weboberfläche | nur lokal, nginx leitet weiter |
| 2333 | Lavalink | nur lokal |
| 8091 | Music-Brain | nur lokal |
| 3306 | MariaDB | lokal oder im internen Netz |
| 11434 | Sprachmodell | lokal oder im internen Netz |

Nach außen gehört ausschließlich Port 80 beziehungsweise 443 offen. Wird der
Bot ohne nginx betrieben, muss stattdessen 8080 erreichbar sein — dann läuft
die Discord-Anmeldung allerdings unverschlüsselt.

Firewall mit nftables oder ufw:

```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

## Ausgehende Verbindungen

Der Server muss erreichen:

- `discord.com` und `gateway.discord.gg` — Bot-Verbindung und Anmeldung
- `youtube.com`, `googlevideo.com` — Audioquellen über Lavalink
- `github.com` — Lavalink-Download bei der Installation
- `repo.maven.apache.org` — Abhängigkeiten beim Build
- `deb.debian.org` — Pakete

Ohne den Zugriff auf Maven Central lässt sich nicht bauen. In abgeschotteten
Netzen braucht es einen Spiegel oder ein vorbereitetes `~/.m2`.

## Discord

Vor der Installation im
[Developer Portal](https://discord.com/developers/applications) anlegen:

1. Anwendung erstellen, unter **Bot** einen Token erzeugen.
2. Bei **Privileged Gateway Intents** einschalten:
   - Server Members Intent
   - Message Content Intent
3. Unter **OAuth2** Client-ID und Client-Secret notieren.
4. Nach der Installation die Redirect-URL eintragen:
   `https://<adresse>/auth/discord/callback`

Der Token wird nur einmal angezeigt. Geht er verloren, muss ein neuer erzeugt
werden — der alte wird dabei ungültig.
