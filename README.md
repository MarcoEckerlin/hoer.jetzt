# hoer.jetzt — web

Die Weboberfläche, getrennt von `core`. React, gebaut mit Vite, ausgeliefert
von nginx.

```bash
npm install
HJ_CORE_URL=http://localhost:8080 npm run dev     # Entwicklung
npm run build                                     # nach dist/
```

## Warum getrennt

Die bisherige Oberfläche lief **im selben Prozess** wie der Bot — sie rief
`AudioService` direkt auf. Damit ließ sich weder der Bot ohne Oberfläche
starten noch die Oberfläche mehrfach betreiben. Getrennt geht beides; der Weg
zum Bot führt über die REST-Schnittstelle und später über Redis.

## Ein Ursprung, nicht zwei

nginx liefert die Anwendung aus **und** leitet `/api`, `/auth` und die
öffentlichen Seiten an `core` weiter. Der Grund ist die Sitzung: sie hängt an
einem Cookie, und ein Cookie über zwei Ursprünge hinweg mitzuschicken verlangt
CORS mit Ausnahmen. Ein Ursprung für alles ist einfacher und dichter.

## Stand

Fertig: Startseite, Anmeldung, Serverauswahl, Wiedergabe-Ansicht.

Offen: die vierzehn Modulseiten. Sie ziehen schrittweise um; bis dahin bleibt
die alte Oberfläche unter `/dashboard` erreichbar und kann alles. Beide reden
mit denselben Endpunkten — es gibt keinen Stichtag, an dem etwas umspringt.
