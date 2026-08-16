# Prüfungen am Farbschema

Zwei kleine Skripte, beide ohne Abhängigkeiten und ohne laufende Anwendung.
Sie prüfen das, was sich an einem Farbschema überhaupt objektiv prüfen lässt —
den Rest muss weiterhin jemand ansehen.

```bash
node pruefen/kontrast-pruefen.cjs src/stil.css
node pruefen/token-luecken.cjs src/stil.css \
    ../core/src/main/resources/static/panel.css \
    ../core/src/main/resources/static/public.css
```

Beide geben bei einem Fund den Rückgabewert 1 zurück, lassen sich also in
einen Bauvorgang hängen.

## `kontrast-pruefen.cjs`

Rechnet die WCAG-Kontraste der tatsächlich vorkommenden Farbpaare aus, für
beide Schemata. Die Paare stehen als Liste im Skript — ein Browser könnte sie
selbst ermitteln, dafür bräuchte es eine laufende Anwendung.

Gefunden hat es unter anderem, dass `opacity` auf ohnehin gedämpftem Text im
hellen Modus auf Kontrast 2.34 kam. Solche Stellen fallen beim Ansehen nicht
auf, weil man den Text ja kennt.

## `token-luecken.cjs`

Sucht Farbtoken, die nur im dunklen Schema gesetzt sind. Das ist der häufigste
Grund, warum ein heller Modus „komisch" aussieht: die meisten Farben kippen,
ein paar bleiben stehen — und das sieht man einzeln nie, sondern nur als
Gesamteindruck.

Es kennt beide Schreibweisen im Bestand: `:root` plus
`:root[data-theme="light"]` (React-Oberfläche, `public.css`) und
`html[data-theme="…"]` (`panel.css`).
