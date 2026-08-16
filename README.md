# hoer.jetzt — ai-radio

Music-Brain: schlägt Titel für das AI-Radio vor. Eigener Zweig ohne
gemeinsame Historie.

```bash
docker build -t hoerjetzt-ai-radio .
```

Hört ausschließlich intern auf Port 8091 — kein Port nach außen. Der Bot
spricht den Dienst über das Container-Netz an.

| Variable | Bedeutung |
| --- | --- |
| `HJ_DB_*` | dieselbe Datenbank wie der Bot |
| `HJ_BOT_ID` | trennt mehrere Instanzen |
| `HJ_LLM_OLLAMA_URL` `HJ_LLM_MODEL` | Sprachmodell, optional |

Ohne Sprachmodell liefert der Dienst keine Vorschläge — das AI-Radio spielt
dann einen festen Ersatzmix.
