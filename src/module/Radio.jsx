import React, { useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Modulseite } from "./rahmen.jsx";

/**
 * Radiosender.
 *
 * <p>Eigene Seite statt eines Reiters in der Wiedergabe: die Senderliste ist
 * lang, die Fernbedienung ist es nicht. Zusammengelegt haette eines von beiden
 * gescrollt werden muessen.</p>
 *
 * <p>Die Liste wird <em>mit</em> Server-Bezug geholt. Ohne ihn laesst der Bot
 * das AI-Radio bewusst weg - die Freigabe gilt je Server, und ohne zu wissen um
 * welchen es geht, waere jede Antwort geraten.</p>
 */
export default function Radio({ guildId, konfig }) {
    const [sender, setSender] = useState([]);
    const [laeuft, setLaeuft] = useState(null);
    const [aktiv, setAktiv] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [suche, setSuche] = useState("");

    useEffect(() => {
        api("GET", `/api/dashboard/radio/stations?guildId=${guildId}`)
            .then((d) => setSender(d || []))
            .catch((f) => setFehler(f.message));

        api("GET", `/api/dashboard/guilds/${guildId}/player`)
            .then((d) => setAktiv(d?.playingRadio ? d.activeRadioName : null))
            .catch(() => {});
    }, [guildId]);

    async function starten(s) {
        setLaeuft(s.id);
        setMeldung(null);
        try {
            const antwort = await api("POST", `/api/dashboard/guilds/${guildId}/player/radio`, {
                radioId: s.id,
                voiceChannelId: null
            });
            setMeldung(antwort?.message || `${s.name} läuft.`);
            if (antwort?.success !== false) setAktiv(s.name);
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setLaeuft(null);
        }
    }

    const gefiltert = suche
        ? sender.filter((s) => s.name.toLowerCase().includes(suche.toLowerCase()))
        : sender;

    return (
        <Modulseite
            titel="Radio"
            hilfe="Ein Sender läuft dauerhaft, bis jemand ihn stoppt — keine Warteschlange, kein Ende."
            kopfzusatz={
                sender.length > 8 && (
                    <input
                        className="eingabe eingabe-suche"
                        placeholder="Sender suchen…"
                        value={suche}
                        onChange={(e) => setSuche(e.target.value)}
                    />
                )
            }
        >
            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && <div className="notiz">{meldung}</div>}
            {aktiv && <div className="notiz">Gerade läuft <strong>{aktiv}</strong>.</div>}

            {!konfig.entitlements?.aiRadio && (
                <p className="feld-hilfe">
                    Das AI-Radio ist für diesen Server nicht freigeschaltet und fehlt deshalb in
                    der Liste. Die Freigabe erteilt ein Bot-Administrator.
                </p>
            )}

            <section className="karte-flach">
                <div className="senderliste">
                    {gefiltert.map((s) => (
                        <button
                            key={s.id}
                            className={`sender ${aktiv === s.name ? "ist-an" : ""}`}
                            onClick={() => starten(s)}
                            disabled={laeuft !== null}
                        >
                            <strong>{s.name}</strong>
                            {laeuft === s.id && <span className="leise">verbindet…</span>}
                        </button>
                    ))}
                </div>

                {sender.length === 0 && !fehler && (
                    <p className="leise">
                        Keine Sender eingetragen. Sie werden im Adminbereich gepflegt und gelten
                        für die ganze Instanz.
                    </p>
                )}
                {sender.length > 0 && gefiltert.length === 0 && <p className="leise">Nichts gefunden.</p>}
            </section>

            <p className="feld-hilfe">
                Zum Beenden die <a href={`#/server/${guildId}/player`}>Wiedergabe</a> öffnen und dort
                auf Stopp — ein Sender endet nicht von selbst.
            </p>
        </Modulseite>
    );
}
