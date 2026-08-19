import React, { useState } from "react";
import { gewaehlt, setzen } from "../lib/farbschema.js";

/**
 * Der Umschalter fuer hell und dunkel.
 *
 * <p>Drei Knoepfe statt eines Kippschalters: "System" ist eine eigene Wahl
 * und nicht dasselbe wie "gerade zufaellig hell". Wer sie trifft, will, dass
 * die Seite mitzieht, wenn das Geraet abends umschaltet - ein Kippschalter
 * kann das nicht ausdruecken.</p>
 */
export default function Farbschema({ klein }) {
    const [wahl, setWahl] = useState(gewaehlt);

    function waehlen(neu) {
        setzen(neu);
        setWahl(neu);
    }

    return (
        <div className={`farbschema ${klein ? "ist-klein" : ""}`} role="group" aria-label="Farbschema">
            {[["system", "System"], ["hell", "Hell"], ["dunkel", "Dunkel"]].map(([id, titel]) => (
                <button
                    key={id}
                    type="button"
                    className="farbschema-knopf"
                    aria-pressed={wahl === id}
                    onClick={() => waehlen(id)}
                >
                    {titel}
                </button>
            ))}
        </div>
    );
}
