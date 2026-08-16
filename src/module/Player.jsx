import React, { useEffect, useRef, useState } from "react";
import { api } from "../lib/api.js";
import { Modulseite } from "./rahmen.jsx";

/**
 * Die Fernbedienung fuer die Wiedergabe.
 *
 * <p>Der Fortschritt laeuft in der Oberflaeche weiter, statt jede Sekunde
 * nachzufragen: der Bot wird alle fuenf Sekunden gefragt, dazwischen zaehlt der
 * Browser selbst hoch. Das ist der Unterschied zwischen einem Balken, der sich
 * bewegt, und einem, der ruckt.</p>
 */
export default function Player({ guildId }) {
    const [zustand, setZustand] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [suche, setSuche] = useState("");
    const [sendet, setSendet] = useState(false);
    const [position, setPosition] = useState(0);
    const geholt = useRef(0);

    /*
     * Die Lautstaerke haelt einen eigenen Zustand.
     *
     * Vorher haing der Regler direkt am Serverzustand, und der wurde alle fuenf
     * Sekunden sowie nach jedem Befehl neu geholt. Wer den Regler losliess,
     * bekam ihn deshalb regelmaessig auf den alten Wert zurueckgeschoben: der
     * Bot braucht einen Moment, bis Lavalink die neue Lautstaerke meldet, und
     * genau in diesem Moment kam die Antwort mit dem alten Wert an.
     *
     * "gesendet" merkt sich, was zuletzt abgeschickt wurde. Erst wenn der
     * Server genau diesen Wert zurueckmeldet, gilt die Sache als erledigt und
     * der Regler folgt wieder dem Server.
     */
    const [lautstaerke, setLautstaerke] = useState(null);
    const gesendet = useRef(null);

    /*
     * Auf welchem Audio-Knoten dieser Server gerade liegt.
     *
     * Eigener Abruf, weil der Endpunkt dem Server-Team vorbehalten ist: wer
     * das Recht nicht hat, bekommt eine 403 - und dann bleibt die Karte
     * einfach weg, statt einen Fehler anzuzeigen. Fuer die Wiedergabe selbst
     * ist die Angabe nicht noetig, fuer die Frage "warum klingt es heute
     * anders" schon.
     */
    const [knoten, setKnoten] = useState(null);

    async function laden() {
        try {
            const d = await api("GET", `/api/dashboard/guilds/${guildId}/player`);
            setZustand(d);
            setPosition(d?.positionMs || 0);

            if (gesendet.current === null) {
                setLautstaerke(d?.volume ?? 100);
            } else if (d?.volume === gesendet.current) {
                // Der Server ist nachgezogen - ab jetzt gilt wieder er.
                gesendet.current = null;
                setLautstaerke(d.volume);
            }
            geholt.current = Date.now();
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }

    useEffect(() => {
        laden();
        const takt = setInterval(laden, 5000);
        return () => clearInterval(takt);
    }, [guildId]);

    useEffect(() => {
        api("GET", `/api/dashboard/guilds/${guildId}/stream`)
            .then(setKnoten)
            .catch(() => setKnoten(null));
    }, [guildId]);

    useEffect(() => {
        if (!zustand || zustand.paused || !zustand.currentTrack) return;
        const takt = setInterval(() => {
            setPosition((zustand.positionMs || 0) + (Date.now() - geholt.current));
        }, 500);
        return () => clearInterval(takt);
    }, [zustand]);

    /**
     * Ein Befehl an den Spieler.
     *
     * <p>Der volle Pfad kommt von der Aufrufstelle statt aus einem Baustein
     * hier drin. Das sieht umstaendlicher aus, hat aber einen Grund: nur so
     * steht die Adresse woertlich im Quelltext, und nur dann findet die
     * Pruefung (endpunkte-pruefen.py) einen Tippfehler, bevor ein Nutzer ihn
     * findet. Genau daran ist "queue/move" mit falschen Feldnamen einmal
     * vorbeigekommen.</p>
     */
    async function befehl(pfad, koerper) {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", pfad, koerper);
            if (antwort?.message) setMeldung(antwort.message);
            await laden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setSendet(false);
        }
    }

    /**
     * Lautstaerke abschicken.
     *
     * <p>Bewusst nicht ueber {@code befehl}: das laedt sofort danach neu, und
     * genau dieses Neuladen hat den Regler zurueckspringen lassen. Hier wird
     * gesendet und dem naechsten regulaeren Abruf ueberlassen, den Wert zu
     * bestaetigen.</p>
     */
    async function lautstaerkeSenden(wert) {
        gesendet.current = wert;
        setLautstaerke(wert);
        try {
            await api("POST", `/api/dashboard/guilds/${guildId}/player/volume`, { volume: wert });
        } catch (f) {
            setMeldung(f.message);
            // Fehlgeschlagen: die Sperre loesen, sonst bliebe der Regler
            // dauerhaft vom Server abgekoppelt.
            gesendet.current = null;
        }
    }

    if (!zustand) {
        return (
            <Modulseite titel="Wiedergabe">
                {fehler ? <div className="notiz notiz-fehler">{fehler}</div> : <div className="ladeschirm"><div className="puls" /></div>}
            </Modulseite>
        );
    }

    const t = zustand.currentTrack;
    const dauer = t?.durationMs || 0;
    const anteil = dauer > 0 ? Math.min(100, (position / dauer) * 100) : 0;

    return (
        <Modulseite
            titel="Wiedergabe"
            hilfe={
                zustand.connected
                    ? `Verbunden mit ${zustand.voiceChannelName}.`
                    : "Der Bot ist in keinem Sprachkanal."
            }
        >
            {meldung && <div className="notiz">{meldung}</div>}

            {knoten && (
                <section className="karte-flach technik">
                    <div className="technik-kopf">
                        <span className="kachel-titel">Technik</span>
                        <span className="leise">Nur für das Server-Team sichtbar</span>
                    </div>

                    <div className="technikzeile">
                        <div>
                            <strong>Audio-Knoten</strong>
                            <span className="leise einfarbig">
                                {knoten.knoten || "—"}
                                {knoten.knotenStufe ? ` · Stufe ${knoten.knotenStufe}` : ""}
                            </span>
                        </div>
                        <span className={`marke ${knoten.knotenErreichbar ? "ist-gut" : "ist-schlecht"}`}>
                            {knoten.knotenErreichbar ? (knoten.verbunden ? "läuft" : "bereit") : "nicht erreichbar"}
                        </span>
                    </div>

                    <div className="technikzeile">
                        <div>
                            <strong>Auslastung des Knotens</strong>
                            <span className="leise">
                                {knoten.wiedergabenAufKnoten === 1
                                    ? "1 Wiedergabe gleichzeitig"
                                    : `${knoten.wiedergabenAufKnoten} Wiedergaben gleichzeitig`}
                            </span>
                        </div>
                        <strong>{knoten.wiedergabenAufKnoten}</strong>
                    </div>

                    <div className="technikzeile">
                        <div>
                            <strong>Stufe dieses Servers</strong>
                            <span className="leise">
                                {knoten.passtZurStufe
                                    ? knoten.serverStufe
                                    : `${knoten.serverStufe} — liegt gerade auf einem Knoten anderer Stufe`}
                            </span>
                        </div>
                        {!knoten.passtZurStufe && <span className="marke ist-warnung">Überlauf</span>}
                    </div>
                </section>
            )}

            <section className="karte-flach">
                <div className="suchzeile">
                    <input
                        className="eingabe"
                        placeholder="Titel, Künstler oder Link…"
                        value={suche}
                        onChange={(e) => setSuche(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === "Enter" && suche.trim()) {
                                befehl(`/api/dashboard/guilds/${guildId}/player/play`, { query: suche.trim(), voiceChannelId: null });
                                setSuche("");
                            }
                        }}
                    />
                    <button
                        className="knopf"
                        disabled={sendet || !suche.trim()}
                        onClick={() => {
                            befehl(`/api/dashboard/guilds/${guildId}/player/play`, { query: suche.trim(), voiceChannelId: null });
                            setSuche("");
                        }}
                    >
                        Abspielen
                    </button>
                </div>
                {!zustand.userInVoiceChannel && (
                    <p className="feld-hilfe">
                        Du bist in keinem Sprachkanal. Der Bot folgt dir dorthin — geh erst hinein,
                        sonst weiß er nicht, wo er spielen soll.
                    </p>
                )}
            </section>

            <section className="karte-flach">
                {t ? (
                    <>
                        <div className="titelzeile">
                            {t.artworkUrl && <img className="cover" src={t.artworkUrl} alt="" />}
                            <div>
                                <strong>{t.title}</strong>
                                <span>{t.author}</span>
                                <span className="leise">
                                    {zustand.playingRadio && zustand.activeRadioName
                                        ? `Radio: ${zustand.activeRadioName}`
                                        : zustand.paused
                                        ? "Pausiert"
                                        : "Spielt gerade"}
                                </span>
                            </div>
                        </div>

                        {!t.stream && dauer > 0 && (
                            <>
                                <div className="balken">
                                    <div className="balken-fuell" style={{ width: `${anteil}%` }} />
                                </div>
                                <div className="zeitzeile">
                                    <span>{zeit(position)}</span>
                                    <span>{zeit(dauer)}</span>
                                </div>
                            </>
                        )}
                    </>
                ) : (
                    <p className="leise">Gerade läuft nichts.</p>
                )}

                <div className="knopfreihe">
                    <button className="knopf" disabled={sendet} onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/${zustand.paused ? "resume" : "pause"}`)}>
                        {zustand.paused ? "Fortsetzen" : "Pause"}
                    </button>
                    <button className="knopf leise" disabled={sendet} onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/skip`)}>Weiter</button>
                    <button className="knopf leise" disabled={sendet} onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/stop`)}>Stopp</button>
                    <button
                        className={`knopf leise ${zustand.repeatEnabled ? "ist-an" : ""}`}
                        disabled={sendet}
                        onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/repeat`, { enabled: !zustand.repeatEnabled })}
                    >
                        Wiederholen {zustand.repeatEnabled ? "an" : "aus"}
                    </button>
                    <button
                        className={`knopf leise ${zustand.bassBoostEnabled ? "ist-an" : ""}`}
                        disabled={sendet}
                        onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/bass`, { enabled: !zustand.bassBoostEnabled })}
                    >
                        Bass {zustand.bassBoostEnabled ? "an" : "aus"}
                    </button>
                </div>

                <div className="lautstaerke">
                    <label className="feld-titel">Lautstärke: {lautstaerke ?? zustand.volume} %</label>
                    <input
                        type="range"
                        min="0"
                        max="150"
                        value={lautstaerke ?? zustand.volume}
                        onChange={(e) => setLautstaerke(Number(e.target.value))}
                        // Erst beim Loslassen senden. Bei jedem Zwischenschritt
                        // zu senden hiesse hundert Aufrufe fuer eine Bewegung -
                        // und der Bot setzt sie alle nacheinander um.
                        onMouseUp={(e) => lautstaerkeSenden(Number(e.target.value))}
                        onTouchEnd={(e) => lautstaerkeSenden(Number(e.target.value))}
                        onKeyUp={(e) => lautstaerkeSenden(Number(e.target.value))}
                    />
                </div>
            </section>

            {(zustand.queue || []).length > 0 && (
                <section className="karte-flach">
                    <h2>Warteschlange ({zustand.queue.length})</h2>
                    <div className="tabelle">
                        {zustand.queue.map((q, i) => (
                            <div className="zeile" key={i}>
                                <span className="leise">{i + 1}</span>
                                <span>{q.title}</span>
                                <span className="leise">{q.author}</span>
                                <span className="listenzeile">
                                    {i > 0 && (
                                        <button
                                            className="knopf leise klein"
                                            disabled={sendet}
                                            onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/queue/move`, { fromIndex: i, toIndex: 0 })}
                                        >
                                            Nach vorn
                                        </button>
                                    )}
                                    <button
                                        className="knopf leise klein"
                                        disabled={sendet}
                                        onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/queue/remove`, { index: i })}
                                    >
                                        Entfernen
                                    </button>
                                </span>
                            </div>
                        ))}
                    </div>
                </section>
            )}
        </Modulseite>
    );
}

function zeit(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const min = Math.floor(s / 60);
    return `${min}:${String(s % 60).padStart(2, "0")}`;
}
