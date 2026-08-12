# 5. Betrieb

## Dienste

| Dienst | Aufgabe | Benutzer |
| --- | --- | --- |
| `discordbot` | Bot und Weboberfläche | `discordbot` |
| `lavalink` | Audioknoten | `lavalink` |
| `discordbot-music-brain` | Titelvorschläge für AI-Radio | `discordbot` |
| `nginx` | Reverse Proxy | `www-data` |
| `mariadb` | Datenbank | `mysql` |

```bash
systemctl status discordbot
systemctl restart discordbot
systemctl stop discordbot

# Reihenfolge beim Neustart aller Dienste
systemctl restart lavalink && sleep 8 && systemctl restart discordbot
```

Lavalink braucht ein paar Sekunden Vorlauf. Startet der Bot zu früh, findet er
keinen Knoten und Musik bleibt stumm, bis er sich neu verbindet.

## Logs

```bash
journalctl -u discordbot -f              # mitlesen
journalctl -u discordbot --since "1 hour ago"
journalctl -u discordbot -p err          # nur Fehler
journalctl -u lavalink -n 100
```

Interne Meldungen des Bots stehen zusätzlich in der Tabelle `logs`.

## Aktualisieren

```bash
cd /opt/discordbot-src
git pull
bash deploy/update.sh
```

Das Skript sichert vorher nach `/opt/discordbot-backups/<zeitstempel>/`,
übernimmt das laufende Lavalink-Passwort, baut neu, startet die Dienste und
prüft, ob die Oberfläche mit HTTP 200 antwortet. Startet ein Dienst nicht,
rollt es selbstständig zurück.

Die Konfiguration bleibt dabei unangetastet.

## Zurückrollen von Hand

```bash
ls /opt/discordbot-backups/
cp /opt/discordbot-backups/<zeitstempel>/DiscordBot-alpha-1.0.jar /opt/discordbot/
systemctl restart discordbot
```

## Sicherung

Drei Dinge sind zu sichern:

1. `/opt/discordbot/config/config.json` — Token und Zugangsdaten
2. `/opt/lavalink/application.yml` — Lavalink-Passwort
3. Die Datenbank (siehe [04-datenbank.md](04-datenbank.md))

Die JARs nicht — die entstehen beim Build neu.

## TLS einrichten

```bash
apt-get install -y certbot python3-certbot-nginx
certbot --nginx -d bot.example.com
```

Danach in `/opt/discordbot/config/config.json` `base_url` und `redirect_uri`
auf `https://` umstellen, die neue Redirect-URL im Developer Portal eintragen
und den Bot neu starten. Ohne den zweiten Schritt schlägt die Anmeldung fehl.

## Wartungsmodus

Im Adminbereich unter *Wartung*. Die Oberfläche zeigt dann allen außer
Bot-Administratoren einen Hinweis. Der Bot selbst läuft weiter — Musik und
Befehle bleiben verfügbar.

## Ressourcen im Blick behalten

```bash
systemctl status discordbot | grep Memory
journalctl -u discordbot | grep -i "OutOfMemory"
df -h /
```

Wächst der Speicherbedarf stetig, hilft ein nächtlicher Neustart als
Zwischenlösung:

```
0 5 * * * root systemctl restart discordbot
```
