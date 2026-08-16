import React, { useEffect, useState } from "react";
import { api } from "../lib/api.js";

/**
 * Der Verbund: welche Nodes es gibt, was sie melden, und was sie tun sollen.
 *
 * <p>Bis hierher gab es die Schnittstelle schon, aber keine Oberflaeche - das
 * Ziel liess sich nur mit einem HTTP-Aufruf von Hand setzen. Das ist genau die
 * Sorte Bedienung, bei der man sich um Mitternacht vertippt.</p>
 */
export default function Verbund() {
    const [nodes, setNodes] = useState([]);
    const [ziel, setZiel] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [laedt, setLaedt] = useState(true);
    const [entwurf, setEntwurf] = useState({ releaseVersion: "", shardsGesamt: "" });
    const [sendet, setSendet] = useState(false);

    async function laden() {
        try {
            const [liste, aktuell] = await Promise.all([
                api("GET", "/api/verbund/nodes"),
                api("GET", "/api/verbund/ziel")
            ]);
            setNodes(liste || []);
            setZiel(aktuell);
            setEntwurf({
                releaseVersion: aktuell?.releaseVersion || "",
                shardsGesamt: aktuell?.shardsGesamt ? String(aktuell.shardsGesamt) : ""
            });
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        } finally {
            setLaedt(false);
        }
    }

    useEffect(() => {
        laden();
        // Eine Minute: der Agent meldet sich in diesem Takt, haeufiger fragen
        // zeigt nur denselben Stand noch einmal.
        const takt = setInterval(laden, 60000);
        return () => clearInterval(takt);
    }, []);

    async function zielSetzen(ereignis) {
        ereignis.preventDefault();
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/verbund/ziel", {
                releaseVersion: entwurf.releaseVersion.trim() || null,
                shardsGesamt: entwurf.shardsGesamt ? Number(entwurf.shardsGesamt) : null
            });
            setMeldung(antwort?.message || "Ziel gesetzt.");
            await laden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setSendet(false);
        }
    }

    if (laedt) return <div className="ladeschirm"><div className="puls" /></div>;

    const lebend = nodes.filter((n) => n.lebt).length;

    return (
        <>
            <header className="modulkopf"><div>
                <h1>Verbund</h1>
                <p>{nodes.length} Node{nodes.length === 1 ? "" : "s"} bekannt, {lebend} davon melden sich.</p>
            </div></header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}

            <section className="karte-flach">
                <h2>Ziel</h2>
                <p className="leise">
                    Gilt für alle Nodes. Sie übernehmen es beim nächsten Lauf des Agenten –
                    nicht sofort. Ein leeres Feld lässt den bisherigen Wert stehen.
                </p>
                <form className="listenzeile" onSubmit={zielSetzen}>
                    <label>
                        Release
                        <input
                            type="text"
                            placeholder="v2026.08.15.9"
                            value={entwurf.releaseVersion}
                            onChange={(e) => setEntwurf({ ...entwurf, releaseVersion: e.target.value })}
                        />
                    </label>
                    <label>
                        Shards gesamt
                        <input
                            type="number"
                            min="1"
                            placeholder="automatisch"
                            value={entwurf.shardsGesamt}
                            onChange={(e) => setEntwurf({ ...entwurf, shardsGesamt: e.target.value })}
                        />
                    </label>
                    <button className="knopf" type="submit" disabled={sendet}>
                        {sendet ? "…" : "Ziel setzen"}
                    </button>
                </form>
                {meldung && <div className="notiz">{meldung}</div>}
                {ziel?.gesetztAm && (
                    <p className="leise">
                        Zuletzt gesetzt {zeit(ziel.gesetztAm)}
                        {ziel.gesetztVon ? ` von ${ziel.gesetztVon}` : ""}.
                    </p>
                )}
            </section>

            <section className="karte-flach">
                <h2>Nodes</h2>
                <div className="tabelle">
                    <div className="zeile kopfzeile">
                        <span>Name</span><span>Nr</span><span>Adresse</span>
                        <span>Shards</span><span>Release</span><span>Zuletzt</span>
                    </div>
                    {nodes.map((n) => (
                        <div className={`zeile ${n.lebt ? "" : "ist-still"}`} key={n.nodeName}>
                            <span>
                                <span className={`ampel ${n.lebt ? "ist-gut" : "ist-weg"}`} />
                                {n.nodeName}
                            </span>
                            <span>{n.nodeNr}</span>
                            <span className="einfarbig">{n.privatIp || "—"}</span>
                            <span>{shards(n)}</span>
                            <span className="einfarbig">{n.releaseVersion || "—"}</span>
                            <span>{zeit(n.letzteMeldung)}</span>
                        </div>
                    ))}
                    {nodes.length === 0 && (
                        <div className="zeile leer">
                            Noch keine Node hat sich gemeldet. Der Agent läuft per Timer –
                            nach der Installation dauert es bis zu einer Minute.
                        </div>
                    )}
                </div>
            </section>
        </>
    );
}

function shards(n) {
    if (n.shardsVon == null || n.shardsBis == null) return "—";
    const bereich = n.shardsVon === n.shardsBis ? `${n.shardsVon}` : `${n.shardsVon}–${n.shardsBis}`;
    return n.shardsGesamt ? `${bereich} / ${n.shardsGesamt}` : bereich;
}

/** Grobe Zeitangabe. "vor 3 Minuten" liest sich schneller als ein Zeitstempel. */
function zeit(iso) {
    if (!iso) return "nie";
    const sekunden = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
    if (!Number.isFinite(sekunden)) return "—";
    if (sekunden < 90) return "gerade eben";
    const minuten = Math.floor(sekunden / 60);
    if (minuten < 60) return `vor ${minuten} Min.`;
    const stunden = Math.floor(minuten / 60);
    if (stunden < 48) return `vor ${stunden} Std.`;
    return `vor ${Math.floor(stunden / 24)} Tagen`;
}
