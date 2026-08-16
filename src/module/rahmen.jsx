import React, { useState } from "react";
import { api } from "../lib/api.js";
import { Speicherleiste, useEntwurf } from "../teile/felder.jsx";

/**
 * Der gemeinsame Rahmen jeder Modulseite: Kopf, Ein/Aus, Speicherleiste.
 *
 * <p>Jede Modulseite tat frueher dasselbe auf ihre eigene Art - eine hatte
 * eine Erfolgsmeldung, die naechste nicht, die dritte lud nach dem Speichern
 * nicht neu und zeigte danach alte Werte an. Hier steht der Ablauf einmal.</p>
 */
export function Modulseite({ titel, hilfe, hinweis, kopfzusatz, children }) {
    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>{titel}</h1>
                    {hilfe && <p className="leise">{hilfe}</p>}
                </div>
                {kopfzusatz}
            </header>
            {hinweis && <div className="notiz">{hinweis}</div>}
            {children}
        </>
    );
}

/**
 * Entwurf halten, speichern, danach neu laden.
 *
 * <p>Das Neuladen ist nicht optional: der Server rechnet beim Speichern
 * Einiges nach (vergibt Kennungen, kuerzt Werte, setzt Hinweise). Ohne das
 * Neuladen zeigt die Oberflaeche danach den <em>eingegebenen</em> Stand statt
 * des gespeicherten - und der Unterschied faellt erst beim naechsten Besuch
 * auf, wenn niemand mehr weiss, woran es lag.</p>
 */
export function useModul({ anfang, pfad, neuLaden, aufbereiten }) {
    const { entwurf, setEntwurf, setzeFeld, geaendert, verwerfen } = useEntwurf(anfang);
    const [sendet, setSendet] = useState(false);
    const [meldung, setMeldung] = useState(null);

    async function speichern() {
        setSendet(true);
        setMeldung(null);
        try {
            const koerper = aufbereiten ? aufbereiten(entwurf) : entwurf;
            const antwort = await api("POST", pfad, koerper);
            setMeldung(antwort?.message || "Gespeichert.");
            await neuLaden();
            // Die Meldung nach ein paar Sekunden wegnehmen, sonst steht am
            // unteren Rand dauerhaft eine Leiste, die nichts mehr sagt.
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
            verwerfen={verwerfen}
            meldung={geaendert ? null : meldung}
        />
    );

    return { entwurf, setEntwurf, setzeFeld, geaendert, verwerfen, speichern, sendet, meldung, leiste };
}
