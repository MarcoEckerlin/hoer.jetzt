import React, { useEffect, useRef, useState } from "react";
import { anwenden, gespeicherterModus, systemBeobachten } from "../lib/farbschema.js";

/**
 * Der Umschalter fuer das Farbschema.
 *
 * <p>Drei Knoepfe statt eines Umschalters mit zwei Zustaenden: „System" ist
 * eine eigene Aussage und nicht dasselbe wie „dunkel". Wer sein Betriebssystem
 * abends auf hell umstellt, will die Seite mitziehen sehen - mit einem
 * Zweifach-Schalter geht diese Auskunft verloren, sobald man einmal
 * angetippt hat.</p>
 */
export default function Farbschema({ klein }) {
    const [modus, setModus] = useState(gespeicherterModus);
    const jetzigen = useRef(modus);
    jetzigen.current = modus;

    useEffect(() => {
        anwenden(modus);
    }, [modus]);

    useEffect(() => systemBeobachten(() => jetzigen.current), []);

    return (
        <div className={`farbschema ${klein ? "ist-klein" : ""}`} role="group" aria-label="Farbschema">
            {[["system", "System"], ["dark", "Dunkel"], ["light", "Hell"]].map(([wert, titel]) => (
                <button
                    key={wert}
                    type="button"
                    className="farbschema-knopf"
                    aria-pressed={modus === wert}
                    onClick={() => setModus(wert)}
                >
                    {titel}
                </button>
            ))}
        </div>
    );
}
