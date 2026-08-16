import React, { useEffect, useState } from "react";
import { api } from "../lib/api.js";
import Farbschema from "./Farbschema.jsx";

/**
 * Wer angemeldet ist - unten links, wie in Discord.
 *
 * <p>Bis hierher fehlte beides: es stand nirgends, als wer man angemeldet ist,
 * und es gab keinen Weg hinaus. Abmelden ging nur ueber das Loeschen des
 * Cookies oder das Eintippen von /logout. Auf einem geteilten Rechner ist das
 * kein Schoenheitsfehler.</p>
 *
 * <p>Das Abmelden ist bewusst ein Verweis und kein {@code fetch}: der Endpunkt
 * beendet die Sitzung und leitet weiter, und genau das soll der Browser auch
 * tun. Ein Aufruf im Hintergrund liesse die Seite mit einer Sitzung
 * zurueck, die es nicht mehr gibt.</p>
 */
export default function Benutzerleiste() {
    const [ich, setIch] = useState(null);
    const [offen, setOffen] = useState(false);

    useEffect(() => {
        api("GET", "/api/dashboard/me").then(setIch).catch(() => setIch(null));
    }, []);

    // Klick daneben schliesst das Menue. Ohne das bleibt es offen, bis man
    // wieder genau den Knopf trifft - der haeufigste Grund, warum solche
    // Menues als "klemmt" wahrgenommen werden.
    useEffect(() => {
        if (!offen) return;
        const zu = () => setOffen(false);
        window.addEventListener("click", zu);
        return () => window.removeEventListener("click", zu);
    }, [offen]);

    return (
        <div className="benutzerleiste" onClick={(e) => e.stopPropagation()}>
            {offen && (
                <div className="benutzermenue">
                    <Farbschema klein />
                    {ich?.botAdmin && (
                        <a
                            className="benutzermenue-zeile"
                            href="/dashboard#/betrieb/verbund"
                            onClick={() => window.setTimeout(() => window.location.reload(), 0)}
                        >
                            Betrieb
                        </a>
                    )}
                    <a className="benutzermenue-zeile" href="/">Startseite</a>
                    <a className="benutzermenue-zeile ist-warnung" href="/logout">Abmelden</a>
                </div>
            )}

            <button className="benutzerknopf" onClick={() => setOffen((x) => !x)}>
                {ich?.avatarUrl
                    ? <img className="benutzerbild" src={ich.avatarUrl} alt="" />
                    : <span className="benutzerbild ist-leer">{(ich?.username || "?")[0]}</span>}
                <span className="benutzername">
                    <strong>{ich?.username || "…"}</strong>
                    <span className="leise">{ich?.botAdmin ? "Bot-Administrator" : "angemeldet"}</span>
                </span>
                <span className="benutzerpfeil">{offen ? "▾" : "▴"}</span>
            </button>
        </div>
    );
}
