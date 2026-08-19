import React, { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api.js";
import { Modulseite } from "./rahmen.jsx";
import { Feld, Text } from "../teile/felder.jsx";
import Bildfeld from "../teile/Bildfeld.jsx";

/**
 * Radiosender.
 *
 * <p>Eigene Seite statt eines Reiters in der Wiedergabe: die Senderliste ist
 * lang, die Fernbedienung ist es nicht. Zusammengelegt haette eines von beiden
 * gescrollt werden muessen.</p>
 *
 * <p>Die Liste wird <em>mit</em> Server-Bezug geholt. Ohne ihn laesst der Bot
 * das AI-Radio bewusst weg - die Freigabe gilt je Server, und ohne zu wissen um
 * welchen es geht, waere jede Antwort geraten.</p>
 *
 * <h2>Zwei Herkuenfte, zwei Listen</h2>
 *
 * <p>Oben stehen alle Sender, die dieser Server hoeren kann - globale und
 * eigene gemischt, weil beim Hoeren niemand nach der Herkunft fragt. Unten
 * stehen nur die eigenen, denn nur die darf dieser Server auch aendern. Eine
 * gemeinsame Liste haette bei der Haelfte der Zeilen keinen Loeschknopf, und
 * das sieht nach einem Fehler aus statt nach einer Regel.</p>
 */
export default function Radio({ guildId, konfig }) {
    const [sender, setSender] = useState([]);
    const [eigene, setEigene] = useState([]);
    const [laeuft, setLaeuft] = useState(null);
    const [aktiv, setAktiv] = useState(null);
    const [meldung, setMeldung] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [suche, setSuche] = useState("");
    const [entwurf, setEntwurf] = useState(null);
    const [sendet, setSendet] = useState(false);

    const laden = useCallback(async () => {
        try {
            const [alle, meine] = await Promise.all([
                api("GET", `/api/dashboard/radio/stations?guildId=${guildId}`),
                api("GET", `/api/dashboard/guilds/${guildId}/radio`).catch(() => [])
            ]);
            setSender(alle || []);
            setEigene(meine || []);
            setFehler(null);
        } catch (f) {
            setFehler(f.message);
        }
    }, [guildId]);

    useEffect(() => {
        laden();
        api("GET", `/api/dashboard/guilds/${guildId}/player`)
            .then((d) => setAktiv(d?.playingRadio ? d.activeRadioName : null))
            .catch(() => {});
    }, [guildId, laden]);

    async function starten(s) {
        setLaeuft(s.id);
        setMeldung(null);
        try {
            const antwort = await api("POST", `/api/dashboard/guilds/${guildId}/player/radio`, {
                radioId: s.id,
                voiceChannelId: null
            });
            setMeldung(antwort?.message || `${s.name} läuft.`);
            if (antwort?.success !== false) setAktiv(s.name);
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setLaeuft(null);
        }
    }

    async function speichern() {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("POST", `/api/dashboard/guilds/${guildId}/radio`, {
                id: entwurf.id ?? null,
                name: entwurf.name,
                url: entwurf.url,
                logoUrl: entwurf.logoUrl
            });
            setMeldung(antwort?.message || "Gespeichert.");
            setEntwurf(null);
            await laden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setSendet(false);
        }
    }

    async function entfernen(s) {
        setSendet(true);
        setMeldung(null);
        try {
            const antwort = await api("DELETE", `/api/dashboard/guilds/${guildId}/radio/${s.id}`);
            setMeldung(antwort?.message || "Entfernt.");
            await laden();
        } catch (f) {
            setMeldung(f.message);
        } finally {
            setSendet(false);
        }
    }

    const gefiltert = suche
        ? sender.filter((s) => s.name.toLowerCase().includes(suche.toLowerCase()))
        : sender;

    return (
        <Modulseite
            titel="Radio"
            hilfe="Ein Sender läuft dauerhaft, bis jemand ihn stoppt — keine Warteschlange, kein Ende."
            kopfzusatz={
                sender.length > 8 && (
                    <input
                        className="eingabe eingabe-suche"
                        placeholder="Sender suchen…"
                        value={suche}
                        onChange={(e) => setSuche(e.target.value)}
                    />
                )
            }
        >
            {fehler && <div className="notiz notiz-fehler">{fehler}</div>}
            {meldung && <div className="notiz">{meldung}</div>}
            {aktiv && <div className="notiz">Gerade läuft <strong>{aktiv}</strong>.</div>}

            {/*
              Hier stand ein Hinweis, dass das AI-Radio nicht freigeschaltet
              sei. Er ist weg: wer die Freigabe nicht hat, soll von der
              Funktion gar nichts mitbekommen - sonst wirbt die Seite fuer
              etwas, das derselbe Nutzer nicht bekommen kann, und macht aus
              einem vollstaendigen Radio ein halbes.
            */}

            <section className="karte-flach">
                <div className="senderliste">
                    {gefiltert.map((s) => (
                        <button
                            key={s.id}
                            className={`sender ${aktiv === s.name ? "ist-an" : ""}`}
                            onClick={() => starten(s)}
                            disabled={laeuft !== null}
                        >
                            <Senderbild sender={s} />
                            <span className="sender-text">
                                <strong>{s.name}</strong>
                                <span className="leise">
                                    {laeuft === s.id ? "verbindet…" : s.guildId ? "eigener Sender" : " "}
                                </span>
                            </span>
                        </button>
                    ))}
                </div>

                {sender.length === 0 && !fehler && (
                    <p className="leise">
                        Noch kein Sender. Trag unten deinen ersten ein — oder warte auf die
                        globalen, die wir pflegen.
                    </p>
                )}
                {sender.length > 0 && gefiltert.length === 0 && <p className="leise">Nichts gefunden.</p>}
            </section>

            <section className="karte-flach">
                <div className="listenzeile listenzeile-kopf">
                    <div className="listenzeile-text">
                        <h2>Eigene Sender</h2>
                        <span className="leise">
                            Nur auf diesem Server sichtbar. Die Adresse muss der direkte Stream
                            sein, nicht die Webseite des Senders.
                        </span>
                    </div>
                    <button
                        className="knopf leise klein"
                        onClick={() => setEntwurf({ id: null, name: "", url: "", logoUrl: "" })}
                        disabled={entwurf !== null}
                    >
                        Hinzufügen
                    </button>
                </div>

                {eigene.map((s) => (
                    <div className="listenzeile" key={s.id}>
                        <Senderbild sender={s} />
                        <div className="listenzeile-text">
                            <strong>{s.name}</strong>
                            <span className="einfarbig leise">{s.url}</span>
                        </div>
                        <button
                            className="knopf leise klein"
                            disabled={sendet}
                            onClick={() => setEntwurf({ ...s })}
                        >
                            Bearbeiten
                        </button>
                        <button className="knopf leise klein" disabled={sendet} onClick={() => entfernen(s)}>
                            Entfernen
                        </button>
                    </div>
                ))}

                {eigene.length === 0 && !entwurf && (
                    <p className="leise">Dieser Server hat noch keine eigenen Sender.</p>
                )}

                {entwurf && (
                    <div className="karte-eingebettet">
                        <div className="feldgitter">
                            <Feld
                                titel="Name"
                                hilfe="So steht er in der Liste und im Player."
                                kind={<Text
                                    wert={entwurf.name}
                                    setzen={(w) => setEntwurf({ ...entwurf, name: w })}
                                    platzhalter="Mein Sender"
                                />}
                            />
                            <Feld
                                titel="Logo (Adresse)"
                                hilfe="Optional. Ohne Bild zeigt die Kachel die Anfangsbuchstaben."
                                kind={<Bildfeld
                                    wert={entwurf.logoUrl || ""}
                                    setzen={(w) => setEntwurf({ ...entwurf, logoUrl: w })}
                                    platzhalter="https://…/logo.png"
                                    seitenverhaeltnis={1}
                                    zielbreite={256}
                                />}
                            />
                        </div>
                        <Feld
                            titel="Stream-Adresse"
                            breit
                            hilfe="Beginnt mit http:// oder https:// und liefert direkt Audio — meist eine .mp3-, .aac- oder .m3u8-Adresse."
                            kind={<Text
                                wert={entwurf.url}
                                setzen={(w) => setEntwurf({ ...entwurf, url: w })}
                                platzhalter="https://stream.example.org/live.mp3"
                            />}
                        />
                        <div className="listenzeile">
                            <button
                                className="knopf"
                                disabled={sendet || !entwurf.name.trim() || !entwurf.url.trim()}
                                onClick={speichern}
                            >
                                {sendet ? "…" : "Speichern"}
                            </button>
                            <button className="knopf leise" disabled={sendet} onClick={() => setEntwurf(null)}>
                                Abbrechen
                            </button>
                        </div>
                    </div>
                )}
            </section>

            <p className="feld-hilfe">
                Zum Beenden die <a href={`#/server/${guildId}/player`}>Wiedergabe</a> öffnen und dort
                auf Stopp — ein Sender endet nicht von selbst.
            </p>
        </Modulseite>
    );
}

/**
 * Das Senderlogo.
 *
 * <p>Faellt das Bild aus, bleibt kein leerer Rahmen stehen, sondern die
 * Anfangsbuchstaben - dieselbe Loesung wie beim Serversymbol. Fremde Bilder
 * verschwinden haeufiger, als man denkt.</p>
 */
export function Senderbild({ sender }) {
    const [kaputt, setKaputt] = useState(false);
    const kuerzel = (sender.name || "?")
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((w) => w[0])
        .join("")
        .toUpperCase();

    if (!sender.logoUrl || kaputt) {
        return <span className="senderbild ist-leer">{kuerzel || "?"}</span>;
    }
    return (
        <img
            className="senderbild"
            src={sender.logoUrl}
            alt=""
            loading="lazy"
            onError={() => setKaputt(true)}
        />
    );
}
