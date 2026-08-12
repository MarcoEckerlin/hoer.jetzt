# 6. Fehlersuche

## Build

**„Releaseversion 17 nicht unterstützt"**
Es ist nur ein JRE installiert, Maven greift auf einen Ersatzcompiler zurück.

```bash
apt-get install -y openjdk-21-jdk-headless
```

**Build hängt beim Laden der Abhängigkeiten**
Kein Zugriff auf `repo.maven.apache.org`. Prüfen mit
`curl -I https://repo.maven.apache.org/maven2/`.

## Start

**Dienst startet nicht**

```bash
journalctl -u discordbot -n 60 --no-pager
```

Die häufigsten Ursachen: Datenbank nicht erreichbar, Token ungültig, Port 8080
belegt (`ss -tlnp | grep 8080`).

**„SSL is required, but the server does not support it"**
Der Datenbankserver spricht kein TLS. Prüfen mit
`mariadb --skip-ssl -h <host> -u discordbot -p`.

**„Access denied for user"**
Der Benutzer existiert nicht für diese Herkunft. `'user'@'localhost'` und
`'user'@'%'` sind für MariaDB zwei verschiedene Benutzer.

## Discord

**Bot ist offline**
Token falsch oder abgelaufen. Im Developer Portal neu erzeugen und in
`config.json` eintragen, danach `systemctl restart discordbot`.

**Slash-Commands fehlen**
Beim Einladen fehlte `applications.commands`. Neu einladen — der Bot muss dafür
nicht entfernt werden. Global registrierte Befehle brauchen bis zu einer Stunde.

**Bot antwortet in einem Kanal nicht**
Kanalrechte prüfen: sie schlagen Serverrechte. Meist fehlt *Kanäle ansehen*
oder *Nachrichten senden* für die Bot-Rolle in genau diesem Kanal.

**Reaction-Roles vergeben keine Rolle**
Die Rolle des Bots steht nicht über der zu vergebenden Rolle. In den
Servereinstellungen unter *Rollen* nach oben ziehen.

**Willkommensnachrichten kommen nicht**
Server Members Intent nicht aktiviert.

## Weboberfläche

**`invalid_redirect_uri` bei der Anmeldung**
Die Redirect-URL im Developer Portal weicht ab. Sie muss **zeichengenau**
übereinstimmen — auch `http` gegen `https` und ein abschließender Schrägstrich
zählen. Der Wert steht in `config.json` unter `webinterface.redirect_uri`.

**502 Bad Gateway**
Der Bot läuft nicht oder hört auf einem anderen Port. Der `proxy_pass` in
`/etc/nginx/sites-available/discordbot` muss zu `webinterface.port` passen.

**Kein Zugang zum Adminbereich**
`bot_admins` ist leer, solange `/admin` nicht einmal aufgerufen wurde. Wer die
Anwendung im Developer Portal besitzt, wird beim ersten Aufruf automatisch
eingetragen. Team-Anwendungen: der Team-Eigentümer.

```sql
SELECT user_id, role, display_name FROM bot_admins;
```

**Serverpanel zeigt keine Server**
Es werden nur Server angezeigt, auf denen der Bot Mitglied ist **und** man
selbst Rechte hat. Ohne Eintrag in der Rechtematrix gilt die Rückfallregel:
*Server verwalten* genügt.

## Musik

**Bot verbindet sich, spielt aber nichts**
Lavalink prüfen:

```bash
systemctl status lavalink
journalctl -u lavalink -n 50
curl -H "Authorization: <passwort>" http://127.0.0.1:2333/version
```

Häufigste Ursache: das Passwort in `/opt/lavalink/application.yml` und in
`config.json` unter `lavalink.password` stimmen nicht überein.

**YouTube liefert nichts mehr**
Das YouTube-Plugin ist veraltet. Version in `application.yml` unter
`plugins.dependency` anheben, Lavalink neu starten.

**Musik stockt**
CPU oder Bandbreite am Limit. `top` und `systemctl status lavalink` ansehen.

## KI-Chat und AI-Radio

**„nicht freigeschaltet"**
Erwartetes Verhalten. Beide Funktionen sind je Server gesperrt. Freigabe im
Adminbereich unter *Server*.

```sql
SELECT guild_id, feature, enabled, daily_limit FROM guild_entitlements;
```

**AI-Radio spielt trotz Sperre**
War ein Fehler in älteren Ständen: die Ablehnung landete in derselben
Fehlerbehandlung wie ein Ausfall des Dienstes und wurde durch den Ersatzmix
überspielt. Behoben — die Freischaltung wird jetzt vor dem Start geprüft. Bei
einem alten Stand hilft ein Update.

**KI antwortet nicht**
Kein Sprachmodell erreichbar:

```bash
curl http://127.0.0.1:11434/api/tags
```

Und im Serverpanel prüfen, ob die Rolle das Recht *KI nutzen* hat.

## Schnelle Gesamtprüfung

```bash
systemctl is-active discordbot lavalink discordbot-music-brain nginx mariadb
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/
mariadb --skip-ssl -h <host> -u discordbot -p -e "SELECT COUNT(*) FROM bot_admins;" discordbot
journalctl -u discordbot -p err --since "1 hour ago" --no-pager | tail -20
```
