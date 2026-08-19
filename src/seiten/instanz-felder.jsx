import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Speicherleiste } from "../teile/felder.jsx";

/**
 * Der gemeinsame Unterbau der Instanz-Seiten im Betrieb.
 *
 * <h2>Warum ein Baustein und nicht je Seite ein Formular</h2>
 *
 * <p>Marke, Wartung, Bot-Zugang, Discord-Login und der KI-Anbieter sind fuer
 * den Benutzer fuenf Themen, fuer den Bot aber <em>eine</em> Konfiguration:
 * dieselbe Quelle ({@code GET /api/admin/config}), dasselbe Ziel
 * ({@code POST /api/admin/config}). Jede Seite ihr eigenes Laden und Speichern
 * schreiben zu lassen hiesse, denselben Ablauf fuenfmal zu pflegen - und beim
 * fuenften Mal fehlt die Erfolgsmeldung.</p>
 *
 * <h2>Warum nur geaenderte Felder geschickt werden</h2>
 *
 * <p>Der Bot behandelt {@code null} als "unveraendert lassen". Wer das ganze
 * Objekt zurueckschickt, ueberschreibt damit auch Felder, die er nie gesehen
 * hat - etwa das Client-Secret, das die API aus gutem Grund nur maskiert
 * ausliefert. Deshalb geht nur hinaus, was tatsaechlich angefasst wurde.</p>
 */
export function useInstanzKonfig() {
    const [konfig, setKonfig] = useState(null);
    const [entwurf, setEntwurf] = useState({});
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [sendet, setSendet] = useState(false);

    const laden = useCallback(async () => {
        try {
            setKonfig(await api("GET", "/api/admin/config"));
            setEntwurf({});
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, []);

    useEffect(() => { laden(); }, [laden]);

    /** Der anzuzeigende Wert: das Geaenderte, sonst das Gespeicherte. */
    function wert(feld) {
        if (Object.prototype.hasOwnProperty.call(entwurf, feld)) return entwurf[feld];
        return konfig?.[feld] ?? "";
    }

    function setzen(feld, neu) {
        setEntwurf((alt) => ({ ...alt, [feld]: neu }));
    }

    const geaendert = Object.keys(entwurf).length > 0;

    async function speichern() {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/config", entwurf);
            setMeldung(antwort?.message || "Gespeichert.");
            await laden();
            setTimeout(() => setMeldung(null), 4000);
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setSendet(false);
        }
    }

    const leiste = (
        <Speicherleiste
            geaendert={geaendert}
            sendet={sendet}
            speichern={speichern}
            verwerfen={() => setEntwurf({})}
            meldung={geaendert ? null : meldung}
        />
    );

    return { konfig, fehler, wert, setzen, geaendert, leiste, laden };
}

/** Kopf einer Betriebsseite - gleiche Form wie die Modulseiten im Dashboard. */
export function Betriebsseite({ titel, hilfe, fehler, children }) {
    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>{titel}</h1>
                    {hilfe && <p>{hilfe}</p>}
                </div>
            </header>
            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {children}
        </>
    );
}
