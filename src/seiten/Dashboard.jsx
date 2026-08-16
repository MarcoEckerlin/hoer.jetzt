import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { MODULE } from "../module/verzeichnis.js";
import Serversymbol from "../teile/Serversymbol.jsx";
import Benutzerleiste from "../teile/Benutzerleiste.jsx";
import { SYMBOLE } from "../teile/Symbole.jsx";

/**
 * Das Dashboard, aufgebaut wie Discord selbst.
 *
 * <p>Drei Spalten, weil die Leute sie kennen: ganz links die Server als runde
 * Symbole, daneben die Liste dessen, was dieser Server hat, rechts der Inhalt.
 * Unten links die Person, die angemeldet ist - genau dort, wo sie in Discord
 * auch sitzt. Wer den Aufbau schon im Kopf hat, muss ihn hier nicht neu
 * lernen.</p>
 *
 * <p>Beim Oeffnen eines Servers steht die <b>Uebersicht</b>, nicht ein Modul.
 * Das war in der alten Oberflaeche so und ist richtig: erst sehen, was los ist,
 * dann etwas einstellen.</p>
 *
 * <p>Die Konfiguration wird einmal je Server geladen und weitergereicht. Server
 * und Modul stehen in der Adresse, damit F5 niemanden herauswirft und ein Link
 * auf eine Modulseite teilbar bleibt.</p>
 */
export default function Dashboard({ server }) {
    const [ort, setOrt] = useState(ausAdresse);
    const [konfig, setKonfig] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [laedt, setLaedt] = useState(true);
    const [offen, setOffen] = useState(false);
    // Einmal hier statt einmal in der Benutzerleiste und einmal fuer den
    // Betriebsknopf: sonst fragen zwei Bausteine dasselbe ab, und der zweite
    // zeichnet einen Sekundenbruchteil spaeter nach.
    const [ich, setIch] = useState(null);

    useEffect(() => {
        api("GET", "/api/dashboard/me").then(setIch).catch(() => setIch(null));
    }, []);

    const botAdmin = !!ich?.botAdmin;

    const guildId = ort.guildId || server[0]?.id || null;
    const modulId = MODULE.some((m) => m.id === ort.modul) ? ort.modul : MODULE[0].id;

    useEffect(() => {
        const hoeren = () => setOrt(ausAdresse());
        window.addEventListener("hashchange", hoeren);
        return () => window.removeEventListener("hashchange", hoeren);
    }, []);

    useEffect(() => {
        const soll = `#/server/${guildId}/${modulId}`;
        if (guildId && window.location.hash !== soll) {
            window.history.replaceState(null, "", soll);
        }
        setOffen(false);
    }, [guildId, modulId]);

    const laden = useCallback(async () => {
        if (!guildId) return;
        setLaedt(true);
        try {
            const [c, sprach] = await Promise.all([
                api("GET", `/api/dashboard/guilds/${guildId}/config`),
                api("GET", `/api/dashboard/guilds/${guildId}/voice-channels`).catch(() => [])
            ]);
            setKonfig({ ...c, voiceChannels: sprach || [] });
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
            setKonfig(null);
        } finally {
            setLaedt(false);
        }
    }, [guildId]);

    useEffect(() => { laden(); }, [laden]);

    if (!server.length) {
        return (
            <div className="hinweisseite">
                <h1>Kein Server</h1>
                <p>
                    Auf keinem deiner Server ist der Bot vorhanden — oder dir fehlt dort das
                    Recht, das Panel zu öffnen.
                </p>
                <a className="knopf" href="/">Zur Startseite</a>
            </div>
        );
    }

    const aktuell = server.find((s) => s.id === guildId) || server[0];
    const modul = MODULE.find((m) => m.id === modulId);
    const Inhalt = modul.seite;

    function gehe(neu) {
        window.location.hash = `#/server/${neu.guildId ?? guildId}/${neu.modul ?? modulId}`;
    }

    // Gruppen in der Reihenfolge ihres ersten Auftretens - so steht die
    // Reihenfolge an einer Stelle (im Verzeichnis) und nicht zweimal.
    const gruppen = [];
    MODULE.filter((m) => !m.sichtbar || m.sichtbar(konfig || {})).forEach((m) => {
        const name = m.gruppe || "Sonstiges";
        let g = gruppen.find((x) => x.name === name);
        if (!g) gruppen.push((g = { name, module: [] }));
        g.module.push(m);
    });

    return (
        <div className={`dashboard ${offen ? "ist-offen" : ""}`}>
            <aside className="serverleiste">
                {server.map((s) => (
                    <button
                        key={s.id}
                        className={`serverknopf ${s.id === guildId ? "ist-aktiv" : ""}`}
                        onClick={() => gehe({ guildId: s.id, modul: "start" })}
                        title={s.name}
                    >
                        <span className="serverknopf-marke" />
                        <Serversymbol server={s} className="serverknopf-bild" />
                        <span className="serverknopf-hinweis">{s.name}</span>
                    </button>
                ))}

                {/*
                  Der Betriebsbereich lag bisher nur im aufklappbaren
                  Benutzermenue unten links - man musste wissen, dass es ihn
                  gibt. Hier steht er dort, wo Discord die Server-Verwaltung
                  hat: unten in derselben Leiste, mit demselben Knopf.
                  Sichtbar nur fuer Bot-Admins; das ist Bequemlichkeit, kein
                  Schutz - der Bot prueft jeden Aufruf ohnehin einzeln.
                */}
                {botAdmin && (
                    <a
                        className="serverknopf serverknopf-betrieb"
                        href="/dashboard#/betrieb/verbund"
                        onClick={() => window.setTimeout(() => window.location.reload(), 0)}
                        title="Betrieb"
                    >
                        <span className="serverknopf-marke" />
                        <span className="serverknopf-bild ist-leer">{SYMBOLE.verbund}</span>
                        <span className="serverknopf-hinweis">Betrieb</span>
                    </a>
                )}
            </aside>

            <nav className="modulleiste">
                <div className="modulleiste-kopf">
                    <span>{aktuell?.name}</span>
                </div>

                <div className="modulleiste-liste">
                    {gruppen.map((g) => (
                        <div className="modulgruppe" key={g.name}>
                            <span className="modulgruppe-titel">{g.name}</span>
                            {g.module.map((m) => (
                                <button
                                    key={m.id}
                                    className={`modulknopf ${m.id === modulId ? "ist-aktiv" : ""}`}
                                    onClick={() => gehe({ modul: m.id })}
                                >
                                    <span className="modulknopf-symbol">{m.symbol}</span>
                                    <span className="modulknopf-titel">{m.titel}</span>
                                    {konfig && m.aktiv?.(konfig) && <span className="modulpunkt" title="Aktiv" />}
                                </button>
                            ))}
                        </div>
                    ))}
                </div>

                <Benutzerleiste ich={ich} />
            </nav>

            <main className="dashboard-inhalt">
                <button className="menuknopf" onClick={() => setOffen((x) => !x)} aria-label="Menü">
                    ☰ {modul.titel}
                </button>

                {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
                {laedt && !konfig && <div className="ladeschirm"><div className="puls" /></div>}
                {konfig && (
                    <Inhalt
                        key={`${guildId}:${modulId}`}
                        guildId={guildId}
                        server={aktuell}
                        konfig={konfig}
                        neuLaden={laden}
                        gehe={(m) => gehe({ modul: m })}
                    />
                )}
            </main>
        </div>
    );
}

function ausAdresse() {
    const treffer = /^#\/server\/(\d+)(?:\/([a-z-]+))?/.exec(window.location.hash || "");
    return treffer ? { guildId: treffer[1], modul: treffer[2] } : {};
}

