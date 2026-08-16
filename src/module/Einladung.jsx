import React, { useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Feld, Schalter, Text } from "../teile/felder.jsx";
import { Modulseite } from "./rahmen.jsx";

/**
 * Der eigene Einladungslink: hoer.jetzt/invite/{name}.
 *
 * <p>Eigener Endpunkt statt Teil der Konfiguration, weil der Kurzname gegen
 * alle Server auf der Instanz eindeutig sein muss. Der Server prueft das und
 * schickt den Grund zurueck - deshalb steht hier die Fehlermeldung im
 * Vordergrund und nicht ein stilles "gespeichert".</p>
 */
export default function Einladung({ guildId }) {
    const [stand, setStand] = useState(null);
    const [entwurf, setEntwurf] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [sendet, setSendet] = useState(false);
    const [kopiert, setKopiert] = useState(false);

    async function laden() {
        try {
            const d = await api("GET", `/api/dashboard/guilds/${guildId}/invite`);
            setStand(d);
            setEntwurf({ enabled: !!d.enabled, slug: d.slug || "", targetUrl: d.targetUrl || "" });
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }

    useEffect(() => { laden(); }, [guildId]);

    async function speichern() {
        setSendet(true);
        setMeldung(null);
        setFehler(null);
        try {
            const antwort = await api("POST", `/api/dashboard/guilds/${guildId}/invite`, entwurf);
            setMeldung(antwort?.message || "Gespeichert.");
            await laden();
        } catch (f) {
            setFehler(f.message);
        } finally {
            setSendet(false);
        }
    }

    if (!entwurf) return <div className="ladeschirm"><div className="puls" /></div>;

    // Nur das, was hinterher auch in einer Adresse stehen darf. Die Prüfung
    // läuft auch auf dem Server - hier steht sie, damit der Hinweis beim Tippen
    // kommt und nicht erst nach dem Speichern.
    const sauber = /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/.test(entwurf.slug || "");

    return (
        <Modulseite
            titel="Einladungslink"
            hilfe="Ein kurzer, eigener Link auf deinen Server — merkbar und ohne Ablaufdatum."
        >
            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && <div className="notiz">{meldung}</div>}

            <section className="karte-flach">
                <Schalter
                    an={entwurf.enabled}
                    setzen={(x) => setEntwurf({ ...entwurf, enabled: x })}
                    titel="Link aktiv"
                    hilfe="Aus heißt: der Link führt ins Leere. Der Kurzname bleibt für diesen Server reserviert."
                />

                <div className="feldgitter">
                    <Feld
                        titel="Kurzname"
                        hilfe="Kleinbuchstaben, Ziffern und Bindestriche, 3 bis 32 Zeichen. Gilt für die ganze Instanz — wer zuerst speichert, hat ihn."
                        kind={
                            <Text
                                wert={entwurf.slug}
                                setzen={(x) => setEntwurf({ ...entwurf, slug: x.toLowerCase().replace(/[^a-z0-9-]/g, "") })}
                                platzhalter="mein-server"
                            />
                        }
                    />
                    <Feld
                        titel="Ziel"
                        hilfe="Die eigentliche Discord-Einladung. Am besten eine ohne Ablauf und ohne Nutzungsgrenze — sonst führt der schöne Link nach zwei Wochen nirgendwohin."
                        kind={
                            <Text
                                wert={entwurf.targetUrl}
                                setzen={(x) => setEntwurf({ ...entwurf, targetUrl: x })}
                                platzhalter="https://discord.gg/…"
                            />
                        }
                    />
                </div>

                {entwurf.slug && !sauber && (
                    <p className="feld-hilfe">
                        So geht der Name nicht durch: 3 bis 32 Zeichen, Anfang und Ende keine Bindestriche.
                    </p>
                )}

                <div className="listenzeile">
                    <button className="knopf" onClick={speichern} disabled={sendet || (entwurf.enabled && !sauber)}>
                        {sendet ? "Speichert…" : "Speichern"}
                    </button>
                </div>
            </section>

            {stand?.publicUrl && (
                <section className="karte-flach">
                    <h2>Dein Link</h2>
                    <div className="linkzeile">
                        <code>{stand.publicUrl}</code>
                        <button
                            className="knopf leise klein"
                            onClick={() => {
                                navigator.clipboard?.writeText(stand.publicUrl);
                                setKopiert(true);
                                setTimeout(() => setKopiert(false), 2000);
                            }}
                        >
                            {kopiert ? "Kopiert" : "Kopieren"}
                        </button>
                    </div>
                    <p className="feld-hilfe">
                        {stand.clicks > 0
                            ? `${stand.clicks}× aufgerufen.`
                            : "Noch nicht aufgerufen."}
                    </p>
                </section>
            )}
        </Modulseite>
    );
}
