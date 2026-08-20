import React, { useEffect, useState } from "react";
import { api } from "../lib/api.js";

/**
 * Die Zahlen eines Discord-Servers.
 *
 * <p>Ein Baustein fuer zwei Orte: die Serveruebersicht im Betrieb und die
 * Uebersicht im Nutzer-Dashboard. Sie unterscheiden sich nur im Endpunkt -
 * der Betrieb fragt ueber die Verwaltung, der Serverbetreiber ueber sein
 * eigenes Dashboard. Zweimal dieselbe Darstellung zu schreiben hiesse, jede
 * spaetere Aenderung an zwei Stellen zu machen und die zweite zu vergessen.</p>
 *
 * <p>Was nicht da ist, wird nicht erfunden: hatte ein Server noch nie
 * Wiedergabe, steht das als Satz da und nicht als Reihe von Nullen. Eine Null
 * neben "Hoerzeit" liest sich wie ein Messwert; sie ist aber die Abwesenheit
 * einer Messung.</p>
 */
export default function Serverstatistik({ guildId, pfad }) {
    const [zahlen, setZahlen] = useState(null);
    const [fehler, setFehler] = useState(null);

    useEffect(() => {
        let abgemeldet = false;
        setZahlen(null);
        setFehler(null);
        api("GET", pfad || `/api/dashboard/guilds/${guildId}/stats`)
            .then((d) => { if (!abgemeldet) setZahlen(d); })
            .catch((f) => { if (!abgemeldet) setFehler(f.message); });
        // Die Zeile laesst sich zuklappen, waehrend die Antwort unterwegs ist.
        return () => { abgemeldet = true; };
    }, [guildId]);

    if (fehler) return <p className="feld-hilfe">Zahlen nicht abrufbar: {fehler}</p>;
    if (!zahlen) return <p className="feld-hilfe leise">Zahlen werden geladen…</p>;

    const nie = zahlen.sitzungen30d === 0 && zahlen.titel30d === 0;

    return (
        <div className="serverstatistik">
            <div className="listenzeile listenzeile-kopf">
                <div className="listenzeile-text">
                    <h2>Statistiken</h2>
                    <span className="leise">
                        Letzte 30 Tage
                        {zahlen.zuletztAktiv
                            ? ` · zuletzt aktiv am ${zahlen.zuletztAktiv.slice(0, 10)}`
                            : " · noch nie Wiedergabe"}
                    </span>
                </div>
            </div>

            {nie ? (
                <p className="feld-hilfe">
                    In den letzten 30 Tagen lief hier nichts.
                    {zahlen.eigeneSender > 0
                        ? ` Der Server hat ${zahlen.eigeneSender} eigene${zahlen.eigeneSender === 1 ? "n" : ""} Sender angelegt.`
                        : ""}
                </p>
            ) : (
                <>
                    <div className="statgitter">
                        <Zahl titel="Hörzeit" wert={zahlen.hoerzeit30d} />
                        <Zahl titel="Hörer" wert={zahlen.hoerer30d} hinweis="eindeutig, pseudonymisiert" />
                        <Zahl titel="Titel gespielt" wert={zahlen.titel30d} />
                        <Zahl titel="Sitzungen" wert={zahlen.sitzungen30d} />
                        <Zahl titel="Eigene Sender" wert={zahlen.eigeneSender} />
                    </div>

                    <Aufteilung zahlen={zahlen} />

                    {(zahlen.meistgehoert || []).length > 0 && (
                        <div className="tabelle">
                            <div className="zeile kopfzeile">
                                <span>Meistgehört</span><span>Interpret</span><span>Hörzeit</span><span>Hörer</span>
                            </div>
                            {zahlen.meistgehoert.map((e, i) => (
                                <div className="zeile" key={`${e.titel}:${i}`}>
                                    <span>{e.titel}</span>
                                    <span className="leise">{e.interpret || "—"}</span>
                                    <span>{e.hoerzeit}</span>
                                    <span>{e.hoerer}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </>
            )}
        </div>
    );
}

function Zahl({ titel, wert, hinweis }) {
    return (
        <div className="statkachel">
            <span className="statkachel-titel">{titel}</span>
            <strong>{typeof wert === "number" ? wert.toLocaleString("de-DE") : wert}</strong>
            {hinweis && <span className="leise">{hinweis}</span>}
        </div>
    );
}

/**
 * Wofuer die Hoerzeit draufging.
 *
 * <p>Ein Balken statt dreier Zahlen: die Frage ist nicht "wie viele Sekunden
 * Radio", sondern "wird der Server fuer Radio oder fuer Musik benutzt". Ein
 * Verhaeltnis beantwortet man mit einer Laenge, nicht mit einer Zahl.</p>
 */
function Aufteilung({ zahlen }) {
    const teile = [
        { name: "Musik", wert: zahlen.musikSekunden30d, klasse: "ist-musik" },
        { name: "Radio", wert: zahlen.radioSekunden30d, klasse: "ist-radio" },
        { name: "KI-Radio", wert: zahlen.aiRadioSekunden30d, klasse: "ist-ai" }
    ].filter((t) => t.wert > 0);

    const summe = teile.reduce((s, t) => s + t.wert, 0);
    if (summe === 0) return null;

    return (
        <div className="statbalken-block">
            <div className="statbalken" role="img"
                 aria-label={teile.map((t) => `${t.name} ${Math.round((t.wert / summe) * 100)} Prozent`).join(", ")}>
                {teile.map((t) => (
                    <span key={t.name} className={t.klasse} style={{ width: `${(t.wert / summe) * 100}%` }} />
                ))}
            </div>
            <div className="statbalken-schrift">
                {teile.map((t) => (
                    <span key={t.name}>
                        <i className={t.klasse} /> {t.name} {Math.round((t.wert / summe) * 100)} %
                    </span>
                ))}
            </div>
        </div>
    );
}
