import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { MODULE } from "../module/verzeichnis.js";
import Serversymbol from "../teile/Serversymbol.jsx";
import Benutzerleiste from "../teile/Benutzerleiste.jsx";

/**
 * Das Dashboard, aufgebaut wie Discord selbst.
 *
 * <p>Drei Spalten, weil die Leute sie kennen: ganz links die Server als
 * quadratische Symbole, daneben die Liste dessen, was dieser Server hat,
 * rechts der Inhalt.
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
    const [suche, setSuche] = useState("");
    // Einmal hier und von hier weitergereicht: sonst fragen mehrere Bausteine
    // dasselbe ab, und der zweite zeichnet einen Sekundenbruchteil spaeter nach.
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
        // Auf dem Telefon liegt die Schublade ueber dem Inhalt. Bliebe sie
        // offen, waere die erste Sicht auf das gewaehlte Modul die Leiste,
        // aus der man es gerade gewaehlt hat. Auf dem Rechner ist nichts
        // zugeklappt, dort tut die Zeile nichts.
        setOffen(false);
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

    // Nach Name oder Kennung - wer die ID kopiert hat, findet den Server
    // damit ebenso wie ueber den Namen.
    const gefilterteServer = suche.trim()
        ? server.filter((s) => (s.name || "").toLowerCase().includes(suche.trim().toLowerCase())
            || s.id.includes(suche.trim()))
        : server;

    return (
        <div className={`dashboard ${offen ? "ist-offen" : ""}`}>
            {/* Nur sichtbar, solange die Schublade offen ist - und nur dort,
                wo sie ueberhaupt eine ist (siehe .menuschirm im Stylesheet). */}
            {offen && (
                <button
                    className="menuschirm"
                    onClick={() => setOffen(false)}
                    aria-label="Menü schließen"
                />
            )}

            <aside className="serverleiste">
                {/*
                  Suche wie in Discord: oben in der Leiste, nicht in einem
                  Menue. Sie erscheint erst ab acht Servern - darunter ist die
                  Liste kuerzer als das Suchfeld und man findet schneller mit
                  den Augen.
                */}
                {server.length >= 8 && (
                    <div className="serversuche">
                        <input
                            className="serversuche-feld"
                            type="search"
                            value={suche}
                            onChange={(e) => setSuche(e.target.value)}
                            placeholder="Suchen"
                            aria-label="Server suchen"
                        />
                    </div>
                )}

                {gefilterteServer.map((s) => (
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
                  Hier stand der Betriebsbereich als zusaetzlicher Knopf. Er
                  ist wieder weg: in der Serverleiste stehen Server, und ein
                  Eintrag, der keiner ist, sortiert sich nur dazwischen.
                  Erreichbar bleibt der Bereich ueber das Profilmenue unten
                  links und ueber die Adresse /admin.
                */}
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
                        botAdmin={botAdmin}
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

