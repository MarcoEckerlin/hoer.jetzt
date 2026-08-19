/**
 * Hell oder dunkel.
 *
 * <p>Drei Einstellungen, aber nur zwei Ergebnisse: "system" wird beim Laden
 * aufgeloest und als fester Wert an das {@code <html>} geschrieben. Deshalb
 * kommt das Stylesheet mit einer Regelmenge aus - ohne die Aufloesung
 * braeuchte jede Regel zusaetzlich eine Medienabfrage, und in der Praxis
 * vergisst man sie an der Haelfte der Stellen.</p>
 *
 * <p>Aendert das Betriebssystem seine Einstellung, zieht die Seite nach -
 * aber nur, solange "system" gewaehlt ist. Wer sich ausdruecklich entschieden
 * hat, soll seine Wahl behalten.</p>
 */

const SCHLUESSEL = "hoerjetzt.farbschema";

/** Was gewaehlt wurde: "system", "hell" oder "dunkel". */
export function gewaehlt() {
    try {
        const wert = window.localStorage.getItem(SCHLUESSEL);
        return wert === "hell" || wert === "dunkel" ? wert : "system";
    } catch (keinSpeicher) {
        // Privates Fenster oder gesperrter Speicher - dann eben ohne Gedaechtnis.
        return "system";
    }
}

/** Was daraus tatsaechlich folgt: "hell" oder "dunkel". */
export function wirksam(wahl = gewaehlt()) {
    if (wahl === "hell" || wahl === "dunkel") {
        return wahl;
    }
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dunkel"
        : "hell";
}

/** Schreibt das Ergebnis an das <html>. */
export function anwenden(wahl = gewaehlt()) {
    document.documentElement.setAttribute(
        "data-theme",
        wirksam(wahl) === "dunkel" ? "dark" : "light"
    );
}

export function setzen(wahl) {
    try {
        if (wahl === "system") {
            window.localStorage.removeItem(SCHLUESSEL);
        } else {
            window.localStorage.setItem(SCHLUESSEL, wahl);
        }
    } catch (keinSpeicher) {
        // Die Wahl gilt dann nur fuer diese Sitzung.
    }
    anwenden(wahl);
}

/**
 * Beim Start aufrufen. Setzt das Attribut und haengt sich an die
 * Systemeinstellung, solange "system" gewaehlt ist.
 */
export function starten() {
    anwenden();
    if (!window.matchMedia) {
        return;
    }
    const abfrage = window.matchMedia("(prefers-color-scheme: dark)");
    const nachziehen = () => {
        if (gewaehlt() === "system") {
            anwenden("system");
        }
    };
    // addListener ist veraltet, wird aber von aelteren Safari-Fassungen noch
    // gebraucht - und genau dort faellt der Ausfall sonst niemandem auf.
    if (abfrage.addEventListener) {
        abfrage.addEventListener("change", nachziehen);
    } else if (abfrage.addListener) {
        abfrage.addListener(nachziehen);
    }
}
