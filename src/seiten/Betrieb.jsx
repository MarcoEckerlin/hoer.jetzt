import React, { useState } from "react";
import Verbund from "./Verbund.jsx";
import Knoten from "./Knoten.jsx";
import Deployments from "./Deployments.jsx";
import Admins from "./Admins.jsx";
import Sender from "./Sender.jsx";
import Instanz from "./Instanz.jsx";
import BotZugang from "./BotZugang.jsx";
import Server from "./Server.jsx";
import Protokoll from "./Protokoll.jsx";
import { SYMBOLE } from "../teile/Symbole.jsx";
import Benutzerleiste from "../teile/Benutzerleiste.jsx";

/**
 * Der Betriebsbereich - wieder in der Weboberflaeche, aber als eigener Ort.
 *
 * <h2>Warum wieder hier</h2>
 *
 * <p>Er lag eine Zeit lang in einer getrennten Anwendung mit eigenem Abbild.
 * Das war sicherheitstechnisch sauberer - was nicht installiert ist, kann auch
 * nicht angegriffen werden - aber im Alltag umstaendlich: zweiter Container,
 * zweiter Port, zweiter Reverse-Proxy-Eintrag, und die Anmeldung musste ueber
 * denselben Ursprung laufen, sonst kam man nicht hinein.</p>
 *
 * <p>Jetzt ist er wieder Teil der Oberflaeche, aber nicht in ihr verstreut: ein
 * eigener Bereich mit eigener Adresse (<code>#/betrieb/...</code>) und eigener
 * Leiste. Wer kein Bot-Administrator ist, bekommt ihn nicht zu sehen - und
 * selbst wenn er die Adresse errraet, entscheidet der Bot bei jedem einzelnen
 * Aufruf noch einmal. Ein ausgeblendeter Knopf ist Bequemlichkeit, kein
 * Schutz.</p>
 */
const SEITEN = [
    { id: "verbund", titel: "Verbund", gruppe: "Zustand", symbol: SYMBOLE.verbund, seite: Verbund },
    { id: "knoten", titel: "Audio-Knoten", gruppe: "Zustand", symbol: SYMBOLE.knoten, seite: Knoten },
    { id: "server", titel: "Server", gruppe: "Zustand", symbol: SYMBOLE.uebersicht, seite: Server },
    { id: "protokoll", titel: "Protokoll", gruppe: "Zustand", symbol: SYMBOLE.protokoll, seite: Protokoll },

    { id: "instanz", titel: "Instanz", gruppe: "Einstellungen", symbol: SYMBOLE.instanz, seite: Instanz },
    { id: "bot", titel: "Bot & Zugang", gruppe: "Einstellungen", symbol: SYMBOLE.verify, seite: BotZugang },
    { id: "deployments", titel: "Deployments", gruppe: "Einstellungen", symbol: SYMBOLE.werkzeug, seite: Deployments },
    { id: "admins", titel: "Bot-Verwaltung", gruppe: "Einstellungen", symbol: SYMBOLE.personen, seite: Admins },
    { id: "sender", titel: "Globale Sender", gruppe: "Einstellungen", symbol: SYMBOLE.radio, seite: Sender }
];

// Reihenfolge aus dem ersten Auftreten - so steht sie an einer Stelle und
// nicht zweimal. Gleiche Regel wie im Dashboard.
const GRUPPEN = SEITEN.reduce((liste, s) => {
    let g = liste.find((x) => x.name === s.gruppe);
    if (!g) liste.push((g = { name: s.gruppe, seiten: [] }));
    g.seiten.push(s);
    return liste;
}, []);

export default function Betrieb({ seite, gehe }) {
    const [offen, setOffen] = useState(false);
    const aktuell = SEITEN.find((s) => s.id === seite) || SEITEN[0];
    const Inhalt = aktuell.seite;

    // Wie im Dashboard: auf dem Telefon liegt die Schublade ueber dem Inhalt
    // und muss nach der Wahl aus dem Weg. Auf dem Rechner tut die Zeile nichts.
    function wechseln(ziel) {
        gehe(ziel);
        setOffen(false);
    }

    return (
        <div className={`dashboard ${offen ? "ist-offen" : ""}`}>
            {offen && (
                <button
                    className="menuschirm"
                    onClick={() => setOffen(false)}
                    aria-label="Menü schließen"
                />
            )}

            <aside className="serverleiste">
                <button className="serverknopf" onClick={() => wechseln("zurueck")} title="Zurück zu den Servern">
                    ←
                    <span className="serverknopf-hinweis">Zurück zu den Servern</span>
                </button>
            </aside>

            <nav className="modulleiste">
                <div className="modulleiste-kopf"><span>Betrieb</span></div>

                <div className="modulleiste-liste">
                    {GRUPPEN.map((g) => (
                        <div className="modulgruppe" key={g.name}>
                            <span className="modulgruppe-titel">{g.name}</span>
                            {g.seiten.map((s) => (
                                <button
                                    key={s.id}
                                    className={`modulknopf ${s.id === aktuell.id ? "ist-aktiv" : ""}`}
                                    onClick={() => wechseln(s.id)}
                                >
                                    <span className="modulknopf-symbol">{s.symbol}</span>
                                    <span className="modulknopf-titel">{s.titel}</span>
                                </button>
                            ))}
                        </div>
                    ))}

                </div>

                <Benutzerleiste />
            </nav>

            <main className="dashboard-inhalt">
                <button className="menuknopf" onClick={() => setOffen((x) => !x)} aria-label="Menü">
                    ☰ {aktuell.titel}
                </button>
                <Inhalt />
            </main>
        </div>
    );
}
