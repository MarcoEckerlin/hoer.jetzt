import React, { useState } from "react";
import { api } from "../lib/api.js";
import { Feld, Text, useEntwurf } from "../teile/felder.jsx";
import EmbedEditor from "../teile/EmbedEditor.jsx";
import { Modulseite } from "./rahmen.jsx";

/**
 * Nachrichtenvorlagen: einmal bauen, in mehreren Modulen benutzen.
 *
 * <p>Der Punkt der Vorlage ist, dass sie an <em>einer</em> Stelle liegt. Wer
 * dieselbe Gestaltung in Willkommen, Verify und drei Ticket-Tafeln kopiert hat,
 * pflegt sie ab dem ersten Farbwechsel an fuenf Stellen - und findet die fuenfte
 * erst, wenn jemand sie meldet.</p>
 */
export default function Vorlagen({ guildId, konfig, neuLaden }) {
    const { entwurf, setEntwurf, geaendert, verwerfen } = useEntwurf(
        (konfig.embedVorlagen || []).map((v) => ({ ...v }))
    );
    const [gewaehlt, setGewaehlt] = useState(0);
    const [sendet, setSendet] = useState(false);
    const [meldung, setMeldung] = useState(null);

    async function speichern() {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", `/api/dashboard/guilds/${guildId}/embed-vorlagen`, entwurf);
            setMeldung(antwort?.message || "Gespeichert.");
            await neuLaden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setSendet(false);
        }
    }

    function aendern(name, wert) {
        setEntwurf(entwurf.map((v, i) => (i === gewaehlt ? { ...v, [name]: wert } : v)));
    }

    const aktuell = entwurf[gewaehlt];

    return (
        <Modulseite
            titel="Nachrichtenvorlagen"
            hilfe="Gestaltete Nachrichten, die mehrere Module benutzen können — Willkommen, Verify, Reaktionsrollen, Tickets."
        >
            {meldung && <div className="notiz">{meldung}</div>}

            <div className="vorlagen">
                <aside className="vorlagenliste">
                    {entwurf.map((v, i) => (
                        <button
                            key={v.id || i}
                            className={`vorlagenknopf ${i === gewaehlt ? "ist-aktiv" : ""}`}
                            onClick={() => setGewaehlt(i)}
                        >
                            {v.name || "Ohne Namen"}
                        </button>
                    ))}
                    <button
                        className="knopf leise klein"
                        onClick={() => {
                            setEntwurf([...entwurf, { id: null, name: "Neue Vorlage", farbe: "#5865F2", felder: [], zusatzBilder: [] }]);
                            setGewaehlt(entwurf.length);
                        }}
                    >
                        Vorlage anlegen
                    </button>
                </aside>

                <div className="vorlageninhalt">
                    {!aktuell ? (
                        <p className="leise">Noch keine Vorlage. Leg eine an — sie steht dann in allen Modulen zur Wahl.</p>
                    ) : (
                        <>
                            <div className="karte-kopf">
                                <Feld
                                    titel="Name"
                                    hilfe="Nur zur Wiedererkennung in den Modulen; er taucht in Discord nicht auf."
                                    kind={<Text wert={aktuell.name} setzen={(x) => aendern("name", x)} />}
                                />
                                <button
                                    className="knopf leise klein"
                                    onClick={() => {
                                        setEntwurf(entwurf.filter((_, i) => i !== gewaehlt));
                                        setGewaehlt(0);
                                    }}
                                >
                                    Vorlage löschen
                                </button>
                            </div>

                            <EmbedEditor embed={aktuell} setzen={(x) => setEntwurf(entwurf.map((v, i) => (i === gewaehlt ? { ...x, id: v.id, name: v.name } : v)))} />
                        </>
                    )}
                </div>
            </div>

            {geaendert && (
                <div className="speicherleiste ist-offen">
                    <span>Ungespeicherte Änderungen.</span>
                    <button className="knopf leise" onClick={verwerfen} disabled={sendet}>Verwerfen</button>
                    <button className="knopf" onClick={speichern} disabled={sendet}>
                        {sendet ? "Speichert…" : "Speichern"}
                    </button>
                </div>
            )}
        </Modulseite>
    );
}
