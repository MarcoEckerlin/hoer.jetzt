import React, { useState } from "react";
import { Auswahl, Farbe, Feld, Mehrzeilig, Text } from "./felder.jsx";
import Bildfeld from "./Bildfeld.jsx";

/**
 * Der Editor fuer eine eingebettete Nachricht - samt Vorschau.
 *
 * <p>Ein Modul kann entweder eine gespeicherte Vorlage benutzen oder eine
 * eigene Nachricht bauen. Beides zusammen geht nicht, und zwar mit Absicht:
 * eine Vorlage, die "meistens" gilt, ausser an drei ueberschriebenen Stellen,
 * ist nach einem halben Jahr nicht mehr zu durchschauen.</p>
 *
 * <p>Die Vorschau ist eine Nachbildung, kein Discord. Sie stimmt in Aufbau und
 * Reihenfolge, nicht auf das Pixel. Das reicht fuer die Frage, die man beim
 * Bauen hat: steht das Bild oben oder unten, und passen die Felder
 * nebeneinander?</p>
 */
export default function EmbedEditor({ embed, setzen, vorlagen, vorlageId, vorlageSetzen }) {
    const [reiter, setReiter] = useState("bauen");
    const e = embed || {};

    function feld(name, wert) {
        setzen({ ...e, [name]: wert });
    }

    const nutztVorlage = !!vorlageId;
    const angezeigt = nutztVorlage
        ? (vorlagen || []).find((v) => v.id === vorlageId) || {}
        : e;

    return (
        <div className="embed-editor">
            {vorlagen && (
                <Feld
                    titel="Vorlage"
                    hilfe={
                        nutztVorlage
                            ? "Diese Nachricht kommt aus der Vorlage. Änderungen dort wirken überall, wo sie benutzt wird."
                            : "Keine Vorlage: die Nachricht unten gilt nur für dieses Modul."
                    }
                    kind={
                        <Auswahl
                            wert={vorlageId}
                            setzen={vorlageSetzen}
                            liste={vorlagen}
                            leerText="— eigene Nachricht —"
                        />
                    }
                />
            )}

            <div className="reiter">
                <button
                    className={`reiter-knopf ${reiter === "bauen" ? "ist-aktiv" : ""}`}
                    onClick={() => setReiter("bauen")}
                    disabled={nutztVorlage}
                >
                    Bearbeiten
                </button>
                <button
                    className={`reiter-knopf ${reiter === "vorschau" ? "ist-aktiv" : ""}`}
                    onClick={() => setReiter("vorschau")}
                >
                    Vorschau
                </button>
            </div>

            {reiter === "vorschau" || nutztVorlage ? (
                <EmbedVorschau embed={angezeigt} />
            ) : (
                <div className="feldgitter">
                    <Feld titel="Titel" kind={<Text wert={e.titel} setzen={(v) => feld("titel", v)} />} />
                    <Feld
                        titel="Titel-Link"
                        hilfe="Macht den Titel anklickbar."
                        kind={<Text wert={e.titelUrl} setzen={(v) => feld("titelUrl", v)} placeholder="https://…" />}
                    />
                    <Feld
                        breit
                        titel="Beschreibung"
                        hilfe="Discord-Markdown ist erlaubt: **fett**, *kursiv*, `Code`, > Zitat."
                        kind={<Mehrzeilig wert={e.beschreibung} setzen={(v) => feld("beschreibung", v)} zeilen={5} />}
                    />
                    <Feld titel="Farbe" kind={<Farbe wert={e.farbe} setzen={(v) => feld("farbe", v)} />} />
                    <Feld
                        titel="Text über der Nachricht"
                        hilfe="Steht außerhalb des Rahmens. Nur hier funktionieren @-Erwähnungen — innerhalb der Nachricht benachrichtigt Discord niemanden."
                        kind={<Text wert={e.inhalt} setzen={(v) => feld("inhalt", v)} />}
                    />

                    <Feld
                        titel="Großes Bild"
                        hilfe="Steht unten, über der Fußzeile."
                        kind={<Bildfeld wert={e.bildUrl} setzen={(v) => feld("bildUrl", v)}
                                        seitenverhaeltnis={16 / 9} zielbreite={1024} />}
                    />
                    <Feld
                        titel="Kleines Bild"
                        hilfe="Rechts oben neben dem Titel."
                        kind={<Bildfeld wert={e.thumbnailUrl} setzen={(v) => feld("thumbnailUrl", v)}
                                        seitenverhaeltnis={1} zielbreite={512} />}
                    />

                    <Bilderliste
                        bilder={e.zusatzBilder || []}
                        setzen={(v) => feld("zusatzBilder", v)}
                        hatHaupt={!!e.bildUrl}
                    />

                    <Felderliste felder={e.felder || []} setzen={(v) => feld("felder", v)} />

                    <Feld titel="Autor" kind={<Text wert={e.autorName} setzen={(v) => feld("autorName", v)} />} />
                    <Feld
                        titel="Autor-Bild"
                        kind={<Text wert={e.autorIconUrl} setzen={(v) => feld("autorIconUrl", v)} placeholder="https://…" />}
                    />
                    <Feld titel="Fußzeile" kind={<Text wert={e.fusszeile} setzen={(v) => feld("fusszeile", v)} />} />
                    <Feld
                        titel="Fußzeilen-Bild"
                        kind={<Text wert={e.fusszeileIconUrl} setzen={(v) => feld("fusszeileIconUrl", v)} placeholder="https://…" />}
                    />
                </div>
            )}
        </div>
    );
}

/**
 * Zusaetzliche Bilder.
 *
 * <p>Discord kennt kein Embed mit mehreren Bildern. Es stapelt aber mehrere
 * Nachrichten mit <em>derselben</em> Link-Adresse zu einer Galerie - genau das
 * macht der Renderer im Bot daraus. Deshalb steht hier auch der Hinweis, dass
 * ohne grosses Bild nichts passiert: die Galerie braucht ein erstes Bild, an
 * das sie sich haengt.</p>
 */
function Bilderliste({ bilder, setzen, hatHaupt }) {
    return (
        <div className="feld ist-breit">
            <label className="feld-titel">Weitere Bilder</label>
            {bilder.map((b, i) => (
                <div className="listenzeile" key={i}>
                    {/* Dieselbe Behandlung wie das grosse Bild - Discord stellt
                        die Zusatzbilder in derselben Galerie dar. */}
                    <Bildfeld
                        wert={b}
                        setzen={(v) => setzen(bilder.map((x, j) => (j === i ? v : x)))}
                        seitenverhaeltnis={16 / 9}
                        zielbreite={1024}
                    />
                    <button className="knopf leise klein" onClick={() => setzen(bilder.filter((_, j) => j !== i))}>
                        Entfernen
                    </button>
                </div>
            ))}
            <button className="knopf leise klein" onClick={() => setzen([...bilder, ""])}>
                Bild hinzufügen
            </button>
            <p className="feld-hilfe">
                Discord zeigt sie als Galerie unter der Nachricht — bis zu vier Bilder insgesamt.
                {!hatHaupt && " Dafür muss oben ein großes Bild gesetzt sein; ohne das bleibt die Galerie leer."}
            </p>
        </div>
    );
}

function Felderliste({ felder, setzen }) {
    function aendern(i, name, wert) {
        setzen(felder.map((f, j) => (j === i ? { ...f, [name]: wert } : f)));
    }
    return (
        <div className="feld ist-breit">
            <label className="feld-titel">Felder</label>
            {felder.map((f, i) => (
                <div className="listenkarte" key={i}>
                    <input
                        className="eingabe"
                        placeholder="Überschrift"
                        value={f.name ?? ""}
                        onChange={(e) => aendern(i, "name", e.target.value)}
                    />
                    <textarea
                        className="eingabe"
                        rows={2}
                        placeholder="Inhalt"
                        value={f.wert ?? ""}
                        onChange={(e) => aendern(i, "wert", e.target.value)}
                    />
                    <div className="listenzeile">
                        <label className="klickzeile">
                            <input
                                type="checkbox"
                                checked={!!f.inline}
                                onChange={(e) => aendern(i, "inline", e.target.checked)}
                            />
                            <span>Nebeneinander</span>
                        </label>
                        <button className="knopf leise klein" onClick={() => setzen(felder.filter((_, j) => j !== i))}>
                            Entfernen
                        </button>
                    </div>
                </div>
            ))}
            {felder.length < 25 && (
                <button className="knopf leise klein" onClick={() => setzen([...felder, { name: "", wert: "", inline: false }])}>
                    Feld hinzufügen
                </button>
            )}
            <p className="feld-hilfe">
                Drei „nebeneinander“ passen in eine Zeile. Discord erlaubt höchstens 25 Felder.
            </p>
        </div>
    );
}

export function EmbedVorschau({ embed }) {
    const e = embed || {};
    const farbe = /^#[0-9a-fA-F]{6}$/.test(e.farbe || "") ? e.farbe : "#5865f2";
    const leer = !e.titel && !e.beschreibung && !(e.felder || []).length && !e.bildUrl && !e.autorName;

    return (
        <div className="vorschau">
            {e.inhalt && <p className="vorschau-inhalt">{e.inhalt}</p>}
            <div className="vorschau-embed" style={{ borderLeftColor: farbe }}>
                {leer && <p className="feld-hilfe">Noch nichts eingetragen.</p>}
                {e.autorName && (
                    <div className="vorschau-autor">
                        {e.autorIconUrl && <img src={e.autorIconUrl} alt="" />}
                        <span>{e.autorName}</span>
                    </div>
                )}
                {e.titel && <div className="vorschau-titel">{e.titel}</div>}
                {e.beschreibung && <div className="vorschau-text">{e.beschreibung}</div>}
                {(e.felder || []).length > 0 && (
                    <div className="vorschau-felder">
                        {e.felder.map((f, i) => (
                            <div className={`vorschau-feld ${f.inline ? "ist-inline" : ""}`} key={i}>
                                <strong>{f.name}</strong>
                                <span>{f.wert}</span>
                            </div>
                        ))}
                    </div>
                )}
                {e.thumbnailUrl && <img className="vorschau-thumb" src={e.thumbnailUrl} alt="" />}
                {e.bildUrl && <img className="vorschau-bild" src={e.bildUrl} alt="" />}
                {(e.zusatzBilder || []).filter(Boolean).length > 0 && e.bildUrl && (
                    <div className="vorschau-galerie">
                        {e.zusatzBilder.filter(Boolean).slice(0, 3).map((b, i) => (
                            <img src={b} alt="" key={i} />
                        ))}
                    </div>
                )}
                {e.fusszeile && (
                    <div className="vorschau-fuss">
                        {e.fusszeileIconUrl && <img src={e.fusszeileIconUrl} alt="" />}
                        <span>{e.fusszeile}</span>
                    </div>
                )}
            </div>
        </div>
    );
}
