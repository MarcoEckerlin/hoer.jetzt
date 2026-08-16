/**
 * Farbschema - hell, dunkel oder wie das System es will.
 *
 * <p>Bewusst dieselbe Speicherstelle und dasselbe Attribut wie die alte
 * Oberflaeche (<code>discordbot-theme</code> und <code>data-theme</code> am
 * <code>&lt;html&gt;</code>). Wer auf der Startseite hell einstellt und dann in
 * die bisherige Ansicht wechselt, soll dort nicht wieder im Dunkeln sitzen -
 * zwei Oberflaechen mit zwei getrennten Einstellungen sind aus Sicht des
 * Nutzers schlicht kaputt.</p>
 *
 * <p>Das Setzen passiert so frueh wie moeglich, noch vor dem ersten Zeichnen.
 * Wartet man bis React laeuft, blitzt kurz das falsche Schema auf - und genau
 * dieses Blitzen sieht man auf einer dunklen Seite besonders deutlich.</p>
 */

const SCHLUESSEL = "discordbot-theme";
const MODI = ["system", "dark", "light"];

export function gespeicherterModus() {
    try {
        const wert = window.localStorage.getItem(SCHLUESSEL);
        return MODI.includes(wert) ? wert : "system";
    } catch {
        // Privater Modus oder gesperrter Speicher: dann eben nur fuer diesen
        // Besuch. Kein Grund, die Seite deshalb scheitern zu lassen.
        return "system";
    }
}

export function aufgeloest(modus) {
    if (modus === "dark" || modus === "light") {
        return modus;
    }
    return window.matchMedia?.("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

export function anwenden(modus) {
    const wurzel = document.documentElement;
    wurzel.setAttribute("data-theme", aufgeloest(modus));
    wurzel.setAttribute("data-theme-mode", modus);
    try {
        window.localStorage.setItem(SCHLUESSEL, modus);
    } catch {
        /* siehe oben */
    }
}

/** Folgt der Systemeinstellung - aber nur, solange nichts anderes gewaehlt ist. */
export function systemBeobachten(modusLesen) {
    const abfrage = window.matchMedia?.("(prefers-color-scheme: light)");
    if (!abfrage) {
        return () => {};
    }
    const beim = () => {
        if (modusLesen() === "system") {
            anwenden("system");
        }
    };
    abfrage.addEventListener("change", beim);
    return () => abfrage.removeEventListener("change", beim);
}
