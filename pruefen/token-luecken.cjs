/*
 * Welche Farbtoken sind nur im dunklen Schema definiert?
 *
 * Genau das ist der haeufigste Grund, warum ein heller Modus "komisch"
 * aussieht: die meisten Farben kippen, ein paar bleiben dunkel stehen - und
 * das faellt einzeln nie auf, sondern nur als Gesamteindruck.
 *
 *   node token-luecken.js <css> [<css> ...]
 */
const fs = require("fs");

function bloecke(css, waehler) {
    const werte = {};
    const entwertet = waehler.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const muster = new RegExp(entwertet + "\\s*\\{([^}]*)\\}", "g");
    let treffer;
    while ((treffer = muster.exec(css)) !== null) {
        for (const zeile of treffer[1].split(/[\n;]/)) {
            const t = /^\s*(--[a-z0-9-]+)\s*:\s*(.+)$/i.exec(zeile);
            if (t) werte[t[1]] = t[2].trim();
        }
    }
    return werte;
}

// Nur Token, die wirklich eine Farbe tragen - Abstaende, Radien und
// Schriftfamilien muessen im hellen Modus gleich bleiben.
function istFarbe(wert) {
    return /#[0-9a-f]{3,8}|rgba?\(|hsla?\(|color-mix/i.test(wert);
}

let luecken = 0;

for (const datei of process.argv.slice(2)) {
    const css = fs.readFileSync(datei, "utf8");

    // Zwei Schreibweisen im Bestand: die React-Oberflaeche setzt den dunklen
    // Satz auf :root und den hellen auf :root[data-theme="light"], die
    // Panel-Stylesheets benutzen html[data-theme="…"]. Beide einlesen, statt
    // eine davon fuer richtig zu erklaeren.
    const dunkel = {
        ...bloecke(css, ":root"),
        ...bloecke(css, ':root,\nhtml[data-theme="dark"]'),
        ...bloecke(css, 'html[data-theme="dark"]')
    };
    const hell = {
        ...bloecke(css, ':root[data-theme="light"]'),
        ...bloecke(css, 'html[data-theme="light"]')
    };

    const farbig = Object.keys(dunkel).filter((t) => istFarbe(dunkel[t]));
    // ":root" trifft auch den hellen Block mit - als Luecke gilt deshalb nur,
    // was dort denselben Wert hat oder gar nicht vorkommt.
    const nurDunkel = farbig.filter((t) => !(t in hell) || hell[t] === dunkel[t]);

    console.log("\n=== " + datei.split(/[\\/]/).pop());
    console.log("    Farbtoken: " + farbig.length
        + ", im hellen Modus neu gesetzt: " + (farbig.length - nurDunkel.length));

    if (nurDunkel.length === 0) {
        console.log("    keine Luecke");
    } else {
        luecken += nurDunkel.length;
        for (const t of nurDunkel) {
            console.log("    !! " + t.padEnd(24) + dunkel[t]);
        }
    }
}

console.log("\n" + (luecken === 0
    ? "Keine Luecken."
    : luecken + " Token bleiben im hellen Modus auf dem dunklen Wert."));
process.exit(luecken === 0 ? 0 : 1);
