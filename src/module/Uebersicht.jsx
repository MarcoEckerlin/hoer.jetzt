import React from "react";
import { MODULE } from "./verzeichnis.js";
import Serversymbol from "../teile/Serversymbol.jsx";

/**
 * Die Startseite eines Servers.
 *
 * <p>Sie war in der alten Oberflaeche der Einstieg und ist es wieder: wer ein
 * Dashboard oeffnet, will zuerst wissen, was los ist - nicht sofort ein
 * Formular ausfuellen. Vorher landete man direkt in der Wiedergabe, also in
 * einem Modul, das vielleicht gar nicht benutzt wird.</p>
 *
 * <p>Alles hier kommt aus Daten, die ohnehin schon geladen sind. Kein
 * zusaetzlicher Aufruf - eine Uebersicht, die eine Sekunde laedt, ist keine.</p>
 */
export default function Uebersicht({ server, konfig, gehe, botAdmin }) {
    const module = MODULE.filter((m) => m.aktiv);
    const aktive = module.filter((m) => m.aktiv(konfig));
    const ruhende = module.filter((m) => !m.aktiv(konfig));

    const rechte = konfig.entitlements || {};

    return (
        <>
            <header className="serverkopf">
                <Serversymbol server={server} className="serverkopf-bild" />
                <div>
                    <h1>{server?.name}</h1>
                    <p className="leise">
                        {server?.memberCount ? `${server.memberCount.toLocaleString("de-DE")} Mitglieder` : "Server"}
                        {server?.userInVoiceChannel && server?.userVoiceChannelName
                            ? ` · du bist in ${server.userVoiceChannelName}`
                            : ""}
                    </p>
                </div>
            </header>

            <div className="kachelreihe">
                <Kachel titel="Aktive Module" wert={aktive.length} von={module.length} />
                <Kachel titel="Rollen" wert={(konfig.roles || []).length} />
                <Kachel titel="Textkanäle" wert={(konfig.textChannels || []).length} />
                <Kachel titel="Vorlagen" wert={(konfig.embedVorlagen || []).length} />
            </div>

            <section className="karte-flach">
                <h2>Läuft gerade</h2>
                <div className="kachelgitter">
                    {aktive.map((m) => (
                        <button key={m.id} className="modulkachel ist-an" onClick={() => gehe(m.id)}>
                            <strong>{m.titel}</strong>
                            <span className="leise">aktiv</span>
                        </button>
                    ))}
                    {aktive.length === 0 && (
                        <p className="leise">
                            Noch ist nichts eingeschaltet. Such dir links ein Modul aus — jedes hat
                            oben einen Schalter.
                        </p>
                    )}
                </div>
            </section>

            {ruhende.length > 0 && (
                <section className="karte-flach">
                    <h2>Ausgeschaltet</h2>
                    <div className="kachelgitter">
                        {ruhende.map((m) => (
                            <button key={m.id} className="modulkachel" onClick={() => gehe(m.id)}>
                                <strong>{m.titel}</strong>
                                <span className="leise">aus</span>
                            </button>
                        ))}
                    </div>
                </section>
            )}

            {/*
              Freigaben sieht nur, wer sie auch vergeben kann.
              Fuer einen Serverbetreiber ist der Block eine Sackgasse: er zeigt
              drei Dinge als "gesperrt", die er selbst nicht aendern kann, und
              legt damit eine Bitte nahe, die er nirgends stellen kann. Wer
              Bot-Administrator ist, sieht ihn weiterhin - dort ist er eine
              Arbeitsanzeige.
            */}
            {botAdmin && (
                <section className="karte-flach">
                    <h2>Freigaben</h2>
                    <p className="leise">
                        Diese drei vergibt ein Bot-Administrator, nicht der Serverbetreiber — sie
                        kosten Rechenzeit oder Wiedergabekapazität.
                    </p>
                    <div className="kachelreihe">
                        <Freigabe titel="KI-Chat" frei={rechte.llmChat} />
                        <Freigabe titel="AI-Radio" frei={rechte.aiRadio} />
                        <Freigabe titel="Premium-Audio" frei={rechte.premiumAudio} />
                    </div>
                </section>
            )}
        </>
    );
}

function Kachel({ titel, wert, von }) {
    return (
        <div className="kachel">
            <span className="kachel-titel">{titel}</span>
            <strong>{von !== undefined ? `${wert} / ${von}` : wert}</strong>
        </div>
    );
}

function Freigabe({ titel, frei }) {
    return (
        <div className="kachel">
            <span className="kachel-titel">{titel}</span>
            <strong className={frei ? "ist-frei" : "leise"}>{frei ? "frei" : "gesperrt"}</strong>
        </div>
    );
}
