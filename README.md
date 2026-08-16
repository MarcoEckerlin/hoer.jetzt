# hoer.jetzt — core

Discord-Bot und Weboberfläche. Eigener Zweig ohne gemeinsame Historie mit
`main`, `ai-radio` oder `lavalink`.

```bash
docker build -t hoerjetzt-core .
```

## Konfiguration

Alles aus `config/config.json` lässt sich über Umgebungsvariablen
überschreiben — im Container ist das der Normalfall. Die Zuordnung steht
vollständig in `ConfigEnvOverlay`; die wichtigsten:

| Variable | Bedeutung |
| --- | --- |
| `HJ_BOT_TOKEN` | Bot-Token aus dem Developer Portal |
| `HJ_DB_HOST` `HJ_DB_USER` `HJ_DB_PASSWORD` | Datenbank |
| `HJ_WEB_BASE_URL` | öffentliche Adresse, muss zum Redirect passen |
| `HJ_DISCORD_CLIENT_ID` `HJ_DISCORD_CLIENT_SECRET` | Anmeldung über Discord |
| `HJ_BOT_ID` | trennt mehrere Instanzen in derselben Datenbank |

Leere Variablen zählen als nicht gesetzt und überschreiben nichts.

## Knotenauswahl

`TierAwareLoadBalancer` ersetzt den Standard-Balancer der Lavalink-Bibliothek.
Er wählt den Knoten nach der Stufe des Discord-Servers (`free` oder `premium`)
und darin den am wenigsten belasteten. Die Bibliothek fragt ihn auch dann, wenn
ein Knoten ausfällt und laufende Verbindungen verschoben werden — die
Stufentrennung gilt damit auch im Störungsfall.

Premium wird je Server im Adminbereich freigeschaltet, wie KI-Chat und
AI-Radio. Ist kein Premium-Knoten erreichbar, weicht der Bot auf die
Standardstufe aus und protokolliert das.
