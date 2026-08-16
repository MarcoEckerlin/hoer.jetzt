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
