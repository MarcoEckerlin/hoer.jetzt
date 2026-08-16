import React, { useState } from "react";
import Verbund from "./Verbund.jsx";
import Knoten from "./Knoten.jsx";
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
    { id: "verbund", titel: "Verbund", symbol: SYMBOLE.verbund, seite: Verbund },
    { id: "knoten", titel: "Audio-Knoten", symbol: SYMBOLE.knoten, seite: Knoten }
];

export default function Betrieb({ seite, gehe }) {
    const [offen, setOffen] = useState(false);
    const aktuell = SEITEN.find((s) => s.id === seite) || SEITEN[0];
    const Inhalt = aktuell.seite;

    return (
        <div className={`dashboard ${offen ? "ist-offen" : ""}`}>
            <aside className="serverleiste">
                <button className="serverknopf" onClick={() => gehe("zurueck")} title="Zurück zu den Servern">
                    ←
                    <span className="serverknopf-hinweis">Zurück zu den Servern</span>
                </button>
            </aside>

            <nav className="modulleiste">
                <div className="modulleiste-kopf"><span>Betrieb</span></div>

                <div className="modulleiste-liste">
                    <div className="modulgruppe">
                        <span className="modulgruppe-titel">Instanz</span>
                        {SEITEN.map((s) => (
                            <button
                                key={s.id}
                                className={`modulknopf ${s.id === aktuell.id ? "ist-aktiv" : ""}`}
                                onClick={() => gehe(s.id)}
                            >
                                <span className="modulknopf-symbol">{s.symbol}</span>
                                <span className="modulknopf-titel">{s.titel}</span>
                            </button>
                        ))}
                        {/*
                          Die bisherige Verwaltungsoberflaeche von core. Sie kann
                          noch Dinge, die hier fehlen - Knoten eintragen,
                          Bot-Admins, Freigaben - und ist deshalb ein Verweis
                          und keine Kopie.
                        */}
                        <a className="modulknopf" href="/admin">
                            <span className="modulknopf-symbol">{SYMBOLE.werkzeug}</span>
                            <span className="modulknopf-titel">Einstellungen</span>
                        </a>
                    </div>
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
