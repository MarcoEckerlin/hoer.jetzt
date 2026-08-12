# 3. Discord-Berechtigungen

## Intents

Im Developer Portal unter **Bot → Privileged Gateway Intents** müssen zwei
Schalter an sein:

| Intent | Wofür |
| --- | --- |
| **Server Members Intent** | Willkommensnachrichten, Verifizierung, Invite-Tracking, Rollenprüfung |
| **Message Content Intent** | KI-Chat, Reaktion auf Nachrichteninhalte |

Ohne diese beiden startet der Bot zwar, aber halbe Funktionen bleiben stumm.
Der dritte Schalter (*Presence Intent*) wird nicht gebraucht.

Nicht-privilegierte Intents setzt der Bot selbst: `GUILD_MESSAGES`,
`GUILD_MESSAGE_REACTIONS`, `GUILD_MODERATION`, `GUILD_VOICE_STATES`.

## Serverrechte

### Basis — ohne die geht nichts

| Recht | Wofür |
| --- | --- |
| Kanäle ansehen | Grundvoraussetzung für alles |
| Nachrichten senden | Antworten, Panels, Meldungen |
| Links einbetten | Alle Embeds, Musikanzeige, Logs |
| Dateien anhängen | Ticket-Transkripte, Bilder |
| Nachrichtenverlauf lesen | Ticket-Transkripte, Kontext im KI-Chat |
| Reaktionen hinzufügen | Reaction-Roles |
| Anwendungsbefehle verwenden | Slash-Commands |
| Verbinden | Sprachkanal betreten |
| Sprechen | Musik und AI-Radio |

Bitmaske: **2150747200**

### Je nach Modul zusätzlich

| Recht | Gebraucht von |
| --- | --- |
| Kanäle verwalten | Join-to-Create, Ticket-Kanäle, Kanalstatus beim Abspielen |
| Rollen verwalten | Reaction-Roles, Verifizierung, Willkommensrollen |
| Server verwalten | Invite-Tracking (liest die Einladungen des Servers) |
| Mitglieder kicken | `/kick` |
| Mitglieder bannen | `/ban` |
| Mitglieder timeouten | `/timeout` |
| Mitglieder stummschalten | Join-to-Create: Rechte im eigenen Kanal |
| Mitglieder taub schalten | Join-to-Create |
| Mitglieder verschieben | Join-to-Create |

Bitmaske mit allem: **1101960178806**

## Einladungslink

```
https://discord.com/api/oauth2/authorize
    ?client_id=<CLIENT-ID>
    &permissions=1101960178806
    &scope=bot%20applications.commands
```

Für einen sparsamen Start ohne Moderationsbefehle `permissions=2150747200`
verwenden — fehlende Rechte lassen sich jederzeit über die Rolle des Bots
nachtragen, ohne ihn neu einzuladen.

## Zwei Fallstricke

**Die Rolle des Bots muss über den Rollen stehen, die er vergeben soll.**
Discord erlaubt niemandem, eine Rolle zu vergeben, die gleich hoch oder höher
steht als die eigene — auch nicht mit *Rollen verwalten*. Reaction-Roles und
Verifizierung scheitern sonst wortlos.

**Rechte pro Kanal schlagen Serverrechte.** Ein Bot mit *Kanäle ansehen* auf
Serverebene sieht einen Kanal trotzdem nicht, wenn dort ein Verbot für seine
Rolle steht. Bei „der Bot antwortet nicht" lohnt zuerst der Blick in die
Kanalrechte.

## Rechte im Webpanel — etwas anderes

Davon zu trennen sind die Rechte *innerhalb* der Oberfläche. Sie werden im
Serverpanel unter **Rollenrechte** je Discord-Rolle vergeben:

| Recht | Bedeutung |
| --- | --- |
| Webpanel öffnen | Zugang zum Serverpanel überhaupt |
| Musik steuern | Abspielen, Pause, Überspringen, Lautstärke |
| Warteschlange verwalten | Titel entfernen und umsortieren |
| Module einstellen | Willkommen, Tickets, Logs und so weiter |
| Tickets bearbeiten | Ticket-Team |
| Logs einsehen | Protokolle lesen |
| KI nutzen | KI-Chat ansprechen |
| Rechte verwalten | Diese Matrix selbst ändern |

Solange nichts eingetragen ist, gilt die Rückfallregel: wer auf dem Server
*Server verwalten* darf, darf im Panel alles. Serverinhaber und Administratoren
kommen immer durch. Bot-Administratoren ebenfalls — auf jedem Server, auch dort,
wo sie kein Mitglied sind.
