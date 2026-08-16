import React, { useEffect, useRef, useState } from "react";

/**
 * Die Bausteine, aus denen jede Modulseite besteht.
 *
 * <p>An einer Stelle, nicht in vierzehn. Die alte Oberflaeche hatte jedes Feld
 * in jeder Seite noch einmal aufgeschrieben - deshalb sah eine Rollenauswahl im
 * Verify-Modul anders aus als im Willkommen-Modul, und ein Fehler in der einen
 * war in der anderen nicht behoben.</p>
 */

/** Beschriftetes Feld mit Erklaerung darunter. */
export function Feld({ titel, hilfe, kind, breit }) {
    return (
        <div className={`feld ${breit ? "ist-breit" : ""}`}>
            <label className="feld-titel">{titel}</label>
            {kind}
            {hilfe && <p className="feld-hilfe">{hilfe}</p>}
        </div>
    );
}

/** Ein/Aus. Bewusst gross genug, um ihn auf dem Handy zu treffen. */
export function Schalter({ an, setzen, titel, hilfe }) {
    return (
        <label className="schalter">
            <input type="checkbox" checked={!!an} onChange={(e) => setzen(e.target.checked)} />
            <span className="schalter-bahn"><span className="schalter-knopf" /></span>
            <span className="schalter-text">
                <strong>{titel}</strong>
                {hilfe && <span className="feld-hilfe">{hilfe}</span>}
            </span>
        </label>
    );
}

export function Text({ wert, setzen, platzhalter, typ = "text", ...rest }) {
    return (
        <input
            className="eingabe"
            type={typ}
            value={wert ?? ""}
            placeholder={platzhalter}
            onChange={(e) => setzen(e.target.value)}
            {...rest}
        />
    );
}

export function Zahl({ wert, setzen, min, max, platzhalter }) {
    return (
        <input
            className="eingabe"
            type="number"
            min={min}
            max={max}
            placeholder={platzhalter}
            value={wert ?? ""}
            // Leer bleibt leer und wird nicht stillschweigend zur 0: der
            // Unterschied zwischen "nicht gesetzt" und "null" entscheidet in
            // mehreren Modulen ueber das Verhalten.
            onChange={(e) => setzen(e.target.value === "" ? null : Number(e.target.value))}
        />
    );
}

export function Mehrzeilig({ wert, setzen, platzhalter, zeilen = 4 }) {
    return (
        <textarea
            className="eingabe"
            rows={zeilen}
            placeholder={platzhalter}
            value={wert ?? ""}
            onChange={(e) => setzen(e.target.value)}
        />
    );
}

/** Einzelauswahl aus Kanaelen, Kategorien oder Rollen. */
export function Auswahl({ wert, setzen, liste, leerText = "— nicht gesetzt —", praefix = "" }) {
    return (
        <select className="eingabe" value={wert ?? ""} onChange={(e) => setzen(e.target.value || null)}>
            <option value="">{leerText}</option>
            {liste.map((e) => (
                <option key={e.id} value={e.id}>{praefix}{e.name}</option>
            ))}
        </select>
    );
}

/**
 * Mehrere Rollen auswaehlen.
 *
 * <p>Als Liste mit Haekchen statt als Mehrfach-Select: ein natives
 * {@code multiple} verlangt Strg-Klick, und wer das nicht weiss, loescht mit
 * dem naechsten Klick seine ganze Auswahl, ohne zu verstehen warum.</p>
 */
export function Rollenwahl({ werte, setzen, liste, leerText = "Keine Rolle ausgewählt." }) {
    const [suche, setSuche] = useState("");
    const gewaehlt = werte || [];
    const sichtbar = suche
        ? liste.filter((r) => r.name.toLowerCase().includes(suche.toLowerCase()))
        : liste;

    function umschalten(id) {
        setzen(gewaehlt.includes(id) ? gewaehlt.filter((x) => x !== id) : [...gewaehlt, id]);
    }

    return (
        <div className="wahlkasten">
            {liste.length > 8 && (
                <input
                    className="eingabe eingabe-suche"
                    placeholder="Rolle suchen…"
                    value={suche}
                    onChange={(e) => setSuche(e.target.value)}
                />
            )}
            <div className="wahlliste">
                {sichtbar.map((r) => (
                    <label key={r.id} className={`wahlzeile ${gewaehlt.includes(r.id) ? "ist-gewaehlt" : ""}`}>
                        <input
                            type="checkbox"
                            checked={gewaehlt.includes(r.id)}
                            onChange={() => umschalten(r.id)}
                        />
                        <span>@{r.name}</span>
                    </label>
                ))}
                {sichtbar.length === 0 && <p className="feld-hilfe">Nichts gefunden.</p>}
            </div>
            {gewaehlt.length === 0 && <p className="feld-hilfe">{leerText}</p>}
        </div>
    );
}

/** Farbe als Waehler und als Hex-Feld nebeneinander. */
export function Farbe({ wert, setzen }) {
    const hex = /^#[0-9a-fA-F]{6}$/.test(wert || "") ? wert : "#5865f2";
    return (
        <div className="farbfeld">
            <input type="color" value={hex} onChange={(e) => setzen(e.target.value)} />
            <input
                className="eingabe"
                value={wert ?? ""}
                placeholder="#5865F2"
                onChange={(e) => setzen(e.target.value)}
            />
        </div>
    );
}

/**
 * Die Speicherleiste.
 *
 * <p>Sie erscheint erst, wenn sich etwas geaendert hat, und bleibt dann am
 * unteren Rand stehen. Ein Speichern-Knopf, den man erst nach dem Scrollen
 * findet, ist der Grund, warum Aenderungen verlorengehen.</p>
 */
export function Speicherleiste({ geaendert, sendet, speichern, verwerfen, meldung }) {
    if (!geaendert && !meldung) return null;
    return (
        <div className={`speicherleiste ${geaendert ? "ist-offen" : ""}`}>
            <span>{meldung || "Ungespeicherte Änderungen."}</span>
            {geaendert && (
                <>
                    <button className="knopf leise" onClick={verwerfen} disabled={sendet}>Verwerfen</button>
                    <button className="knopf" onClick={speichern} disabled={sendet}>
                        {sendet ? "Speichert…" : "Speichern"}
                    </button>
                </>
            )}
        </div>
    );
}

/**
 * Haelt einen Entwurf des Moduls und meldet, ob er vom gespeicherten Stand
 * abweicht.
 *
 * <p>Der Vergleich laeuft ueber JSON. Das ist grob, aber es fragt genau das
 * Richtige: hat sich am Ergebnis etwas geaendert? Ein Feld anzuklicken und
 * wieder zurueckzusetzen soll nicht als Aenderung gelten.</p>
 */
export function useEntwurf(anfang) {
    const [entwurf, setEntwurf] = useState(anfang);
    const original = useRef(JSON.stringify(anfang));

    useEffect(() => {
        // Der Server hat neu geladen (anderer Server gewaehlt, oder nach dem
        // Speichern). Entwurf mitziehen, sonst zeigt die Seite alte Werte.
        setEntwurf(anfang);
        original.current = JSON.stringify(anfang);
    }, [JSON.stringify(anfang)]);

    const geaendert = JSON.stringify(entwurf) !== original.current;

    function setzeFeld(name, wert) {
        setEntwurf((alt) => ({ ...alt, [name]: wert }));
    }

    function verwerfen() {
        setEntwurf(JSON.parse(original.current));
    }

    return { entwurf, setEntwurf, setzeFeld, geaendert, verwerfen };
}
