# 4. Datenbank

MariaDB 10.6 oder neuer. MySQL 8 funktioniert ebenfalls.

## Lokal

Das Installationsskript erledigt alles. Der Zugriff läuft über den
Unix-Socket als root, ein Datenbank-Passwort für root ist nicht nötig:

```sql
CREATE DATABASE IF NOT EXISTS `discordbot`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'discordbot'@'localhost' IDENTIFIED BY '<passwort>';
GRANT ALL PRIVILEGES ON `discordbot`.* TO 'discordbot'@'localhost';
FLUSH PRIVILEGES;
```

## Auf einem anderen Server

Dort von Hand anlegen, bevor das Installationsskript läuft:

```sql
CREATE DATABASE `discordbot`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'discordbot'@'%' IDENTIFIED BY '<passwort>';
GRANT ALL PRIVILEGES ON `discordbot`.* TO 'discordbot'@'%';
FLUSH PRIVILEGES;
```

Statt `'%'` besser die konkrete IP des Bot-Servers eintragen.

MariaDB muss außerdem auf der Netzwerkschnittstelle lauschen — in
`/etc/mysql/mariadb.conf.d/50-server.cnf`:

```ini
bind-address = 0.0.0.0
```

**`utf8mb4` ist Pflicht.** Discord-Namen enthalten Emoji. Mit `utf8` (drei
Byte) bricht das Einfügen ab.

**Ohne TLS ausdrücklich verbinden.** Viele interne Server sprechen kein TLS,
der Client bricht dann mit *„SSL is required, but the server does not support
it"* ab. Zum Prüfen:

```bash
mariadb --skip-ssl -h <host> -u discordbot -p discordbot -e "SELECT 1;"
```

**Latenz zählt.** Der Bot fragt die Datenbank oft. Bei einem entfernten Server
schlagen wenige Millisekunden Handshake spürbar durch, deshalb hält der Bot
einen Verbindungspool. Über 20 ms Latenz wird es zäh.

## Tabellen

Das Schema legt der Bot beim ersten Start selbst an, ebenso fehlende Spalten
nach einem Update. Kein separates Migrationsskript.

| Tabelle | Inhalt |
| --- | --- |
| `settings` | Instanzweite Einstellungen, Branding, Wartungsmodus |
| `deployments` | Instanzen mit Adresse und Port |
| `deployment_lavalink_nodes` | Audioknoten je Instanz |
| `guild_module_settings` | Modulkonfiguration je Server |
| `bot_admins` | Bot-Administratoren mit Rolle |
| `guild_role_permissions` | Rechtematrix je Server und Rolle |
| `guild_entitlements` | Freischaltung von KI-Chat und AI-Radio |
| `guild_feature_usage` | Tagesverbrauch der freigeschalteten Funktionen |
| `admin_audit_log` | Wer hat im Adminbereich was geändert |
| `ticket_transcripts` | Abgeschlossene Tickets |
| `uploaded_assets` | Hochgeladene Bilder |
| `music_track_events` | Abspielverlauf, Grundlage für Music-Brain |
| `music_listener_events` | Zuhörerzahlen für die Statistik |
| `logs` | Interne Protokolle |

## Sicherung

```bash
mariadb-dump --single-transaction --skip-ssl \
    -h <host> -u discordbot -p discordbot \
    | gzip > /var/backups/discordbot-$(date +%F).sql.gz
```

Als tägliche Aufgabe in `/etc/cron.daily/discordbot-backup`, ausführbar machen
nicht vergessen.

Zurückspielen:

```bash
gunzip -c discordbot-2026-08-12.sql.gz | mariadb --skip-ssl -u discordbot -p discordbot
```

Der Bot sollte dabei gestoppt sein.

## Zwei Instanzen auf einer Datenbank

Test und Produktion können sich eine Datenbank teilen. Getrennt wird über
`bot_id` in der `config.json` — jede Instanz sieht nur ihre eigenen Zeilen.
Zwei Instanzen mit derselben `bot_id` überschreiben sich gegenseitig.
