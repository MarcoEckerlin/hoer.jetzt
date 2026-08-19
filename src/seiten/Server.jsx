import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import Serversymbol from "../teile/Serversymbol.jsx";
import Serverstatistik from "../teile/Serverstatistik.jsx";

/**
 * Alle Server, auf denen der Bot ist - mit ihren Freigaben.
 *
 * <p>Die drei Freigaben (KI-Chat, AI-Radio, Premium-Audio) kosten Rechenzeit
 * oder Wiedergabekapazitaet und werden deshalb hier vergeben, nicht vom
 * Serverbetreiber. In der Uebersicht eines Servers sind sie nur sichtbar -
 * mit dem Hinweis, wer sie erteilt.</p>
 *
 * <p>Im Verbund zeigt diese Seite die Server <em>dieser</em> Node. Mit
 * aufgeteilten Shards kennt jede nur ihre Haelfte; die Dashboard-Liste wird
 * zusammengefuehrt, diese Verwaltungsliste bewusst nicht: eine Freigabe wird
 * dort erteilt, wo der Server auch laeuft.</p>
 */
export default function Server() {
    const [liste, setListe] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [beschaeftigt, setBeschaeftigt] = useState(null);
    const [suche, setSuche] = useState("");
    const [offen, setOffen] = useState(null);

    const laden = useCallback(async () => {
        try {
            setListe((await api("GET", "/api/admin/management/guilds")) || []);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, []);

    useEffect(() => { laden(); }, [laden]);

    async function umschalten(guildId, freigabe) {
        const schluessel = guildId + ":" + freigabe.feature;
        setBeschaeftigt(schluessel);
        setMeldung(null);
        try {
            const antwort = await api("POST", `/api/admin/management/guilds/${guildId}/entitlements`, {
                feature: freigabe.feature,
                enabled: !freigabe.enabled,
                dailyLimit: freigabe.dailyLimit ?? 0,
                note: freigabe.note || ""
            });
            setMeldung({ art: "gut", text: antwort?.message || "Gespeichert." });
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setBeschaeftigt(null);
        }
    }

    if (!liste && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    const gefiltert = suche
        ? (liste || []).filter((s) => (s.name || "").toLowerCase().includes(suche.toLowerCase()) || s.id.includes(suche))
        : (liste || []);

    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>Server</h1>
                    <p>Wo der Bot überall ist — und was dort freigeschaltet ist.</p>
                </div>
                {(liste || []).length > 6 && (
                    <input
                        className="eingabe eingabe-suche"
                        placeholder="Server suchen…"
                        value={suche}
                        onChange={(e) => setSuche(e.target.value)}
                    />
                )}
            </header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && (
                <div className={`notiz ${meldung.art === "schlecht" ? "notiz-fehler" : "notiz-gut"}`}>
                    {meldung.text}
                </div>
            )}

            <div className="knotenliste">
                {gefiltert.map((server) => (
                    <div className="knotenzeile" key={server.id}>
                        <div
                            className="knotenzeile-kopf"
                            onClick={() => setOffen(offen === server.id ? null : server.id)}
                        >
                            <Serversymbol server={server} className="minicover" />
                            <span className="knotenzeile-name">
                                <strong>{server.name}</strong>
                                <span className="einfarbig leise">
                                    {server.memberCount?.toLocaleString("de-DE")} Mitglieder
                                    {server.ownerName ? ` · ${server.ownerName}` : ""}
                                </span>
                            </span>
                            <span className="knotenzeile-marken">
                                {(server.entitlements || []).filter((f) => f.enabled).map((f) => (
                                    <span className="marke ist-gut" key={f.feature}>{f.featureLabel}</span>
                                ))}
                                {!server.permissionsConfigured && (
                                    <span className="marke leise" title="Auf diesem Server wurden noch keine Rollenrechte vergeben.">
                                        ohne Rechte
                                    </span>
                                )}
                            </span>
                            <span className="leise">{offen === server.id ? "▾" : "▸"}</span>
                        </div>

                        {offen === server.id && (
                            <div className="knotenzeile-inhalt">
                                <p className="feld-hilfe">
                                    Server-ID <span className="einfarbig">{server.id}</span>
                                    {server.joinedAt ? ` · dabei seit ${server.joinedAt.slice(0, 10)}` : ""}
                                </p>
                                {(server.entitlements || []).map((freigabe) => (
                                    <div className="technikzeile" key={freigabe.feature}>
                                        <div>
                                            <strong>{freigabe.featureLabel}</strong>
                                            <span className="leise">
                                                {freigabe.dailyLimit > 0
                                                    ? `Tageslimit ${freigabe.dailyLimit}, heute ${freigabe.usedToday} benutzt`
                                                    : "ohne Tageslimit"}
                                                {freigabe.note ? ` · ${freigabe.note}` : ""}
                                            </span>
                                        </div>
                                        <button
                                            className={`knopf leise klein ${freigabe.enabled ? "ist-an" : ""}`}
                                            disabled={beschaeftigt !== null}
                                            onClick={() => umschalten(server.id, freigabe)}
                                        >
                                            {beschaeftigt === server.id + ":" + freigabe.feature
                                                ? "…"
                                                : freigabe.enabled ? "frei" : "gesperrt"}
                                        </button>
                                    </div>
                                ))}

                                <Serverstatistik
                                    guildId={server.id}
                                    pfad={`/api/admin/management/guilds/${server.id}/stats`}
                                />
                            </div>
                        )}
                    </div>
                ))}
            </div>

            {(liste || []).length === 0 && !fehler && (
                <div className="notiz">Der Bot ist auf keinem Server — jedenfalls auf keinem dieser Node.</div>
            )}
        </>
    );
}
