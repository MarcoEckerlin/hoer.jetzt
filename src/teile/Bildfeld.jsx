import React, { useCallback, useEffect, useRef, useState } from "react";
import { bildHochladen } from "../lib/api.js";

/**
 * Ein Bildfeld: Adresse eintippen oder Datei hochladen.
 *
 * <p>Bisher stand an jeder dieser Stellen ein nacktes Textfeld mit dem
 * Platzhalter "https://…". Das setzt voraus, dass das Bild bereits irgendwo im
 * Netz liegt - wer eines auf der Festplatte hat, musste es erst bei einem
 * Fremdanbieter ablegen. Der Upload-Endpunkt existierte die ganze Zeit, war
 * aber von der Oberflaeche aus nicht erreichbar.</p>
 *
 * <h2>Warum die Adresse bleibt</h2>
 *
 * <p>Das Textfeld verschwindet nicht. Ein eingefuegter Link ist der schnellste
 * Weg, wenn das Bild schon existiert, und manche Bilder <em>sollen</em> fremd
 * bleiben - ein Senderlogo etwa, das der Sender selbst pflegt. Hochladen ist
 * eine zusaetzliche Moeglichkeit, kein Ersatz.</p>
 *
 * <h2>Der Zuschnitt</h2>
 *
 * <p>Wie bei Discords Profilbild: ziehen verschiebt, der Regler zoomt, der
 * Ausschnitt hat das Verhaeltnis, in dem das Bild spaeter auch angezeigt wird.
 * Ohne das laedt jemand ein Querformat als Logo hoch und wundert sich, warum
 * links und rechts etwas fehlt - der Beschnitt passiert dann naemlich trotzdem,
 * nur unsichtbar und ohne Wahl.</p>
 *
 * @param wert                aktuelle Adresse
 * @param setzen              Rueckruf mit der neuen Adresse
 * @param seitenverhaeltnis   Breite/Hoehe des Ausschnitts (1 = quadratisch)
 * @param rund                Vorschau als Kreis - fuer Bilder, die Discord rund zeigt
 * @param zielbreite          Kantenlaenge des Ergebnisses in Pixeln
 */
export default function Bildfeld({
    wert,
    setzen,
    platzhalter = "https://…",
    seitenverhaeltnis = 1,
    rund = false,
    zielbreite = 512
}) {
    const [datei, setDatei] = useState(null);
    const [fehler, setFehler] = useState(null);
    const [laeuft, setLaeuft] = useState(false);
    const auswahl = useRef(null);

    async function hochladen(inhalt, name) {
        setLaeuft(true);
        setFehler(null);
        try {
            const antwort = await bildHochladen(inhalt, name);
            setzen(antwort.url);
            setDatei(null);
        } catch (f) {
            setFehler(f.message);
        } finally {
            setLaeuft(false);
        }
    }

    function gewaehlt(e) {
        const f = e.target.files && e.target.files[0];
        // Zuruecksetzen, sonst loest dieselbe Datei beim zweiten Mal kein
        // change-Ereignis aus und der Knopf wirkt kaputt.
        e.target.value = "";
        if (!f) return;
        setFehler(null);

        // Ein GIF durch den Zuschnitt zu schicken hiesse, es auf das erste
        // Einzelbild einzudampfen - die Zeichenflaeche kennt keine Animation.
        // Also unveraendert durchreichen und das auch sagen.
        if (f.type === "image/gif") {
            hochladen(f, f.name);
            return;
        }
        setDatei(f);
    }

    return (
        <div className="bildfeld">
            <div className="bildfeld-zeile">
                <Vorschau wert={wert} rund={rund} seitenverhaeltnis={seitenverhaeltnis} />
                <input
                    className="eingabe"
                    type="text"
                    value={wert ?? ""}
                    placeholder={platzhalter}
                    onChange={(e) => setzen(e.target.value)}
                />
                <input
                    ref={auswahl}
                    type="file"
                    accept="image/png,image/jpeg,image/gif,image/webp"
                    hidden
                    onChange={gewaehlt}
                />
                <button
                    type="button"
                    className="knopf leise klein"
                    disabled={laeuft}
                    onClick={() => auswahl.current && auswahl.current.click()}
                >
                    {laeuft ? "…" : "Hochladen"}
                </button>
                {wert && (
                    <button
                        type="button"
                        className="knopf leise klein"
                        disabled={laeuft}
                        onClick={() => setzen("")}
                    >
                        Entfernen
                    </button>
                )}
            </div>

            {fehler && <p className="feld-hilfe ist-fehler">{fehler}</p>}

            {datei && (
                <Zuschnitt
                    datei={datei}
                    seitenverhaeltnis={seitenverhaeltnis}
                    rund={rund}
                    zielbreite={zielbreite}
                    abbrechen={() => setDatei(null)}
                    fertig={(blob) => hochladen(blob, "zuschnitt.webp")}
                    unveraendert={() => hochladen(datei, datei.name)}
                />
            )}
        </div>
    );
}

/** Kleines Abbild neben dem Feld. Faellt es aus, bleibt kein leerer Rahmen. */
function Vorschau({ wert, rund, seitenverhaeltnis }) {
    const [kaputt, setKaputt] = useState(false);
    useEffect(() => setKaputt(false), [wert]);

    const stil = { aspectRatio: String(seitenverhaeltnis) };
    if (!wert || kaputt) {
        return <span className={`bildfeld-vorschau ist-leer ${rund ? "ist-rund" : ""}`} style={stil}>▦</span>;
    }
    return (
        <img
            className={`bildfeld-vorschau ${rund ? "ist-rund" : ""}`}
            style={stil}
            src={wert}
            alt=""
            onError={() => setKaputt(true)}
        />
    );
}

/**
 * Der Zuschnitt-Dialog.
 *
 * <h2>Die Rechnung</h2>
 *
 * <p>Das Bild wird zunaechst so skaliert, dass es den Ausschnitt gerade
 * ausfuellt ({@code grundmass}). Der Regler multipliziert das. Die Verschiebung
 * wird so begrenzt, dass nie ein Rand entsteht - andernfalls koennte man das
 * Bild aus dem Ausschnitt herausziehen und bekaeme eine Flaeche, die im
 * Ergebnis schwarz oder durchsichtig waere.</p>
 *
 * <p>Beim Speichern wird aus Verschiebung und Massstab der Bereich im
 * <em>Original</em> zurueckgerechnet und nur dieser gezeichnet. So haengt die
 * Schaerfe des Ergebnisses an der Aufloesung der Datei, nicht an der Groesse
 * des Dialogs auf dem Bildschirm.</p>
 */
function Zuschnitt({ datei, seitenverhaeltnis, rund, zielbreite, abbrechen, fertig, unveraendert }) {
    const [bild, setBild] = useState(null);
    const [zoom, setZoom] = useState(1);
    const [versatz, setVersatz] = useState({ x: 0, y: 0 });
    const [fehler, setFehler] = useState(null);
    const rahmen = useRef(null);
    const ziehen = useRef(null);

    // Objekt-URL statt FileReader: kein Base64 im Speicher, und der Browser
    // gibt den Puffer wieder frei, sobald wir ihn widerrufen.
    useEffect(() => {
        const adresse = URL.createObjectURL(datei);
        const el = new Image();
        el.onload = () => setBild(el);
        el.onerror = () => setFehler("Diese Datei lässt sich nicht als Bild lesen.");
        el.src = adresse;
        return () => URL.revokeObjectURL(adresse);
    }, [datei]);

    // Esc schliesst - bei einer Schicht ueber der Seite erwartet man das.
    useEffect(() => {
        const taste = (e) => e.key === "Escape" && abbrechen();
        window.addEventListener("keydown", taste);
        return () => window.removeEventListener("keydown", taste);
    }, [abbrechen]);

    const masse = useCallback(() => {
        const el = rahmen.current;
        if (!el || !bild) return null;
        const b = el.clientWidth;
        const h = b / seitenverhaeltnis;
        const grundmass = Math.max(b / bild.naturalWidth, h / bild.naturalHeight);
        const massstab = grundmass * zoom;
        return { b, h, massstab, breite: bild.naturalWidth * massstab, hoehe: bild.naturalHeight * massstab };
    }, [bild, seitenverhaeltnis, zoom]);

    const begrenzen = useCallback((v) => {
        const m = masse();
        if (!m) return v;
        return {
            x: Math.min(0, Math.max(m.b - m.breite, v.x)),
            y: Math.min(0, Math.max(m.h - m.hoehe, v.y))
        };
    }, [masse]);

    // Nach jedem Zoom neu einpassen: sonst bleibt beim Herauszoomen ein Rand
    // stehen, weil die alte Verschiebung fuer das groessere Bild galt.
    useEffect(() => setVersatz((v) => begrenzen(v)), [zoom, begrenzen]);

    function greifen(e) {
        e.currentTarget.setPointerCapture(e.pointerId);
        ziehen.current = { x: e.clientX - versatz.x, y: e.clientY - versatz.y };
    }

    function bewegen(e) {
        if (!ziehen.current) return;
        setVersatz(begrenzen({ x: e.clientX - ziehen.current.x, y: e.clientY - ziehen.current.y }));
    }

    function loslassen() {
        ziehen.current = null;
    }

    function speichern() {
        const m = masse();
        if (!m || !bild) return;

        // Vom Bildschirm zurueck ins Original: was im Ausschnitt zu sehen ist,
        // liegt dort bei (-versatz / massstab) und ist (rahmen / massstab) gross.
        const quelleX = -versatz.x / m.massstab;
        const quelleY = -versatz.y / m.massstab;
        const quelleB = m.b / m.massstab;
        const quelleH = m.h / m.massstab;

        const flaeche = document.createElement("canvas");
        flaeche.width = zielbreite;
        flaeche.height = Math.round(zielbreite / seitenverhaeltnis);
        const stift = flaeche.getContext("2d");
        stift.imageSmoothingQuality = "high";
        stift.drawImage(bild, quelleX, quelleY, quelleB, quelleH, 0, 0, flaeche.width, flaeche.height);

        flaeche.toBlob(
            (blob) => {
                if (blob) fertig(blob);
                else setFehler("Der Ausschnitt konnte nicht erzeugt werden.");
            },
            // WebP ist bei gleicher Sichtqualitaet deutlich kleiner als PNG und
            // wird von jedem Browser unterstuetzt, der diese Oberflaeche
            // ueberhaupt darstellt. Der Server nimmt es an.
            "image/webp",
            0.92
        );
    }

    const m = masse();

    return (
        <div className="schicht" onClick={abbrechen}>
            <div className="schicht-fenster ist-schmal" onClick={(e) => e.stopPropagation()}>
                <h2>Bild zuschneiden</h2>
                <p className="feld-hilfe">Ziehen verschiebt, der Regler vergrößert.</p>

                {fehler && <div className="notiz notiz-fehler">{fehler}</div>}

                <div
                    className={`zuschnitt-rahmen ${rund ? "ist-rund" : ""}`}
                    ref={rahmen}
                    style={{ aspectRatio: String(seitenverhaeltnis) }}
                    onPointerDown={greifen}
                    onPointerMove={bewegen}
                    onPointerUp={loslassen}
                    onPointerCancel={loslassen}
                >
                    {bild && m && (
                        <img
                            src={bild.src}
                            alt=""
                            draggable="false"
                            style={{
                                width: `${m.breite}px`,
                                height: `${m.hoehe}px`,
                                transform: `translate(${versatz.x}px, ${versatz.y}px)`
                            }}
                        />
                    )}
                </div>

                <input
                    className="zuschnitt-regler"
                    type="range"
                    min="1"
                    max="4"
                    step="0.01"
                    value={zoom}
                    aria-label="Vergrößerung"
                    onChange={(e) => setZoom(Number(e.target.value))}
                />

                <div className="listenzeile">
                    <button type="button" className="knopf" onClick={speichern} disabled={!bild}>
                        Übernehmen
                    </button>
                    {/* Der Ausweg fuer alles, was nicht in den Ausschnitt passt -
                        etwa ein Bild, dessen Verhaeltnis gerade der Punkt ist. */}
                    <button type="button" className="knopf leise" onClick={unveraendert}>
                        Ohne Zuschnitt
                    </button>
                    <button type="button" className="knopf leise" onClick={abbrechen}>
                        Abbrechen
                    </button>
                </div>
            </div>
        </div>
    );
}
