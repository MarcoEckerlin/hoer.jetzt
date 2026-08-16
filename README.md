# hoer.jetzt

Discord-Bot mit Weboberfläche: Musik, AI-Radio, KI-Chat und Community-Module —
konfigurierbar über ein Panel im Discord-Stil.

## Aufbau: vier Zweige

Der Anwendungscode liegt **nicht** in `main`. Jede Komponente hat ihren eigenen
Zweig, ohne gemeinsame Historie — ein Merge zwischen ihnen ist damit technisch
ausgeschlossen.

| Zweig | Inhalt | Abbild |
| --- | --- | --- |
| `main` | Compose, Dokumentation, Betriebsskripte | — |
| [`core`](../../tree/core) | Bot und Weboberfläche | `hoerjetzt-core` |
| [`ai-radio`](../../tree/ai-radio) | Music-Brain | `hoerjetzt-ai-radio` |
| [`lavalink`](../../tree/lavalink) | Audio-Knoten | `hoerjetzt-lavalink` |

Zum Bauen wird der jeweilige Zweig ausgecheckt. `docker compose build` in
`deploy/docker/` erwartet die drei Zweige als Arbeitskopien nebeneinander:

```bash
git clone -b core     <repo> core
git clone -b ai-radio <repo> ai-radio
git clone -b lavalink <repo> lavalink
git clone -b main     <repo> main
cd main/deploy/docker && docker compose build
```

## Betrieb

```bash
cd deploy/docker
cp .env.beispiel .env      # ausfüllen
docker compose up -d
```

Die Datenbank läuft außerhalb des Stacks und wird nur eingetragen. Das Schema
legt der Bot beim ersten Start selbst an.

## Aufbau zur Laufzeit

```
                Internet
                   │
              Reverse Proxy
                   │
        core (8080) ── MariaDB (extern)
          │      │
          │      └── ai-radio (8091, nur intern)
          │
     ┌────┴─────────────────────┐
     │                          │
  Standard-Pool            Premium-Pool
  lavalink free            lavalink premium
  (mehrere Knoten)         (bessere Hardware)
```

Audio wird auf Knoten verteilt, alles andere läuft im `core`. Welcher Knoten
einen Server bedient, entscheidet dessen Stufe: Premium-Server landen auf
Premium-Knoten, die bewusst leer gehalten werden. Freigeschaltet wird Premium
je Server im Adminbereich — wie KI-Chat und AI-Radio.

Fällt ein Knoten aus, verteilt die Bibliothek seine Verbindungen neu — die
Stufentrennung bleibt dabei erhalten. Ist kein Premium-Knoten erreichbar,
weicht der Bot auf die Standardstufe aus und protokolliert das.

## Knoten hinzufügen

Auf dem neuen Host:

```bash
HJ_LAVALINK_PASSWORD=... LAVALINK_TIER=premium \
    docker compose -f docker-compose.lavalink.yml up -d
```

Danach im Adminbereich unter *Lavalink* eintragen: Adresse, Passwort, Stufe und
Obergrenze gleichzeitiger Wiedergaben. Erst dieser Eintrag entscheidet, welche
Server auf dem Knoten landen.

## Dokumentation

[`ANLEITUNG.md`](ANLEITUNG.md) — Einrichtung, Betrieb, Fehlersuche.
[`BEFEHLE.md`](BEFEHLE.md) — Spickzettel für den Alltag.
