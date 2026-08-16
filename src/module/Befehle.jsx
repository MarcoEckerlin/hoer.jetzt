import React, { useState } from "react";
import { api } from "../lib/api.js";
import { Modulseite } from "./rahmen.jsx";

/**
 * Slash-Befehle je Server ein- und ausschalten.
 *
 * <p>Kein Entwurf und keine Speicherleiste: jeder Schalter wirkt sofort. Bei
 * einer Liste aus vierzig Einzelschaltern ist "Speichern nicht vergessen" die
 * schlechtere Bedienung - man klickt drei Haken und wechselt die Seite.</p>
 */
export default function Befehle({ guildId, konfig, neuLaden }) {
    const [laeuft, setLaeuft] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [suche, setSuche] = useState("");

    const befehle = konfig.commands || [];
    const gefiltert = suche
        ? befehle.filter(
              (b) =>
                  b.name.toLowerCase().includes(suche.toLowerCase()) ||
                  (b.description || "").toLowerCase().includes(suche.toLowerCase())
          )
        : befehle;

    const gruppen = {};
    gefiltert.forEach((b) => {
        const k = b.category || "Sonstiges";
        (gruppen[k] = gruppen[k] || []).push(b);
    });

    async function umschalten(b) {
        setLaeuft(b.name);
        setMeldung(null);
        try {
            await api("POST", `/api/dashboard/guilds/${guildId}/commands/${encodeURIComponent(b.name)}`, {
                enabled: !b.enabled
            });
            await neuLaden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setLaeuft(null);
        }
    }

    return (
        <Modulseite
            titel="Befehle"
            hilfe="Was auf diesem Server benutzt werden darf. Ein abgeschalteter Befehl antwortet mit einem Hinweis, statt zu wirken."
            kopfzusatz={
                <input
                    className="eingabe eingabe-suche"
                    placeholder="Befehl suchen…"
                    value={suche}
                    onChange={(e) => setSuche(e.target.value)}
                />
            }
        >
            {meldung && <div className="notiz notiz-fehler">{meldung}</div>}

            {Object.entries(gruppen).map(([kategorie, liste]) => (
                <section className="karte-flach" key={kategorie}>
                    <h2>{kategorie}</h2>
                    <div className="befehlsliste">
                        {liste.map((b) => (
                            <button
                                key={b.name}
                                className={`befehl ${b.enabled ? "ist-an" : ""}`}
                                onClick={() => umschalten(b)}
                                disabled={laeuft === b.name}
                            >
                                <code>/{b.name}</code>
                                <span className="leise">{b.description}</span>
                                <span className="befehl-zustand">{b.enabled ? "an" : "aus"}</span>
                            </button>
                        ))}
                    </div>
                </section>
            ))}

            {gefiltert.length === 0 && <p className="leise">Nichts gefunden.</p>}
        </Modulseite>
    );
}
