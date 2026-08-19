import React, { useEffect, useRef, useState } from "react";
import { api } from "../lib/api.js";
import { Modulseite } from "./rahmen.jsx";
import { SYMBOLE } from "../teile/Symbole.jsx";

/**
 * Die Fernbedienung fuer die Wiedergabe.
 *
 * <p>Der Fortschritt laeuft in der Oberflaeche weiter, statt jede Sekunde
 * nachzufragen: der Bot wird alle fuenf Sekunden gefragt, dazwischen zaehlt der
 * Browser selbst hoch. Das ist der Unterschied zwischen einem Balken, der sich
 * bewegt, und einem, der ruckt.</p>
 *
 * <h2>Suchen, dann waehlen</h2>
 *
 * <p>Frueher spielte die Eingabe sofort den ersten Treffer. Bei einem
 * eindeutigen Titel ging das gut, bei einem haeufigen Namen landete man beim
 * Cover eines Zufallskanals - und merkte es erst, als es lief. Jetzt kommt
 * zuerst eine Trefferliste. Wer den alten Weg will, drueckt Enter: der erste
 * Treffer laeuft dann direkt.</p>
 */
export default function Player({ guildId }) {
    const [zustand, setZustand] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [suche, setSuche] = useState("");
    const [treffer, setTreffer] = useState(null);
    const [sucht, setSucht] = useState(false);
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
    const [technikOffen, setTechnikOffen] = useState(false);

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

    /** Sucht, ohne abzuspielen - das Ergebnis landet in der Vorschau. */
    async function suchen() {
        const begriff = suche.trim();
        if (!begriff) return;
        setSucht(true);
        setMeldung(null);
        try {
            const liste = await api("POST", `/api/dashboard/guilds/${guildId}/player/search`, {
                query: begriff,
                voiceChannelId: null
            });
            setTreffer(liste || []);
        } catch (f) {
            setMeldung(f.message);
            setTreffer(null);
        } finally {
            setSucht(false);
        }
    }

    /**
     * Einen Titel in die Warteschlange geben.
     *
     * <p>Geschickt wird die Adresse des Titels, nicht sein Name: der Bot
     * erkennt eine URL und laedt genau diesen Titel, statt noch einmal zu
     * suchen. Sonst waere die Auswahl in der Vorschau ein Vorschlag, den die
     * zweite Suche wieder verwerfen kann.</p>
     */
    async function abspielen(t) {
        await befehl(`/api/dashboard/guilds/${guildId}/player/play`, {
            query: t.uri || t.title,
            voiceChannelId: null
        });
        setTreffer(null);
        setSuche("");
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
    const warteschlange = zustand.queue || [];
    const restdauer = warteschlange.reduce((summe, q) => summe + (q.durationMs || 0), 0);

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

            <section className="karte-flach">
                <div className="suchzeile">
                    <input
                        className="eingabe"
                        placeholder="Titel, Künstler oder Link…"
                        value={suche}
                        onChange={(e) => setSuche(e.target.value)}
                        onKeyDown={(e) => {
                            // Enter spielt sofort - der schnelle Weg fuer alle,
                            // die genau wissen, was sie wollen.
                            if (e.key === "Enter" && suche.trim()) {
                                befehl(`/api/dashboard/guilds/${guildId}/player/play`, { query: suche.trim(), voiceChannelId: null });
                                setSuche("");
                                setTreffer(null);
                            }
                        }}
                    />
                    <button className="knopf leise" disabled={sucht || !suche.trim()} onClick={suchen}>
                        {SYMBOLE.suche} {sucht ? "sucht…" : "Vorschau"}
                    </button>
                    <button
                        className="knopf"
                        disabled={sendet || !suche.trim()}
                        onClick={() => {
                            befehl(`/api/dashboard/guilds/${guildId}/player/play`, { query: suche.trim(), voiceChannelId: null });
                            setSuche("");
                            setTreffer(null);
                        }}
                    >
                        {SYMBOLE.abspielen} Abspielen
                    </button>
                </div>

                {!zustand.userInVoiceChannel && (
                    <p className="feld-hilfe">
                        Du bist in keinem Sprachkanal. Der Bot folgt dir dorthin — geh erst hinein,
                        sonst weiß er nicht, wo er spielen soll.
                    </p>
                )}

                {treffer && (
                    <div className="trefferliste">
                        <div className="trefferliste-kopf">
                            <strong>{treffer.length} Treffer</strong>
                            <button className="knopf leise klein" onClick={() => setTreffer(null)}>Schließen</button>
                        </div>
                        {treffer.length === 0 && <p className="leise">Nichts gefunden.</p>}
                        {treffer.map((k, i) => (
                            <button
                                className="trefferzeile"
                                key={`${k.identifier}-${i}`}
                                disabled={sendet}
                                onClick={() => abspielen(k)}
                                title={k.uri}
                            >
                                <Bild url={k.artworkUrl} />
                                <span className="trefferzeile-text">
                                    <strong>{k.title}</strong>
                                    <span className="leise">{k.author}</span>
                                </span>
                                <span className="leise">{k.stream ? "Live" : zeit(k.durationMs)}</span>
                                <span className="marke leise">{k.sourceName}</span>
                            </button>
                        ))}
                    </div>
                )}
            </section>

            {/*
              Die laufende Karte mit dem Cover als Hintergrund.
              Das Bild liegt stark abgedunkelt darunter, nicht als Deko: es
              beantwortet auf einen Blick, ob wirklich der gemeinte Titel
              laeuft. Der Text steht auf einer eigenen Schicht darueber,
              damit der Kontrast unabhaengig vom Bild bleibt - ein helles
              Cover hat sonst weisse Schrift auf Weiss.
            */}
            <section className={`karte-flach spieler ${t ? "hat-titel" : ""}`}>
                {t?.artworkUrl && (
                    <div className="spieler-grund" style={{ backgroundImage: `url(${t.artworkUrl})` }} aria-hidden="true" />
                )}
                <div className="spieler-inhalt">
                    {t ? (
                        <>
                            <div className="titelzeile">
                                <Bild url={t.artworkUrl} gross />
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
                        <button
                            className="knopf"
                            disabled={sendet}
                            onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/${zustand.paused ? "resume" : "pause"}`)}
                        >
                            {zustand.paused ? SYMBOLE.abspielen : SYMBOLE.pause}
                            {zustand.paused ? "Fortsetzen" : "Pause"}
                        </button>
                        <button className="knopf leise" disabled={sendet} onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/skip`)}>
                            {SYMBOLE.weiter} Weiter
                        </button>
                        <button className="knopf leise" disabled={sendet} onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/stop`)}>
                            {SYMBOLE.stopp} Stopp
                        </button>
                        <button
                            className={`knopf leise ${zustand.repeatEnabled ? "ist-an" : ""}`}
                            disabled={sendet}
                            aria-pressed={zustand.repeatEnabled}
                            onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/repeat`, { enabled: !zustand.repeatEnabled })}
                        >
                            {SYMBOLE.wiederholen} Wiederholen {zustand.repeatEnabled ? "an" : "aus"}
                        </button>
                        <button
                            className={`knopf leise ${zustand.bassBoostEnabled ? "ist-an" : ""}`}
                            disabled={sendet}
                            aria-pressed={zustand.bassBoostEnabled}
                            onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/bass`, { enabled: !zustand.bassBoostEnabled })}
                        >
                            {SYMBOLE.bass} Bass {zustand.bassBoostEnabled ? "an" : "aus"}
                        </button>
                    </div>

                    <div className="lautstaerke">
                        <label className="feld-titel">
                            {SYMBOLE.lautstaerke} Lautstärke: {lautstaerke ?? zustand.volume} %
                        </label>
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
                        <span className="feld-hilfe">
                            Über 100 % wird digital verstärkt — lauter, aber nicht besser.
                        </span>
                    </div>
                </div>
            </section>

            <section className="karte-flach">
                <div className="karte-kopf">
                    <div>
                        <h2>{SYMBOLE.warteschlange} Warteschlange</h2>
                        <p className="leise">
                            {warteschlange.length === 0
                                ? "Leer — was gespielt wird, kommt aus der Suche oben."
                                : `${warteschlange.length} Titel · noch ${zeit(restdauer)}`}
                        </p>
                    </div>
                </div>

                {warteschlange.map((q, i) => (
                    <div className="warteliste-zeile" key={i}>
                        <span className="warteliste-nummer">{i + 1}</span>
                        <Bild url={q.artworkUrl} />
                        <span className="listenzeile-text">
                            <strong>{q.title}</strong>
                            <span className="leise">{q.author}</span>
                        </span>
                        <span className="leise">{q.stream ? "Live" : zeit(q.durationMs)}</span>
                        {i > 0 && (
                            <button
                                className="knopf leise klein"
                                disabled={sendet}
                                title="Als Nächstes spielen"
                                onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/queue/move`, { fromIndex: i, toIndex: 0 })}
                            >
                                {SYMBOLE.nachOben}
                            </button>
                        )}
                        <button
                            className="knopf leise klein"
                            disabled={sendet}
                            title="Aus der Warteschlange nehmen"
                            onClick={() => befehl(`/api/dashboard/guilds/${guildId}/player/queue/remove`, { index: i })}
                        >
                            {SYMBOLE.entfernen}
                        </button>
                    </div>
                ))}
            </section>

            {/*
              Die Technikkarte stand bisher ganz oben - vor dem Player. Wer die
              Wiedergabe oeffnet, will aber zuerst die Wiedergabe sehen. Jetzt
              steht sie unten und zugeklappt: gebraucht wird sie erst, wenn
              etwas nicht stimmt.
            */}
            {knoten && (
                <section className="karte-flach technik">
                    <button className="technik-kopf" onClick={() => setTechnikOffen((x) => !x)}>
                        <span className="kachel-titel">Technik</span>
                        <span className="leise">
                            {knoten.knoten || "—"}
                            {knoten.knotenStufe ? ` · Stufe ${knoten.knotenStufe}` : ""}
                        </span>
                        <span className={`marke ${knoten.knotenErreichbar ? "ist-gut" : "ist-schlecht"}`}>
                            {knoten.knotenErreichbar ? (knoten.verbunden ? "läuft" : "bereit") : "nicht erreichbar"}
                        </span>
                        <span className="leise">{technikOffen ? "▾" : "▸"}</span>
                    </button>

                    {technikOffen && (
                        <>
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

                            <p className="feld-hilfe">Nur für das Server-Team sichtbar.</p>
                        </>
                    )}
                </section>
            )}
        </Modulseite>
    );
}

/** Cover oder Platzhalter - ein fehlendes Bild soll kein Loch hinterlassen. */
function Bild({ url, gross }) {
    const [kaputt, setKaputt] = useState(false);
    const klasse = gross ? "cover" : "minicover";

    if (!url || kaputt) {
        return <span className={`${klasse} ist-leer`} aria-hidden="true">{SYMBOLE.wiedergabe}</span>;
    }
    return <img className={klasse} src={url} alt="" loading="lazy" onError={() => setKaputt(true)} />;
}

function zeit(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const std = Math.floor(s / 3600);
    const min = Math.floor((s % 3600) / 60);
    const rest = String(s % 60).padStart(2, "0");
    return std > 0 ? `${std}:${String(min).padStart(2, "0")}:${rest}` : `${min}:${rest}`;
}
