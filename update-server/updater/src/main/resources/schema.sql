-- hoer.jetzt - Update-Server, Verwaltungsdaten.
--
-- SQLite, weil es hier um drei Tabellen und einen Schreiber geht. Forgejo
-- daneben macht es genauso. Zeiten stehen als ISO-8601-Text in UTC: SQLite
-- kennt keinen Zeitstempeltyp, und Text laesst sich sortieren, vergleichen
-- und von Hand lesen, wenn doch mal jemand mit sqlite3 hineinschaut.

CREATE TABLE IF NOT EXISTS freigabe (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    bereich   TEXT    NOT NULL UNIQUE,
    name      TEXT    NOT NULL DEFAULT '',
    notiz     TEXT    NOT NULL DEFAULT '',
    angelegt  TEXT    NOT NULL,
    laeuft_ab TEXT,
    aktiv     INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS knoten (
    kennung            TEXT PRIMARY KEY,
    name               TEXT    NOT NULL DEFAULT '',
    profil             TEXT    NOT NULL DEFAULT '',
    version            TEXT,
    vorher             TEXT,
    zustand            TEXT,
    ergebnis           TEXT,
    letzte_ip          TEXT,
    zuletzt_gemeldet   TEXT,
    zuletzt_gesehen    TEXT,
    update_angefordert INTEGER NOT NULL DEFAULT 0
);

-- Jeder Zugriff, erlaubte wie abgelehnte. Die abgelehnten sind der
-- eigentliche Zweck: wenn ein Knoten nicht mehr aktualisiert, steht hier,
-- ob er es versucht hat und woran es lag.
CREATE TABLE IF NOT EXISTS zugriff (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    zeit    TEXT    NOT NULL,
    ip      TEXT    NOT NULL,
    pfad    TEXT    NOT NULL,
    erlaubt INTEGER NOT NULL,
    grund   TEXT    NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS zugriff_zeit ON zugriff (zeit DESC);

-- ---------------------------------------------------------------------------
-- Knoten-Identitaet
-- ---------------------------------------------------------------------------
--
-- Bis hierher teilten sich alle Knoten ein Passwort. Das gab keinen Widerruf
-- fuer einen einzelnen Knoten - nur den ueber die Adresse, und die wechselt,
-- sobald eine Hetzner-Maschine neu aufgesetzt wird. Der Update-Server nannte
-- das in seinem eigenen README als obersten offenen Punkt.
--
-- Ab hier hat jeder Knoten sein eigenes Geheimnis. Der Weg dahin ist bewusst
-- klein gehalten: Basic-Auth schickt Benutzer UND Passwort, und der Benutzer
-- wurde bisher gelesen und weggeworfen ("er steht in jeder Anleitung"). Jetzt
-- traegt er die Kennung des Knotens. Damit bleibt "docker login" das, was es
-- war, und es kommt kein zweites Verfahren dazu.

-- Welche Module auf einem Knoten laufen. Daraus ergeben sich seine
-- Faehigkeiten - ein Knoten ohne Lavalink kommt nicht an das Lavalink-Passwort.
CREATE TABLE IF NOT EXISTS knoten_modul (
    kennung  TEXT NOT NULL,
    modul    TEXT NOT NULL,
    angelegt TEXT NOT NULL,
    PRIMARY KEY (kennung, modul)
);

-- Getrennt von den Modulen gefuehrt, obwohl sie sich daraus ergeben.
--
-- Zwei Gruende: eine einzelne Faehigkeit laesst sich sperren, ohne das ganze
-- Modul zu entfernen (etwa bei Verdacht auf einen geleakten Tresor), und beim
-- Entzug eines Moduls bleibt nachvollziehbar, was der Knoten vorher durfte.
CREATE TABLE IF NOT EXISTS knoten_faehigkeit (
    kennung    TEXT    NOT NULL,
    faehigkeit TEXT    NOT NULL,
    aktiv      INTEGER NOT NULL DEFAULT 1,
    angelegt   TEXT    NOT NULL,
    PRIMARY KEY (kennung, faehigkeit)
);

-- Der Bootstrap-Token: kurzlebig, einmalig, widerrufbar, knotenspezifisch.
--
-- Er ersetzt das gemeinsame Aufsetz-Passwort fuer neue Knoten. Der Ablauf ist
-- umgekehrt zum bisherigen: erst wird der Knoten im Update-Server angelegt und
-- bekommt einen Token, dann laeuft die Installation. Damit kennt der Server
-- den Knoten, bevor dieser zum ersten Mal anklopft - und der Token oeffnet
-- genau eine Anmeldung, nicht dauerhaft einen Bereich.
--
-- "verbraucht" statt Loeschen: die Frage "wer hat sich wann mit welchem Token
-- angemeldet" stellt sich genau dann, wenn etwas schiefgegangen ist.
CREATE TABLE IF NOT EXISTS knoten_anmeldung (
    anmeldung_id TEXT PRIMARY KEY,
    kennung      TEXT NOT NULL,
    token_hash   TEXT NOT NULL,
    angelegt     TEXT NOT NULL,
    laeuft_ab    TEXT NOT NULL,
    verbraucht   TEXT,
    verbraucht_von TEXT,
    widerrufen   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS knoten_anmeldung_kennung
    ON knoten_anmeldung (kennung);

-- Die oeffentlichen Schluessel eines Knotens.
--
-- Getrennt nach Zweck, wie in Abschnitt 31 verlangt: der Update-Schluessel
-- autorisiert den Bezug, der Geheimnis-Schluessel entschluesselt Passwoerter.
-- Beides mit demselben Schluessel zu machen hiesse, dass ein abgefangener
-- Update-Bezug auch die Zugangsdaten oeffnet.
--
-- Hier liegt ausschliesslich der oeffentliche Teil. Der private verlaesst den
-- Knoten nie - deshalb kann dieser Server die Geheimnisse eines Knotens nach
-- dem Verschluesseln selbst nicht mehr lesen.
CREATE TABLE IF NOT EXISTS knoten_schluessel (
    kennung    TEXT NOT NULL,
    zweck      TEXT NOT NULL,
    oeffentlich TEXT NOT NULL,
    angelegt   TEXT NOT NULL,
    abgeloest  TEXT,
    PRIMARY KEY (kennung, zweck, angelegt)
);

CREATE INDEX IF NOT EXISTS knoten_schluessel_aktuell
    ON knoten_schluessel (kennung, zweck, abgeloest);

-- Verwaltungshandlungen. Getrennt von "zugriff": dort steht der Maschinen-
-- verkehr, hier was ein Mensch veranlasst hat. Beides in einer Tabelle waere
-- die eine Zeile, die man sucht, zwischen zehntausend Abbildschichten.
CREATE TABLE IF NOT EXISTS verwaltung_protokoll (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    zeit     TEXT NOT NULL,
    wer      TEXT NOT NULL,
    handlung TEXT NOT NULL,
    ziel     TEXT NOT NULL DEFAULT '',
    ergebnis TEXT NOT NULL DEFAULT '',
    quell_ip TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS verwaltung_protokoll_zeit
    ON verwaltung_protokoll (zeit DESC);
