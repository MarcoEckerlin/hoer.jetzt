import React from "react";

/**
 * Das Symbol eines Servers - Logo, sonst Kuerzel.
 *
 * <p>Es gab das zweimal, und zwar unterschiedlich: die Serverleiste nahm die
 * Anfangsbuchstaben der ersten beiden Woerter in Grossschrift, die Uebersicht
 * das erste Zeichen unveraendert. Derselbe Server hiess links "EM" und oben
 * "e". Solche Abweichungen sind einzeln winzig und in der Summe der Grund,
 * warum eine Oberflaeche zusammengestueckelt wirkt.</p>
 *
 * <p>Die Groesse kommt aus der Klasse des Aufrufers, nicht aus einer
 * Eigenschaft: so bestimmt weiterhin das Stylesheet das Aussehen, und ein
 * neuer Ort braucht keine neue Zahl im JavaScript.</p>
 */
export default function Serversymbol({ server, className = "" }) {
    const name = server?.name || "";

    if (server?.iconUrl) {
        return <img className={className} src={server.iconUrl} alt="" />;
    }
    return <span className={`${className} ist-leer`.trim()}>{kuerzel(name)}</span>;
}

/** Bis zu zwei Anfangsbuchstaben, gross. "Eckerlin Music" wird zu "EM". */
export function kuerzel(name) {
    const woerter = (name || "?")
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2);

    if (woerter.length === 0) {
        return "?";
    }
    // Array.from statt [0]: bei einem Servernamen, der mit einem Emoji
    // anfaengt, liefert [0] die halbe Ersatzzeichen-Paarung und der Browser
    // zeigt ein Kaestchen.
    return woerter.map((wort) => Array.from(wort)[0]).join("").toUpperCase();
}
