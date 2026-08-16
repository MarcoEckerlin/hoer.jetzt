import React, { useEffect, useState } from "react";
import { api } from "../lib/api.js";

/**
 * Die Audio-Knoten (Lavalink).
 *
 * <p>Zeigt Auslastung und Belegung und bietet die drei Eingriffe, die im
 * Betrieb wirklich vorkommen: Knotentabelle neu einlesen, einen einzelnen
 * Knoten neu verbinden, und die Server neu verteilen. Alles drei ist
 * absichtlich ein Knopf mit Rueckmeldung und kein stiller Aufruf - wer eine
 * Verbindung kappt, will wissen, ob es geklappt hat.</p>
 */
export default function Knoten() {
    const [knoten, setKnoten] = useState([]);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [laedt, setLaedt] = useState(true);
    const [beschaeftigt, setBeschaeftigt] = useState(null);
    const [offen, setOffen] = useState({});

    async function laden() {
        try {
            setKnoten((await api("GET", "/api/admin/audio/nodes")) || []);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        } finally {
            setLaedt(false);
        }
    }

    useEffect(() => {
        laden();
        const takt = setInterval(laden, 15000);
        return () => clearInterval(takt);
    }, []);

    async function aktion(schluessel, pfad) {
        setBeschaeftigt(schluessel);
        setMeldung(null);
        try {
            const antwort = await api("POST", pfad);
            setMeldung(antwort?.message || "Erledigt.");
            await laden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setBeschaeftigt(null);
        }
    }

    if (laedt) return <div className="ladeschirm"><div className="puls" /></div>;

    return (
        <>
            <header className="modulkopf"><div>
                <h1>Audio-Knoten</h1>
                <p>
                    {knoten.filter((k) => k.erreichbar).length} von {knoten.length} erreichbar,{" "}
                    {knoten.reduce((s, k) => s + (k.spielend || 0), 0)} spielen gerade.
                </p>
            </div></header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}

            <div className="listenzeile">
                <button
                    className="knopf"
                    disabled={beschaeftigt !== null}
                    onClick={() => aktion("neu", "/api/admin/actions/reload-audio-nodes")}
                >
                    {beschaeftigt === "neu" ? "…" : "Knotentabelle neu einlesen"}
                </button>
                <button
                    className="knopf leise"
                    disabled={beschaeftigt !== null}
                    onClick={() => aktion("verteilen", "/api/admin/actions/rebalance-audio")}
                >
                    {beschaeftigt === "verteilen" ? "…" : "Server neu verteilen"}
                </button>
            </div>
            {meldung && <div className="notiz">{meldung}</div>}

            {knoten.map((k) => (
                <section className={`karte-flach ${k.erreichbar ? "" : "ist-still"}`} key={k.name}>
                    <div className="karte-kopf">
                        <h2>
                            <span className={`ampel ${k.erreichbar ? "ist-gut" : "ist-weg"}`} />
                            {k.name}
                            <span className={`marke ${k.stufe === "premium" ? "gold" : ""}`}>{k.stufe}</span>
                        </h2>
                        <button
                            className="knopf leise klein"
                            disabled={beschaeftigt !== null}
                            onClick={() => aktion(k.name, `/api/admin/audio/nodes/${encodeURIComponent(k.name)}/reconnect`)}
                        >
                            {beschaeftigt === k.name ? "…" : "Neu verbinden"}
                        </button>
                    </div>

                    <p className="leise einfarbig">{k.adresse}</p>

                    <div className="kachelreihe">
                        <Wert titel="Server" wert={`${k.gesamt} / ${k.obergrenze || "∞"}`} />
                        <Wert titel="Spielt" wert={k.spielend} />
                        <Wert titel="CPU" wert={`${Math.round((k.cpuLast || 0) * 100)} %`} />
                        <Wert titel="Strafpunkte" wert={k.strafpunkte} />
                        <Wert titel="Laufzeit" wert={dauer(k.laufzeitSekunden)} />
                    </div>

                    <div className="balken">
                        <div
                            className={`balken-fuell ${(k.cpuLast || 0) > 0.85 ? "heiss" : ""}`}
                            style={{ width: `${Math.min(100, Math.round((k.cpuLast || 0) * 100))}%` }}
                        />
                    </div>

                    {(k.server || []).length > 0 && (
                        <>
                            <button
                                className="knopf leise klein"
                                onClick={() => setOffen({ ...offen, [k.name]: !offen[k.name] })}
                            >
                                {offen[k.name] ? "Server ausblenden" : `${k.server.length} Server anzeigen`}
                            </button>
                            {offen[k.name] && (
                                <div className="tabelle">
                                    {k.server.map((s) => (
                                        <div className="zeile" key={s.guildId}>
                                            <span>{s.name || s.guildId}</span>
                                            <span>{s.stufe}</span>
                                            <span>{s.spielt ? (s.titel || "spielt") : "still"}</span>
                                            <span>
                                                {s.passtZurStufe ? "" : (
                                                    <span className="marke warn" title="Liegt auf einem Knoten anderer Stufe – meist ein Ausweichen nach einem Ausfall.">
                                                        ausgewichen
                                                    </span>
                                                )}
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </section>
            ))}

            {knoten.length === 0 && (
                <div className="notiz">
                    Kein Knoten eingetragen. Knoten stehen in der Datenbank; der Bot liest die
                    Tabelle regelmäßig neu ein – ein Neustart ist dafür nicht nötig.
                </div>
            )}
        </>
    );
}

function Wert({ titel, wert }) {
    return (
        <div className="kachel">
            <span className="kachel-titel">{titel}</span>
            <strong>{wert}</strong>
        </div>
    );
}

function dauer(sekunden) {
    if (!sekunden) return "—";
    const tage = Math.floor(sekunden / 86400);
    if (tage > 0) return `${tage} d`;
    const stunden = Math.floor(sekunden / 3600);
    if (stunden > 0) return `${stunden} h`;
    return `${Math.floor(sekunden / 60)} min`;
}
