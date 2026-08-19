/**
 * Zugriff auf core.
 *
 * <p>Bewusst duenn: eine Funktion, die den Fehlerfall genauso ernst nimmt wie
 * den Erfolgsfall. Die alte Oberflaeche hatte dieselbe Stelle - dort war sie
 * der Grund, warum eine abgelaufene Sitzung als "HTTP 401" beim Nutzer
 * ankam statt als "bitte neu anmelden".</p>
 */
export async function api(methode, pfad, koerper) {
    const optionen = {
        method: methode,
        headers: { Accept: "application/json" },
        // Die Sitzung haengt an einem Cookie, das core setzt. Ohne dies
        // schickt der Browser es bei einem anderen Ursprung nicht mit.
        credentials: "include"
    };
    if (koerper !== undefined) {
        optionen.headers["Content-Type"] = "application/json";
        optionen.body = JSON.stringify(koerper);
    }

    const antwort = await fetch(pfad, optionen);
    const text = await antwort.text();
    let daten = null;
    if (text) {
        try {
            daten = JSON.parse(text);
        } catch {
            daten = null;
        }
    }

    if (!antwort.ok) {
        if (antwort.status === 401) {
            throw new Error("Die Sitzung ist abgelaufen. Bitte neu anmelden.");
        }
        throw new Error((daten && (daten.message || daten.error)) || `HTTP ${antwort.status}`);
    }
    return daten;
}

/** Öffentliche Angaben zur Instanz — ohne Anmeldung abrufbar. */
export function markeLaden() {
    return api("GET", "/api/public/brand").catch(() => null);
}

/**
 * Ein Bild hochladen.
 *
 * <p>Eigene Funktion und nicht {@link api}: dort wird der Rumpf zu JSON gemacht
 * und "Content-Type: application/json" gesetzt. Bei FormData muss der Browser
 * den Kopf selbst schreiben, weil er die Trennzeichenkette enthaelt - setzt man
 * ihn von Hand, kommt beim Server ein unlesbarer Rumpf an.</p>
 */
export async function bildHochladen(datei, dateiname) {
    const formular = new FormData();
    formular.append("file", datei, dateiname || "bild");

    const antwort = await fetch("/api/assets/upload", {
        method: "POST",
        headers: { Accept: "application/json" },
        credentials: "include",
        body: formular
    });

    const text = await antwort.text();
    let daten = null;
    if (text) {
        try {
            daten = JSON.parse(text);
        } catch {
            daten = null;
        }
    }

    if (!antwort.ok) {
        if (antwort.status === 401) {
            throw new Error("Die Sitzung ist abgelaufen. Bitte neu anmelden.");
        }
        // 413 kommt vom Servlet-Container, bevor unser Code laeuft - dann gibt
        // es keine JSON-Meldung, sondern eine HTML-Fehlerseite.
        if (antwort.status === 413) {
            throw new Error("Das Bild ist zu groß. Erlaubt sind 3 MB.");
        }
        throw new Error((daten && (daten.message || daten.error)) || `HTTP ${antwort.status}`);
    }
    return daten;
}

/**
 * Das Symbol im Browser-Tab an die hinterlegte Marke anpassen.
 *
 * <p>Das Grundsymbol steht in index.html. Ist im Adminbereich ein Markenbild
 * hochgeladen, zeigt der Tab es danach an - vorher war das Feld im Panel
 * ausfuellbar, wirkte sich aber nirgends aus.</p>
 *
 * <p>Fehlerhafte Bilder werden stillschweigend uebergangen: ein Tab mit dem
 * Grundsymbol ist besser als einer ohne.</p>
 */
export function seitensymbolSetzen(adresse) {
    if (!adresse) {
        return;
    }
    const verweis = document.getElementById("seitensymbol");
    if (!verweis) {
        return;
    }
    // Erst laden, dann tauschen. Sonst steht bei einer toten Adresse gar
    // kein Symbol mehr da, weil der alte Verweis schon ersetzt waere.
    const probe = new Image();
    probe.onload = () => {
        verweis.setAttribute("href", adresse);
        verweis.removeAttribute("type");
    };
    probe.src = adresse;
}
