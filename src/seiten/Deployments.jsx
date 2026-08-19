import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Feld, Text, Zahl, Schalter } from "../teile/felder.jsx";

/**
 * Deployments - die Instanzen, unter denen dieser Bot erreichbar ist.
 *
 * <p>Bisher lagen Deployments und Audio-Knoten im alten Adminbereich, der
 * Verbund und die Knotenauslastung hier. Wer wissen wollte, warum ein Server
 * auf einem bestimmten Knoten landet, musste zwischen zwei Oberflaechen hin
 * und her springen - und die eine sah aus wie 2019, die andere wie heute.</p>
 *
 * <p>Deshalb wandert die Pflege dorthin, wo auch der Zustand steht. Gespeichert
 * wird ueber denselben Endpunkt wie bisher; geschickt wird nur die Liste der
 * Deployments. Alle anderen Felder bleiben null, und null heisst dort
 * ausdruecklich "unveraendert lassen" - sonst wuerde ein Klick hier die halbe
 * Instanzkonfiguration leeren.</p>
 */
export default function Deployments() {
    const [konfig, setKonfig] = useState(null);
    const [liste, setListe] = useState([]);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [sendet, setSendet] = useState(false);
    const [geaendert, setGeaendert] = useState(false);

    const laden = useCallback(async () => {
        try {
            const c = await api("GET", "/api/admin/config");
            setKonfig(c);
            setListe((c.deployments || []).map((d) => ({ ...d })));
            setGeaendert(false);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, []);

    useEffect(() => { laden(); }, [laden]);

    function aendern(index, feld, wert) {
        setListe((alt) => alt.map((e, i) => (i === index ? { ...e, [feld]: wert } : e)));
        setGeaendert(true);
    }

    function hinzufuegen() {
        setListe((alt) => [...alt, {
            deploymentKey: "",
            displayName: "",
            webPort: null,
            baseUrl: "",
            redirectUri: "",
            enabled: true,
            sortOrder: alt.length
        }]);
        setGeaendert(true);
    }

    function entfernen(index) {
        setListe((alt) => alt.filter((_, i) => i !== index));
        setGeaendert(true);
    }

    async function speichern() {
        // Der Schluessel ist die Kennung, unter der eine Instanz ihre Knoten
        // und ihre Adresse findet. Leer gespeichert waere die Zeile unsichtbar
        // und trotzdem da - deshalb hier und nicht erst im Bot pruefen.
        const ohneSchluessel = liste.filter((e) => !(e.deploymentKey || "").trim());
        if (ohneSchluessel.length > 0) {
            setMeldung({ art: "schlecht", text: "Jedes Deployment braucht einen Schlüssel." });
            return;
        }

        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/config", {
                deployments: liste.map((e, i) => ({
                    deploymentKey: (e.deploymentKey || "").trim(),
                    displayName: e.displayName || "",
                    webPort: e.webPort === "" || e.webPort === null ? null : Number(e.webPort),
                    baseUrl: e.baseUrl || "",
                    redirectUri: e.redirectUri || "",
                    enabled: e.enabled !== false,
                    sortOrder: i
                }))
            });
            setMeldung({ art: "gut", text: antwort?.message || "Gespeichert." });
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setSendet(false);
        }
    }

    if (!konfig && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>Deployments</h1>
                    <p>
                        Jede Instanz dieses Bots hat einen Schlüssel. Über ihn findet sie ihre
                        Audio-Knoten und ihre öffentliche Adresse.
                    </p>
                </div>
                <div className="kopf-knoepfe">
                    <button className="knopf leise klein" onClick={hinzufuegen}>Hinzufügen</button>
                </div>
            </header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && (
                <div className={`notiz ${meldung.art === "schlecht" ? "notiz-fehler" : "notiz-gut"}`}>
                    {meldung.text}
                </div>
            )}

            {konfig && (
                <section className="streifen">
                    <div className="streifen-kopf">
                        <span className="ampel ist-gut" />
                        <strong>Diese Instanz</strong>
                        <span className="marke">{konfig.currentDeploymentKey || "ohne Schlüssel"}</span>
                        {konfig.currentDeploymentDisplayName && (
                            <span className="marke leise">{konfig.currentDeploymentDisplayName}</span>
                        )}
                    </div>
                    <p className="leise">
                        Erkannt aus der Konfiguration dieses Prozesses — nicht aus der Liste unten.
                        Steht der Schlüssel dort nicht, findet diese Instanz keine eigenen Knoten.
                    </p>

                    {/*
                      Der haeufigste Fall nach einer Neuinstallation: die Instanz
                      laeuft, ihr Schluessel steht aber in keiner Zeile - und dann
                      sucht man die Knoten. Statt es nur zu melden, gibt es hier
                      den Eintrag mit einem Klick, vorbelegt aus dem, was der
                      Prozess ohnehin ueber sich weiss.
                    */}
                    {konfig.currentDeploymentKey
                        && !liste.some((e) => e.deploymentKey === konfig.currentDeploymentKey) && (
                        <div className="listenzeile">
                            <span className="marke ist-warnung">nicht eingetragen</span>
                            <button
                                className="knopf leise klein"
                                onClick={() => {
                                    setListe((alt) => [...alt, {
                                        deploymentKey: konfig.currentDeploymentKey,
                                        displayName: konfig.currentDeploymentDisplayName || "",
                                        webPort: null,
                                        baseUrl: konfig.webBaseUrl || "",
                                        redirectUri: "",
                                        enabled: true,
                                        sortOrder: alt.length
                                    }]);
                                    setGeaendert(true);
                                }}
                            >
                                Diese Instanz übernehmen
                            </button>
                        </div>
                    )}
                </section>
            )}

            <div className="knotenliste">
                {liste.map((eintrag, index) => (
                    <div
                        className={`knotenzeile ${eintrag.enabled === false ? "zustand-still" : ""}`}
                        key={index}
                    >
                        <div className="knotenzeile-kopf" style={{ cursor: "default" }}>
                            <span className={`ampel ${eintrag.enabled === false ? "ist-aus" : "ist-gut"}`} />
                            <span className="knotenzeile-name">
                                <strong>{eintrag.displayName || eintrag.deploymentKey || "Neu"}</strong>
                                <span className="einfarbig leise">{eintrag.baseUrl || "keine Adresse"}</span>
                            </span>
                            <span className="knotenzeile-marken">
                                {eintrag.deploymentKey === konfig?.currentDeploymentKey && (
                                    <span className="marke ist-gut" title="Der Schlüssel, unter dem dieser Prozess läuft.">
                                        aktiv
                                    </span>
                                )}
                            </span>
                            <button className="knopf leise klein" onClick={() => entfernen(index)}>
                                Entfernen
                            </button>
                        </div>

                        <div className="knotenzeile-inhalt">
                            <div className="feldgitter">
                                <Feld
                                    titel="Schlüssel"
                                    hilfe="Eindeutig je Instanz. Wird nirgends angezeigt, nur zugeordnet."
                                    kind={<Text
                                        wert={eintrag.deploymentKey || ""}
                                        setzen={(w) => aendern(index, "deploymentKey", w)}
                                        platzhalter="standard"
                                    />}
                                />
                                <Feld
                                    titel="Anzeigename"
                                    hilfe="Nur für dich, damit die Liste lesbar bleibt."
                                    kind={<Text
                                        wert={eintrag.displayName || ""}
                                        setzen={(w) => aendern(index, "displayName", w)}
                                        platzhalter="Produktiv"
                                    />}
                                />
                            </div>

                            <div className="feldgitter">
                                <Feld
                                    titel="Öffentliche Adresse"
                                    hilfe="Bestimmt, wohin Discord nach der Anmeldung zurückschickt."
                                    kind={<Text
                                        wert={eintrag.baseUrl || ""}
                                        setzen={(w) => aendern(index, "baseUrl", w)}
                                        platzhalter="https://hoer.jetzt"
                                    />}
                                />
                                <Feld
                                    titel="Web-Port"
                                    hilfe="Leer lassen, wenn der Vorgabewert gilt."
                                    kind={<Zahl
                                        wert={eintrag.webPort ?? ""}
                                        setzen={(w) => aendern(index, "webPort", w)}
                                        min={1}
                                        max={65535}
                                    />}
                                />
                            </div>

                            <Feld
                                titel="Redirect-URI"
                                breit
                                hilfe="Muss genauso im Discord-Entwicklerportal stehen. Leer = aus der öffentlichen Adresse abgeleitet."
                                kind={<Text
                                    wert={eintrag.redirectUri || ""}
                                    setzen={(w) => aendern(index, "redirectUri", w)}
                                    platzhalter="https://hoer.jetzt/auth/discord/callback"
                                />}
                            />

                            <Schalter
                                an={eintrag.enabled !== false}
                                setzen={(w) => aendern(index, "enabled", w)}
                                titel="Aktiv"
                                hilfe="Ausgeschaltet bleibt der Eintrag erhalten, wird aber nicht benutzt."
                            />
                        </div>
                    </div>
                ))}

                {liste.length === 0 && (
                    <div className="notiz">
                        Kein Deployment eingetragen. Ohne Eintrag findet diese Instanz keine
                        Audio-Knoten — sie werden über den Schlüssel zugeordnet.
                    </div>
                )}
            </div>

            {geaendert && (
                <div className="listenzeile">
                    <button className="knopf" disabled={sendet} onClick={speichern}>
                        {sendet ? "…" : "Speichern"}
                    </button>
                    <button className="knopf leise" disabled={sendet} onClick={laden}>
                        Verwerfen
                    </button>
                </div>
            )}
        </>
    );
}
