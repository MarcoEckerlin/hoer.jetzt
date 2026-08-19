import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";

/**
 * Die Audio-Knoten.
 *
 * <h2>Warum neu aufgebaut</h2>
 *
 * <p>Vorher war jeder Knoten eine grosse Karte mit fuenf Kacheln, einem
 * Fortschrittsbalken und einer Serverliste. Bei zwei Knoten ging das; ab vier
 * scrollte man an allem vorbei, was man eigentlich sehen wollte - naemlich
 * "ist alles da und antwortet es". Jetzt steht das oben in einer Zeile, und
 * die Einzelheiten holt man sich je Knoten dazu.</p>
 *
 * <p>Getrennt sind ausserdem <b>Zustand</b> und <b>Eingriff</b>: was ein Knoten
 * gerade tut, steht in der Zeile; was man mit ihm tun kann, klappt darunter
 * auf. Vorher lagen Knoepfe zwischen den Zahlen und man traf beim Ueberfliegen
 * versehentlich "Neu verbinden".</p>
 */
export default function Knoten() {
    const [knoten, setKnoten] = useState([]);
    const [netz, setNetz] = useState([]);
    const [autoscale, setAutoscale] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [laedt, setLaedt] = useState(true);
    const [beschaeftigt, setBeschaeftigt] = useState(null);
    const [offen, setOffen] = useState({});
    const [protokoll, setProtokoll] = useState(null);
    const [anlegen, setAnlegen] = useState(null);
    const [befehl, setBefehl] = useState(null);

    const laden = useCallback(async () => {
        try {
            const [k, a] = await Promise.all([
                api("GET", "/api/admin/audio/knoten"),
                api("GET", "/api/admin/audio/autoscale").catch(() => null)
            ]);
            setKnoten(k || []);
            setAutoscale(a);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        } finally {
            setLaedt(false);
        }
    }, []);

    // Die Messung laeuft getrennt und seltener: sie dauert bis zu drei
    // Sekunden, und sie an den 15-Sekunden-Takt zu haengen hiesse, dass die
    // Zahlen der Knoten auf sie warten.
    const netzMessen = useCallback(async () => {
        try {
            setNetz((await api("GET", "/api/admin/netz/erreichbarkeit")) || []);
        } catch {
            setNetz([]);
        }
    }, []);

    useEffect(() => {
        laden();
        netzMessen();
        const takt = setInterval(laden, 15000);
        const netzTakt = setInterval(netzMessen, 60000);
        return () => { clearInterval(takt); clearInterval(netzTakt); };
    }, [laden, netzMessen]);

    async function aktion(schluessel, pfad, koerper) {
        setBeschaeftigt(schluessel);
        setMeldung(null);
        try {
            const antwort = await api("POST", pfad, koerper);
            setMeldung({ art: "gut", text: antwort?.message || antwort?.meldung || "Erledigt." });
            await laden();
            return true;
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
            return false;
        } finally {
            setBeschaeftigt(null);
        }
    }

    async function autoscaleSchalten(an) {
        setBeschaeftigt("autoscale");
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/audio/autoscale", { enabled: an });
            setMeldung({ art: "gut", text: antwort?.message || "Erledigt." });
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setBeschaeftigt(null);
        }
    }

    /**
     * Holt den Installationsbefehl fuer einen Knoten auf fremder Hardware.
     *
     * <p>Er enthaelt die beiden Geheimnisse und die Adresse dieses Bots -
     * deshalb wird er beim Bot erzeugt und nicht in der Oberflaeche
     * zusammengebaut: dort laege das Lavalink-Passwort im Quelltext der Seite.</p>
     */
    async function befehlHolen(stufe) {
        setBefehl({ text: "…", stufe });
        try {
            const antwort = await api("GET", `/api/admin/audio/nodes/befehl?stufe=${stufe}`);
            setBefehl({ text: antwort?.befehl || "", hinweis: antwort?.hinweis, stufe });
        } catch (f) {
            setBefehl({ text: "", hinweis: f.message, stufe });
        }
    }

    /**
     * Entfernt einen Knoten.
     *
     * <p>Zwei Rueckfragen, nicht eine: die erste, weil ein Knoten mit laufender
     * Wiedergabe verschwindet; die zweite nur bei Hetzner-Knoten, weil ein
     * stehen gebliebener Server weiter Geld kostet und ein geloeschter nicht
     * zurueckkommt.</p>
     */
    async function entfernen(k) {
        if (!window.confirm(`Knoten "${k.name}" wirklich aus der Liste entfernen?`)) return;

        let mitServer = false;
        if (k.hetznerId) {
            mitServer = window.confirm(
                `Auch den Hetzner-Server ${k.hetznerId} löschen?\n\n`
                + "OK = Server wird gelöscht und kostet nichts mehr.\n"
                + "Abbrechen = nur der Eintrag geht weg, der Server läuft weiter.");
        }

        setBeschaeftigt("weg:" + k.name);
        setMeldung(null);
        try {
            const antwort = await api(
                "DELETE",
                `/api/admin/audio/nodes/${encodeURIComponent(k.name)}?server=${mitServer}`);
            setMeldung({ art: "gut", text: antwort?.message || "Entfernt." });
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setBeschaeftigt(null);
        }
    }

    async function protokollZeigen(name) {
        setProtokoll({ name, text: "…" });
        try {
            const antwort = await api("GET", `/api/admin/audio/nodes/${encodeURIComponent(name)}/log`);
            setProtokoll({ name, text: antwort?.protokoll || "Leer." });
        } catch (f) {
            setProtokoll({ name, text: f.message });
        }
    }

    if (laedt) return <div className="ladeschirm"><div className="puls" /></div>;

    const erreichbar = knoten.filter((k) => k.erreichbar).length;
    const spielend = knoten.reduce((summe, k) => summe + (k.spielend || 0), 0);
    const anmarsch = knoten.filter((k) => k.zustand === "anmarsch").length;

    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>Audio-Knoten</h1>
                    <p>{erreichbar} von {knoten.length} erreichbar · {spielend} spielen gerade
                        {anmarsch > 0 && ` · ${anmarsch} im Anmarsch`}</p>
                </div>
                <div className="kopf-knoepfe">
                    <button
                        className="knopf leise klein"
                        disabled={beschaeftigt !== null}
                        onClick={() => aktion("neu", "/api/admin/actions/reload-audio-nodes")}
                    >
                        {beschaeftigt === "neu" ? "…" : "Neu einlesen"}
                    </button>
                    <button
                        className="knopf leise klein"
                        disabled={beschaeftigt !== null}
                        onClick={() => aktion("verteilen", "/api/admin/actions/rebalance-audio")}
                    >
                        {beschaeftigt === "verteilen" ? "…" : "Neu verteilen"}
                    </button>
                    <button className="knopf leise klein" onClick={() => befehlHolen("free")}>
                        Manuell hinzufügen
                    </button>
                    <button className="knopf klein" onClick={() => setAnlegen({ stufe: "premium", code: "" })}>
                        Bei Hetzner anlegen
                    </button>
                </div>
            </header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && (
                <div className={`notiz ${meldung.art === "schlecht" ? "notiz-fehler" : "notiz-gut"}`}>
                    {meldung.text}
                </div>
            )}

            <Autoscaling lage={autoscale} schalten={autoscaleSchalten} beschaeftigt={beschaeftigt} />
            <Netz messungen={netz} beimMessen={netzMessen} />

            <section className="block">
                <h2 className="block-titel">Knoten</h2>
                <div className="knotenliste">
                    {knoten.map((k) => (
                        <Knotenzeile
                            key={k.name}
                            k={k}
                            entfernen={entfernen}
                            offen={!!offen[k.name]}
                            umschalten={() => setOffen({ ...offen, [k.name]: !offen[k.name] })}
                            beschaeftigt={beschaeftigt}
                            aktion={aktion}
                            protokollZeigen={protokollZeigen}
                        />
                    ))}
                </div>

                {knoten.length === 0 && (
                    <div className="notiz">
                        Kein Knoten eingetragen. Ein Knoten mit Agent trägt sich beim Start selbst
                        ein — dafür braucht er die Adresse dieses Bots und <code>HJ_NODE_TOKEN</code>.
                    </div>
                )}
            </section>

            {protokoll && (
                <Schicht titel={`Protokoll — ${protokoll.name}`} schliessen={() => setProtokoll(null)}>
                    <pre className="protokoll">{protokoll.text}</pre>
                </Schicht>
            )}

            {befehl && (
                <Schicht titel="Knoten auf eigener Hardware" schliessen={() => setBefehl(null)}>
                    <p className="leise">
                        Läuft auf jedem Server mit Debian oder Ubuntu — Hetzner ist nicht nötig.
                        Der Knoten meldet sich nach der Installation von selbst an und taucht dann
                        in dieser Liste auf.
                    </p>
                    <div className="listenzeile">
                        <button
                            className={`knopf leise klein ${befehl.stufe === "free" ? "ist-an" : ""}`}
                            onClick={() => befehlHolen("free")}
                        >
                            Standard
                        </button>
                        <button
                            className={`knopf leise klein ${befehl.stufe === "premium" ? "ist-an" : ""}`}
                            onClick={() => befehlHolen("premium")}
                        >
                            Premium
                        </button>
                    </div>
                    <pre className="protokoll">{befehl.text}</pre>
                    <div className="listenzeile">
                        <button
                            className="knopf klein"
                            onClick={() => navigator.clipboard?.writeText(befehl.text)}
                        >
                            In die Zwischenablage
                        </button>
                    </div>
                    {befehl.hinweis && <p className="feld-hilfe">{befehl.hinweis}</p>}
                    <p className="feld-hilfe">
                        Der Befehl enthält Zugangsdaten — nicht weitergeben und nicht in ein
                        öffentliches Ticket kopieren.
                    </p>
                </Schicht>
            )}

            {anlegen && (
                <Schicht titel="Knoten anlegen" schliessen={() => setAnlegen(null)}>
                    <p className="leise">
                        Legt bei Hetzner einen Server an, der sich selbst installiert und danach
                        von allein anmeldet. Das dauert einige Minuten und <strong>kostet Geld</strong> —
                        deshalb der zweite Faktor.
                    </p>
                    <label className="feld">
                        <span>Stufe</span>
                        <select
                            value={anlegen.stufe}
                            onChange={(e) => setAnlegen({ ...anlegen, stufe: e.target.value })}
                        >
                            <option value="premium">Premium</option>
                            <option value="free">Standard</option>
                        </select>
                    </label>
                    <label className="feld">
                        <span>Code aus der Authenticator-App</span>
                        <input
                            inputMode="numeric"
                            autoComplete="one-time-code"
                            maxLength={6}
                            value={anlegen.code}
                            onChange={(e) => setAnlegen({ ...anlegen, code: e.target.value })}
                        />
                    </label>
                    <div className="listenzeile">
                        <button
                            className="knopf"
                            disabled={anlegen.code.length !== 6 || beschaeftigt !== null}
                            onClick={async () => {
                                const geklappt = await aktion("anlegen", "/api/admin/audio/nodes/anlegen", {
                                    stufe: anlegen.stufe,
                                    code: anlegen.code
                                });
                                if (geklappt) setAnlegen(null);
                            }}
                        >
                            {beschaeftigt === "anlegen" ? "…" : "Anlegen"}
                        </button>
                        <button className="knopf leise" onClick={() => setAnlegen(null)}>Abbrechen</button>
                    </div>
                </Schicht>
            )}
        </>
    );
}

/* ------------------------------------------------------------------ Teile */

function Autoscaling({ lage, schalten, beschaeftigt }) {
    if (!lage) return null;

    // Ohne Token laesst sich nichts anlegen - dann waere der Schalter keine
    // Entscheidung, sondern eine Falle. Er bleibt sichtbar, aber gesperrt.
    const aus = !lage.eingeschaltet || !lage.tokenVorhanden;
    return (
        <section className={`streifen ${aus ? "ist-aus" : ""}`}>
            <div className="streifen-kopf">
                <span className={`ampel ${aus ? "ist-aus" : "ist-gut"}`} />
                <strong>Autoscaling</strong>
                <span className="marke">{aus ? "aus" : `ab ${Math.round(lage.schwelle * 100)} %`}</span>
                {!aus && <span className="marke">{lage.autoKnoten} / {lage.obergrenze} Knoten</span>}
                <button
                    className={`knopf leise klein ${lage.eingeschaltet ? "ist-an" : ""}`}
                    disabled={!lage.tokenVorhanden || beschaeftigt !== null}
                    title={lage.tokenVorhanden
                        ? "Gilt für den ganzen Verbund, nicht nur diese Node."
                        : "Ohne Hetzner-Token wirkungslos."}
                    onClick={() => schalten(!lage.eingeschaltet)}
                >
                    {beschaeftigt === "autoscale" ? "…" : lage.eingeschaltet ? "Ausschalten" : "Einschalten"}
                </button>
            </div>
            <p className="leise">
                {!lage.tokenVorhanden
                    ? "Kein Hetzner-Token hinterlegt (HJ_HETZNER_TOKEN) — es werden keine Server erzeugt."
                    : !lage.eingeschaltet
                        ? "Ausgeschaltet. Vorhandene Knoten bleiben; es kommen keine neuen dazu."
                        : lage.meldung}
            </p>
        </section>
    );
}

function Netz({ messungen, beimMessen }) {
    // Der Loadbalancer zuerst: ist er weg, ist die Oberflaeche fuer alle
    // ausserhalb nicht erreichbar - egal wie gut es den Knoten geht.
    const reihenfolge = { dienst: 0, knoten: 1, agent: 2 };
    const sortiert = [...messungen].sort(
        (a, b) => (reihenfolge[a.art] ?? 9) - (reihenfolge[b.art] ?? 9) || a.name.localeCompare(b.name)
    );

    return (
        <section className="block">
            <div className="block-kopf">
                <h2 className="block-titel">Erreichbarkeit</h2>
                <button className="knopf leise klein" onClick={beimMessen}>Neu messen</button>
            </div>

            {sortiert.length === 0
                ? <p className="leise">Wird gemessen …</p>
                : (
                    <div className="messungen">
                        {sortiert.map((m) => (
                            <div className={`messung ${m.erreichbar ? "" : "ist-weg"}`} key={`${m.art}:${m.name}`}>
                                <span className={`ampel ${m.erreichbar ? ampelStufe(m.ms) : "ist-weg"}`} />
                                <span className="messung-name">{m.name}</span>
                                <span className="messung-ziel einfarbig leise">{m.ziel}</span>
                                <span className="messung-wert">
                                    {m.erreichbar ? `${m.ms} ms` : (m.meldung || "keine Antwort")}
                                </span>
                            </div>
                        ))}
                    </div>
                )}
        </section>
    );
}

function Knotenzeile({ k, offen, umschalten, beschaeftigt, aktion, protokollZeigen, entfernen }) {
    const arbeitet = beschaeftigt !== null;
    const pfad = `/api/admin/audio/nodes/${encodeURIComponent(k.name)}`;

    return (
        <div className={`knotenzeile ${offen ? "ist-offen" : ""} zustand-${k.zustand}`}>
            <button className="knotenzeile-kopf" onClick={umschalten}>
                <span className={`ampel ${ampelFuer(k)}`} />

                <span className="knotenzeile-name">
                    <strong>{k.name}</strong>
                    <span className="einfarbig leise">{k.adresse || "noch keine Adresse"}</span>
                </span>

                <span className="knotenzeile-marken">
                    <span className={`marke ${k.stufe === "premium" ? "gold" : ""}`}>{k.stufe}</span>
                    <Herkunft wert={k.herkunft} />
                    {!k.hatAgent && <span className="marke" title="Ohne Agent gibt es nur „Neu verbinden“.">kein Agent</span>}
                </span>

                <span className="knotenzeile-zahlen">
                    <Zahl titel="Server" wert={`${k.gesamt}${k.obergrenze > 0 ? ` / ${k.obergrenze}` : ""}`} />
                    <Zahl titel="Spielt" wert={k.spielend} />
                    <Zahl titel="CPU" wert={`${Math.round((k.cpuLast || 0) * 100)} %`} />
                    <Zahl titel="Laufzeit" wert={dauer(k.laufzeitSekunden)} />
                </span>

                <span className="knotenzeile-pfeil">{offen ? "▾" : "▸"}</span>
            </button>

            <div className="balken">
                <div
                    className={`balken-fuell ${(k.cpuLast || 0) > 0.85 ? "heiss" : ""}`}
                    style={{ width: `${Math.min(100, Math.round((k.cpuLast || 0) * 100))}%` }}
                />
            </div>

            {offen && (
                <div className="knotenzeile-inhalt">
                    {k.zustand === "anmarsch" && (
                        <div className="notiz">
                            Der Server wird gerade erzeugt und installiert sich selbst. Er meldet sich
                            in wenigen Minuten — bis dahin steht hier keine Adresse.
                        </div>
                    )}

                    <div className="listenzeile">
                        <button
                            className="knopf leise klein"
                            disabled={arbeitet}
                            onClick={() => aktion(k.name + ":verbinden", `${pfad}/reconnect`)}
                        >
                            {beschaeftigt === k.name + ":verbinden" ? "…" : "Neu verbinden"}
                        </button>

                        <button
                            className="knopf leise klein"
                            disabled={arbeitet || !k.hatAgent}
                            title={k.hatAgent ? "Startet den Container auf dem Host neu." : "Nur mit Agent möglich."}
                            onClick={() => aktion(k.name + ":neustart", `${pfad}/restart`)}
                        >
                            {beschaeftigt === k.name + ":neustart" ? "…" : "Container neu starten"}
                        </button>

                        <button
                            className="knopf leise klein"
                            disabled={arbeitet || !k.hatAgent}
                            title={k.hatAgent ? "Holt den Zweig, baut neu, startet." : "Nur mit Agent möglich."}
                            onClick={() => aktion(k.name + ":update", `${pfad}/update`)}
                        >
                            {beschaeftigt === k.name + ":update" ? "…" : "Aktualisieren"}
                        </button>

                        {k.hatAgent && (
                            <button className="knopf leise klein" onClick={() => protokollZeigen(k.name)}>
                                Protokoll
                            </button>
                        )}

                        {/*
                          Ganz rechts und optisch abgesetzt: die einzige Aktion
                          hier, die nichts wiederherstellt.
                        */}
                        <button
                            className="knopf leise klein ist-gefaehrlich"
                            disabled={arbeitet}
                            title={k.hetznerId
                                ? "Entfernt den Eintrag - der Hetzner-Server wird auf Rueckfrage mitgeloescht."
                                : "Entfernt den Eintrag aus der Liste."}
                            onClick={() => entfernen(k)}
                        >
                            {beschaeftigt === "weg:" + k.name ? "…" : "Entfernen"}
                        </button>
                    </div>

                    <dl className="paare">
                        <div><dt>Strafpunkte</dt><dd>{k.strafpunkte < 0 ? "—" : k.strafpunkte}</dd></div>
                        {k.hetznerId && <div><dt>Hetzner-ID</dt><dd>{k.hetznerId}</dd></div>}
                        {k.zuletztGesehen && <div><dt>Zuletzt gemeldet</dt><dd>{zeit(k.zuletztGesehen)}</dd></div>}
                    </dl>

                    {(k.server || []).length > 0 ? (
                        <div className="tabelle">
                            <div className="zeile zeile-kopf">
                                <span>Server</span><span>Stufe</span><span>Wiedergabe</span><span></span>
                            </div>
                            {k.server.map((s) => (
                                <div className="zeile" key={s.guildId}>
                                    <span>{s.name || s.guildId}</span>
                                    <span>{s.stufe}</span>
                                    <span>{s.spielt ? (s.titel || "spielt") : "still"}</span>
                                    <span>
                                        {!s.passtZurStufe && (
                                            <span className="marke warn" title="Liegt auf einem Knoten anderer Stufe – meist ein Ausweichen nach einem Ausfall.">
                                                ausgewichen
                                            </span>
                                        )}
                                    </span>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <p className="leise">Kein Server auf diesem Knoten.</p>
                    )}
                </div>
            )}
        </div>
    );
}

function Herkunft({ wert }) {
    const texte = {
        auto: ["automatisch", "Vom Autoscaling erzeugt — wird bei Leerlauf wieder abgebaut."],
        selbst: ["selbst angemeldet", "Hat sich beim Start selbst eingetragen."],
        manuell: ["von Hand", "Im Adminbereich eingetragen."],
        konfiguration: ["aus der Konfiguration", "Kommt aus der Deployment-Konfiguration, nicht aus der Knotentabelle."]
    };
    const [text, titel] = texte[wert] || [wert, ""];
    return <span className="marke leise" title={titel}>{text}</span>;
}

function Zahl({ titel, wert }) {
    return (
        <span className="zahl">
            <span className="zahl-titel">{titel}</span>
            <strong>{wert}</strong>
        </span>
    );
}

function Schicht({ titel, schliessen, children }) {
    // Escape schliesst. Ohne das sucht man bei einem versehentlich geoeffneten
    // Fenster erst den Knopf - und findet ihn bei einem langen Protokoll erst
    // nach dem Scrollen.
    useEffect(() => {
        const beim = (e) => e.key === "Escape" && schliessen();
        window.addEventListener("keydown", beim);
        return () => window.removeEventListener("keydown", beim);
    }, [schliessen]);

    return (
        <div className="schicht" onClick={schliessen}>
            <div className="schicht-fenster" onClick={(e) => e.stopPropagation()}>
                <div className="schicht-kopf">
                    <h2>{titel}</h2>
                    <button className="knopf leise klein" onClick={schliessen}>Schließen</button>
                </div>
                {children}
            </div>
        </div>
    );
}

/* ------------------------------------------------------------------ Hilfen */

function ampelFuer(k) {
    if (k.zustand === "anmarsch") return "ist-warten";
    return k.erreichbar ? "ist-gut" : "ist-weg";
}

function ampelStufe(ms) {
    if (ms < 0) return "ist-weg";
    if (ms <= 50) return "ist-gut";
    if (ms <= 250) return "ist-warten";
    return "ist-lahm";
}

function dauer(sekunden) {
    if (!sekunden) return "—";
    const tage = Math.floor(sekunden / 86400);
    if (tage > 0) return `${tage} d`;
    const stunden = Math.floor(sekunden / 3600);
    if (stunden > 0) return `${stunden} h`;
    return `${Math.floor(sekunden / 60)} min`;
}

function zeit(iso) {
    try {
        return new Date(iso).toLocaleString("de-DE", { dateStyle: "short", timeStyle: "short" });
    } catch {
        return iso;
    }
}
