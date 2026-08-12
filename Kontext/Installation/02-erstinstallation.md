# 2. Erstinstallation

```bash
cd /opt/discordbot-src
bash deploy/install.sh
```

Als root, aus dem Projektverzeichnis. Das Skript ist mehrfach ausführbar:
bereits gesetzte Werte werden beim nächsten Lauf als Vorgabe angeboten,
vorhandene Daten bleiben unangetastet.

Bis zur Rückfrage *„Jetzt installieren?"* wird nichts verändert. Vorher darf
jederzeit mit Strg+C abgebrochen werden.

## Der Verlauf

### 1. Datenbank

> MariaDB auf diesem Server installieren? (j/n)

**Ja** — MariaDB wird installiert, Datenbank und Benutzer werden angelegt, das
Passwort auf Wunsch zufällig erzeugt. Der Zugriff läuft über den Unix-Socket,
ein root-Passwort ist nicht nötig.

**Nein** — Adresse, Port, Datenbankname und Zugangsdaten werden abgefragt und
die Verbindung sofort geprüft. Schlägt sie fehl, zeigt das Skript die nötigen
SQL-Befehle an und fragt, ob trotzdem weitergemacht werden soll.

### 2. Discord

Bot-Token, Client-ID und Client-Secret aus dem Developer Portal. Der Token wird
verdeckt eingegeben. Bei einem zweiten Lauf genügt Enter, um den vorhandenen
Wert zu behalten.

### 3. Weboberfläche

Port (Vorgabe 8080) und die öffentliche Adresse. Die Adresse landet als
Redirect-URI in der Konfiguration und **muss** exakt so im Developer Portal
stehen — sonst schlägt die Anmeldung mit `invalid_redirect_uri` fehl.

Anschließend die Frage nach nginx. Ohne Reverse Proxy ist die Oberfläche direkt
unter Port 8080 erreichbar, dann aber ohne TLS.

### 4. Audio

Lavalink lokal (Vorgabe) oder ein bestehender Knoten. Lokal wird das JAR
geladen und ein zufälliges Passwort gesetzt. Ein bereits vorhandenes Passwort
bleibt erhalten, damit ein zweiter Lauf laufende Verbindungen nicht abreißt.

### 5. Zusatzdienste

**Music-Brain** schlägt Titel für das AI-Radio vor. Ohne den Dienst spielt
AI-Radio einen festen Ersatzmix.

**Sprachmodell** für KI-Chat und AI-Radio. Ohne Modell bleiben beide Funktionen
einfach aus — das ist ein gültiger Zustand, kein Fehler.

### Zusammenfassung und Ausführung

Alle Angaben werden noch einmal gezeigt. Nach der Bestätigung läuft ohne
weitere Rückfragen durch:

1. Pakete installieren
2. Dienstbenutzer `discordbot` und `lavalink` anlegen
3. Datenbank einrichten oder Verbindung prüfen
4. Bauen (`mvn package`, beim ersten Mal einige Minuten)
5. JARs und Konfiguration einspielen
6. Lavalink laden und konfigurieren
7. systemd-Units schreiben
8. nginx einrichten
9. Dienste starten und prüfen

Am Ende steht je Dienst ein **OK** oder **FEHLER**, dazu die Prüfung, ob die
Weboberfläche antwortet.

## Danach

1. **Redirect-URL eintragen** — im Developer Portal unter OAuth2 genau die
   Adresse, die das Skript am Ende ausgibt.
2. **Bot einladen** — über den OAuth2-URL-Generator mit `bot` und
   `applications.commands`.
3. **`/admin` einmal aufrufen** — wer die Anwendung im Developer Portal
   besitzt, wird dabei automatisch als Eigentümer eingetragen. Vorher ist die
   Tabelle `bot_admins` leer und niemand kommt in den Adminbereich.
4. **Rollenrechte setzen** — im Serverpanel unter *Rollenrechte*. Solange dort
   nichts hinterlegt ist, gilt die Rückfallregel: wer *Server verwalten* darf,
   darf alles.
5. **KI-Chat und AI-Radio freigeben** — beide sind je Server gesperrt. Freigabe
   im Adminbereich unter *Server*. Ohne Freigabe lehnt der Bot ab; es gibt
   keinen Ersatzweg.

## Wo was liegt

```
/opt/discordbot/                 Bot
    DiscordBot-alpha-1.0.jar
    config/config.json           Token und Zugangsdaten, Rechte 0600
/opt/discordbot-music-brain/     Music-Brain
/opt/lavalink/                   Lavalink und application.yml
/opt/discordbot-src/             Quellstand für spätere Updates
/opt/discordbot-backups/         Sicherungen der Deployments
/etc/systemd/system/             discordbot, lavalink, discordbot-music-brain
/etc/nginx/sites-available/discordbot
```
