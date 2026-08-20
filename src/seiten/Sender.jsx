import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Feld, Text } from "../teile/felder.jsx";
import Bildfeld from "../teile/Bildfeld.jsx";
import { Senderbild } from "../module/Radio.jsx";

/**
 * Globale Radiosender.
 *
 * <p>Was hier steht, hoert jeder Server. Deshalb liegt die Seite im Betrieb
 * und nicht im Serverpanel - ein Serveradmin pflegt seine eigenen Sender unter
 * <code>Radio</code>, und die sieht auch nur er.</p>
 *
 * <p>Technisch ist es dieselbe Tabelle; der Unterschied ist eine leere
 * <code>guild_id</code>. Die Trennung sitzt im Bot, nicht hier: der Endpunkt
 * fuer globale Sender verlangt Schreibrechte im Betrieb, der fuer eigene
 * schreibt die Server-ID aus dem Pfad und kann gar nichts Globales anlegen.</p>
 */
export default function Sender() {
    const [liste, setListe] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [entwurf, setEntwurf] = useState(null);
    const [sendet, setSendet] = useState(false);

    const laden = useCallback(async () => {
        try {
            setListe((await api("GET", "/api/admin/radio")) || []);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, []);

    useEffect(() => { laden(); }, [laden]);

    async function speichern() {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/radio", {
                id: entwurf.id ?? null,
                name: entwurf.name,
                url: entwurf.url,
                logoUrl: entwurf.logoUrl
            });
            setMeldung({ art: "gut", text: antwort?.message || "Gespeichert." });
            setEntwurf(null);
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setSendet(false);
        }
    }

    async function entfernen(s) {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("DELETE", `/api/admin/radio/${s.id}`);
            setMeldung({ art: "gut", text: antwort?.message || "Entfernt." });
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setSendet(false);
        }
    }

    if (!liste && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>Globale Sender</h1>
                    <p>
                        Stehen auf jedem Server in der Radioliste. Eigene Sender legt jeder
                        Server selbst an — die tauchen hier nicht auf.
                    </p>
                </div>
                <div className="kopf-knoepfe">
                    <button
                        className="knopf leise klein"
                        onClick={() => setEntwurf({ id: null, name: "", url: "", logoUrl: "" })}
                        disabled={entwurf !== null}
                    >
                        Hinzufügen
                    </button>
                </div>
            </header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && (
                <div className={`notiz ${meldung.art === "schlecht" ? "notiz-fehler" : "notiz-gut"}`}>
                    {meldung.text}
                </div>
            )}

            <section className="karte-flach">
                {(liste || []).map((s) => (
                    <div className="listenzeile" key={s.id}>
                        <Senderbild sender={s} />
                        <div className="listenzeile-text">
                            <strong>{s.name}</strong>
                            <span className="einfarbig leise">{s.url}</span>
                        </div>
                        <button className="knopf leise klein" disabled={sendet} onClick={() => setEntwurf({ ...s })}>
                            Bearbeiten
                        </button>
                        <button className="knopf leise klein" disabled={sendet} onClick={() => entfernen(s)}>
                            Entfernen
                        </button>
                    </div>
                ))}

                {(liste || []).length === 0 && !entwurf && (
                    <p className="leise">
                        Kein globaler Sender eingetragen. Bis dahin sehen die Server nur ihre
                        eigenen — und das KI-Radio, wo es freigeschaltet ist.
                    </p>
                )}

                {entwurf && (
                    <div className="karte-eingebettet">
                        <div className="feldgitter">
                            <Feld
                                titel="Name"
                                hilfe="So steht er überall in der Liste."
                                kind={<Text
                                    wert={entwurf.name}
                                    setzen={(w) => setEntwurf({ ...entwurf, name: w })}
                                    platzhalter="hoer.jetzt Charts"
                                />}
                            />
                            <Feld
                                titel="Logo (Adresse)"
                                hilfe="Optional. Ohne Bild zeigt die Kachel die Anfangsbuchstaben."
                                kind={<Bildfeld
                                    wert={entwurf.logoUrl || ""}
                                    setzen={(w) => setEntwurf({ ...entwurf, logoUrl: w })}
                                    platzhalter="https://…/logo.png"
                                    seitenverhaeltnis={1}
                                    zielbreite={256}
                                />}
                            />
                        </div>
                        <Feld
                            titel="Stream-Adresse"
                            breit
                            hilfe="Direkter Audiostream über http:// oder https:// — nicht die Webseite des Senders."
                            kind={<Text
                                wert={entwurf.url}
                                setzen={(w) => setEntwurf({ ...entwurf, url: w })}
                                platzhalter="https://stream.example.org/live.mp3"
                            />}
                        />
                        <div className="listenzeile">
                            <button
                                className="knopf"
                                disabled={sendet || !entwurf.name.trim() || !entwurf.url.trim()}
                                onClick={speichern}
                            >
                                {sendet ? "…" : "Speichern"}
                            </button>
                            <button className="knopf leise" disabled={sendet} onClick={() => setEntwurf(null)}>
                                Abbrechen
                            </button>
                        </div>
                    </div>
                )}
            </section>
        </>
    );
}
