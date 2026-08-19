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
