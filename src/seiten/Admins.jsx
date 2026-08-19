import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Feld, Text, Auswahl } from "../teile/felder.jsx";

/**
 * Wer den Bot verwalten darf.
 *
 * <p>Diese Seite gab es nur im alten Adminbereich. Das war lange folgenlos -
 * bis zu dem Abend, an dem niemand mehr hineinkam und der einzige Weg zurueck
 * ein INSERT von Hand in <code>bot_admins</code> war. Eine Verwaltung, die man
 * nur erreicht, wenn sie funktioniert, ist genau dann weg, wenn man sie
 * braucht.</p>
 *
 * <p>Die Stufe <b>Owner</b> steht bewusst nicht zur Auswahl: sie kommt aus der
 * Discord-Anwendung und wird beim ersten Zugriff uebernommen. Wer sie von Hand
 * vergeben koennte, koennte sich auch selbst hochstufen.</p>
 */
export default function Admins() {
    const [daten, setDaten] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [beschaeftigt, setBeschaeftigt] = useState(null);
    const [neu, setNeu] = useState({ userId: "", displayName: "", role: "" });

    // Zweiter Faktor. Er gilt nur fuer eine einzige Sache: einen Knoten von
    // Hand bei Hetzner anlegen. Das Autoscaling laeuft ohne, weil es nachts
    // niemanden fragen kann - der Knopf hier dagegen kostet Geld auf Zuruf.
    const [zweiFaktor, setZweiFaktor] = useState(null);
    const [einrichtung, setEinrichtung] = useState(null);
    const [pruefcode, setPruefcode] = useState("");

    const laden = useCallback(async () => {
        try {
            const d = await api("GET", "/api/admin/management/admins");
            setDaten(d);
            setNeu((alt) => ({ ...alt, role: alt.role || d.assignableRoles?.[0]?.key || "" }));
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, []);

    useEffect(() => { laden(); }, [laden]);

    useEffect(() => {
        api("GET", "/api/admin/2fa").then(setZweiFaktor).catch(() => setZweiFaktor(null));
    }, []);

    async function faktorEinrichten() {
        setBeschaeftigt("2fa");
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/2fa/einrichten");
            setEinrichtung(antwort?.otpauth || "");
            setPruefcode("");
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setBeschaeftigt(null);
        }
    }

    async function faktorBestaetigen() {
        setBeschaeftigt("2fa-pruefen");
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/2fa/pruefen", { code: pruefcode });
            setMeldung({ art: "gut", text: antwort?.message || "Eingerichtet." });
            setEinrichtung(null);
            setPruefcode("");
            setZweiFaktor(await api("GET", "/api/admin/2fa"));
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setBeschaeftigt(null);
        }
    }

    async function speichern(eintrag, schluessel) {
        setBeschaeftigt(schluessel);
        setMeldung(null);
        try {
            const antwort = await api("POST", "/api/admin/management/admins", eintrag);
            setMeldung({ art: "gut", text: antwort?.message || "Gespeichert." });
            await laden();
            return true;
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
            return false;
        } finally {
            setBeschaeftigt(null);
        }
    }

    async function entfernen(userId) {
        setBeschaeftigt("weg:" + userId);
        setMeldung(null);
        try {
            const antwort = await api("DELETE", `/api/admin/management/admins/${encodeURIComponent(userId)}`);
            setMeldung({ art: "gut", text: antwort?.message || "Entfernt." });
            await laden();
        } catch (f) {
            setMeldung({ art: "schlecht", text: f.message });
        } finally {
            setBeschaeftigt(null);
        }
    }

    if (!daten && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    const rollen = daten?.assignableRoles || [];
    const darfVerwalten = daten?.canManageAdmins;

    return (
        <>
            <header className="modulkopf">
                <div>
                    <h1>Bot-Verwaltung</h1>
                    <p>
                        Wer hier steht, darf auf jedem Server alles — unabhängig von seinen
                        Discord-Rollen dort.
                    </p>
                </div>
            </header>

            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && (
                <div className={`notiz ${meldung.art === "schlecht" ? "notiz-fehler" : "notiz-gut"}`}>
                    {meldung.text}
                </div>
            )}

            {!darfVerwalten && (
                <div className="notiz">
                    Du kannst die Liste sehen, aber nicht ändern — dafür braucht es die Stufe Owner.
                </div>
            )}

            <div className="knotenliste">
                {(daten?.admins || []).map((eintrag) => (
                    <div className="knotenzeile" key={eintrag.userId}>
                        <div className="knotenzeile-kopf" style={{ cursor: "default" }}>
                            <span className={`ampel ${eintrag.role === "OWNER" ? "ist-gut" : "ist-warten"}`} />
                            <span className="knotenzeile-name">
                                <strong>{eintrag.displayName || "ohne Namen"}</strong>
                                <span className="einfarbig leise">{eintrag.userId}</span>
                            </span>
                            <span className="knotenzeile-marken">
                                <span className={`marke ${eintrag.role === "OWNER" ? "gold" : ""}`}>
                                    {eintrag.role}
                                </span>
                                {eintrag.userId === daten.currentUserId && <span className="marke">du</span>}
                                {eintrag.applicationOwner && (
                                    <span className="marke leise" title="Kommt aus der Discord-Anwendung und lässt sich nicht entfernen.">
                                        Anwendungseigentümer
                                    </span>
                                )}
                            </span>
                            {/* Nicht bei der eigenen Zeile: der Server lehnt die
                                Selbstentfernung ohnehin ab, und ein Knopf, der
                                immer in eine Fehlermeldung laeuft, sieht aus wie
                                ein Fehler statt wie eine Regel. */}
                            {darfVerwalten
                                && !eintrag.applicationOwner
                                && eintrag.userId !== daten.currentUserId && (
                                <button
                                    className="knopf leise klein"
                                    disabled={beschaeftigt !== null}
                                    onClick={() => entfernen(eintrag.userId)}
                                >
                                    {beschaeftigt === "weg:" + eintrag.userId ? "…" : "Entfernen"}
                                </button>
                            )}
                        </div>
                    </div>
                ))}

                {(daten?.admins || []).length === 0 && (
                    <div className="notiz">
                        Niemand eingetragen. Der Eigentümer der Discord-Anwendung wird beim ersten
                        Zugriff automatisch übernommen.
                    </div>
                )}
            </div>

            {darfVerwalten && (
                <section className="karte-flach">
                    <h2>Hinzufügen</h2>
                    <div className="feldgitter">
                        <Feld
                            titel="Discord-Benutzer-ID"
                            hilfe="Nur Ziffern. In Discord: Entwicklermodus einschalten, Rechtsklick auf den Benutzer, „ID kopieren“."
                            kind={<Text
                                wert={neu.userId}
                                setzen={(w) => setNeu({ ...neu, userId: w.replace(/\D/g, "") })}
                                platzhalter="595602901653520384"
                            />}
                        />
                        <Feld
                            titel="Name"
                            hilfe="Nur für die Liste hier."
                            kind={<Text
                                wert={neu.displayName}
                                setzen={(w) => setNeu({ ...neu, displayName: w })}
                                platzhalter="Marco"
                            />}
                        />
                        <Feld
                            titel="Stufe"
                            hilfe={rollen.map((r) => `${r.label}: ${r.description}`).join(" · ")}
                            kind={<Auswahl
                                wert={neu.role}
                                setzen={(w) => setNeu({ ...neu, role: w })}
                                liste={rollen.map((r) => ({ id: r.key, name: r.label }))}
                                leerText="— Stufe wählen —"
                            />}
                        />
                    </div>
                    <div className="listenzeile">
                        <button
                            className="knopf"
                            disabled={neu.userId.length < 5 || !neu.role || beschaeftigt !== null}
                            onClick={async () => {
                                if (await speichern(neu, "neu")) {
                                    setNeu({ userId: "", displayName: "", role: neu.role });
                                }
                            }}
                        >
                            {beschaeftigt === "neu" ? "…" : "Eintragen"}
                        </button>
                    </div>
                </section>
            )}

            {/*
              Der zweite Faktor hatte bisher Endpunkte, aber keine Oberflaeche -
              einrichten liess er sich damit gar nicht, und der Knopf "Knoten
              anlegen" lief ins Leere.
            */}
            <section className="karte-flach">
                <h2>Zweiter Faktor</h2>
                <p className="leise">
                    Wird an genau einer Stelle verlangt: wenn du unter <em>Audio-Knoten</em>
                    {" "}von Hand einen Server bei Hetzner anlegst. Das Autoscaling läuft ohne —
                    es muss auf Last reagieren und kann nachts niemanden fragen. Ein übernommener
                    Adminzugang wäre sonst gleichbedeutend mit einer offenen Kreditkarte.
                </p>

                {zweiFaktor?.eingerichtet && !einrichtung && (
                    <div className="listenzeile">
                        <span className="marke ist-gut">eingerichtet</span>
                        <button className="knopf leise klein" disabled={beschaeftigt !== null} onClick={faktorEinrichten}>
                            Neu einrichten
                        </button>
                    </div>
                )}

                {zweiFaktor && !zweiFaktor.eingerichtet && !einrichtung && (
                    <div className="listenzeile">
                        <span className="marke ist-warnung">nicht eingerichtet</span>
                        <button className="knopf" disabled={beschaeftigt !== null} onClick={faktorEinrichten}>
                            {beschaeftigt === "2fa" ? "…" : "Jetzt einrichten"}
                        </button>
                    </div>
                )}

                {einrichtung !== null && (
                    <div className="karte-eingebettet">
                        <h3>1. In die App eintragen</h3>
                        <p className="leise">
                            Authenticator-App öffnen (Aegis, 2FAS, Google Authenticator, 1Password …),
                            „Konto manuell hinzufügen“ wählen und diesen Schlüssel eintippen:
                        </p>
                        <p className="einfarbig geheimnis">{geheimnisAus(einrichtung)}</p>
                        <p className="feld-hilfe">
                            Zeitbasiert (TOTP), 6 Stellen, 30 Sekunden — das sind überall die
                            Vorgaben. Wer lieber kopiert, nimmt die vollständige Adresse:
                        </p>
                        <p className="einfarbig leise umbrechen">{einrichtung}</p>

                        <h3>2. Bestätigen</h3>
                        <p className="leise">
                            Der Schlüssel wird <strong>nur jetzt</strong> angezeigt und nirgends
                            wieder. Ohne die Bestätigung unten bleibt er ungeprüft — und im
                            Ernstfall stellt sich erst dann heraus, dass die App etwas anderes
                            gespeichert hat.
                        </p>
                        <div className="listenzeile">
                            <input
                                className="eingabe"
                                style={{ maxWidth: 140 }}
                                inputMode="numeric"
                                placeholder="123456"
                                value={pruefcode}
                                onChange={(e) => setPruefcode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                            />
                            <button
                                className="knopf"
                                disabled={pruefcode.length !== 6 || beschaeftigt !== null}
                                onClick={faktorBestaetigen}
                            >
                                {beschaeftigt === "2fa-pruefen" ? "…" : "Bestätigen"}
                            </button>
                            <button
                                className="knopf leise"
                                disabled={beschaeftigt !== null}
                                onClick={() => { setEinrichtung(null); setPruefcode(""); }}
                            >
                                Abbrechen
                            </button>
                        </div>
                    </div>
                )}
            </section>
        </>
    );
}

/** Der reine Schlüssel aus der otpauth-Adresse - das ist es, was man abtippt. */
function geheimnisAus(otpauth) {
    const treffer = /[?&]secret=([^&]+)/i.exec(otpauth || "");
    if (!treffer) return "";
    // In Vierergruppen: 32 Zeichen am Stück tippt sich niemand fehlerfrei ab.
    return treffer[1].replace(/(.{4})/g, "$1 ").trim();
}
