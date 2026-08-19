/*
 * Kontraste aus den Farbtoken rechnen - fuer beide Schemata.
 *
 * Kein Browser noetig: die Token stehen als Hexwerte in der Datei, und WCAG
 * ist eine Formel. Was ein Browser zusaetzlich koennte, waere die tatsaechlich
 * gewaehlte Kombination zu erkennen - die geben wir hier von Hand vor, dafuer
 * laesst es sich ohne laufende Anwendung pruefen.
 *
 *   node kontrast-pruefen.js <pfad/zu/stil.css>
 */

const fs = require("fs");

const datei = process.argv[2];
const css = fs.readFileSync(datei, "utf8");

function block(waehler) {
    const anfang = css.indexOf(waehler);
    if (anfang < 0) throw new Error("Block nicht gefunden: " + waehler);
    const auf = css.indexOf("{", anfang);
    const zu = css.indexOf("}", auf);
    const werte = {};
    for (const zeile of css.slice(auf + 1, zu).split("\n")) {
        const treffer = /^\s*(--[a-z0-9-]+)\s*:\s*([^;]+);/.exec(zeile);
        if (treffer) werte[treffer[1]] = treffer[2].trim();
    }
    return werte;
}

// Eine Fassung statt zweier - siehe Kopf von stil.css.
//
// Die Namen der Oberflaeche zeigen auf die fuenf Designfarben
// (--text: var(--tinte)), deshalb wird einmal aufgeloest, bevor gerechnet
// wird. Ohne das faende der Pruefer ueberall "kein Hexwert" und meldete
// stillschweigend nichts - eine Pruefung, die nie etwas findet, ist keine.
function aufloesen(roh) {
    const token = {};
    for (const [name, wert] of Object.entries(roh)) {
        let wo = wert;
        for (let runde = 0; runde < 5 && wo.trim().startsWith("var("); runde++) {
            const innen = wo.trim().slice(4, -1).trim();
            if (!/^--[a-z0-9-]+$/.test(innen)) break;
            wo = roh[innen] || wo;
        }
        token[name] = wo;
    }
    return token;
}

const rohHell = block(":root {");
const rohDunkel = { ...rohHell, ...block(String.raw`:root[data-theme="dark"]`) };
const schemata = [["hell", aufloesen(rohHell)], ["dunkel", aufloesen(rohDunkel)]];

function rgb(wert) {
    const treffer = /^#([0-9a-f]{6})$/i.exec(wert.trim());
    if (!treffer) return null;
    const n = parseInt(treffer[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function mischen(vorne, hinten, deckung) {
    return vorne.map((k, i) => Math.round(k * deckung + hinten[i] * (1 - deckung)));
}

function helligkeit([r, g, b]) {
    const f = (k) => {
        const v = k / 255;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function kontrast(a, b) {
    const [x, y] = [helligkeit(a), helligkeit(b)].sort((p, q) => q - p);
    return (x + 0.05) / (y + 0.05);
}

// Paare, die in der Oberflaeche tatsaechlich vorkommen.
const paare = [
    ["--text", "--grund", 1, "Fliesstext auf Seitengrund"],
    ["--text", "--flaeche", 1, "Fliesstext auf Karte"],
    ["--text", "--flaeche-2", 1, "Fliesstext auf zweiter Flaeche"],
    ["--text-leise", "--flaeche", 1, "Nebentext auf Karte"],
    ["--text-leise", "--flaeche-2", 1, "Nebentext auf zweiter Flaeche"],
    ["--text-leise", "--grund", 1, "Nebentext auf Seitengrund"],
    ["--akzent", "--flaeche", 1, "Link/Akzent auf Karte"],
    ["--akzent", "--grund", 1, "Link/Akzent auf Seitengrund"],
    ["--akzent-schrift", "--akzent", 1, "Schrift im gefuellten Knopf"],
    ["--gruen", "--flaeche", 1, "Signal gruen auf Karte"],
    ["--gruen", "--flaeche-2", 1, "Signal gruen auf zweiter Flaeche"],
    ["--rot", "--flaeche", 1, "Signal rot auf Karte"],
    ["--rot", "--flaeche-2", 1, "Signal rot auf zweiter Flaeche"],
    ["--warn", "--flaeche", 1, "Warnfarbe auf Karte"],
    ["--gold", "--flaeche", 1, "Premium-Marke auf Karte"],
    ["--akzent-2", "--flaeche", 1, "Zweitakzent auf Karte"],

    // Die Stellen, an denen gedaempfter Text auf gedaempfter Flaeche steht -
    // dort war der Kontrast vorher zu niedrig.
    ["--text-leise", "--flaeche-2", 1, "Abgeschalteter Befehl (.befehl .leise)"],
    ["--text-leise", "--flaeche-2", 1, "Gesperrter Sender (.sender:disabled)"],
    ["--text-leise", "--flaeche-2", 1, "Ruhende Modulkachel (.modulkachel)"],
    ["--text", "--flaeche", 0.5, "Text bei opacity .5 (.knopf:disabled)"]
];

const GRENZE = 4.5;
const GRENZE_GROSS = 3.0;

let schlecht = 0;

for (const [schema, token] of schemata) {
    console.log("\n=== " + schema.toUpperCase());
    for (const [vorne, hinten, deckung, was] of paare) {
        const v = rgb(token[vorne]);
        const h = rgb(token[hinten]);
        if (!v || !h) {
            console.log("  ?  " + was + " (" + vorne + " oder " + hinten + " ist kein Hexwert)");
            continue;
        }
        const wirklich = deckung < 1 ? mischen(v, h, deckung) : v;
        const wert = kontrast(wirklich, h);
        const grenze = deckung < 1 ? GRENZE_GROSS : GRENZE;
        const ok = wert >= grenze;
        if (!ok) schlecht++;
        console.log(
            "  " + (ok ? "ok " : "!! ") +
            wert.toFixed(2).padStart(5) + "  (mind. " + grenze.toFixed(1) + ")  " + was
        );
    }
}

/*
 * Zweite Pruefung: feste Flaeche ohne feste Schrift.
 *
 * Die Rechnung oben prueft Token gegen Token. Einen Fall findet sie nicht,
 * und der hat zugeschlagen: eine Regel setzt eine der fuenf Designfarben als
 * Grund - die sind in beiden Schemata gleich - und laesst die Schriftfarbe
 * offen. Geerbt wird dann die Seitenfarbe, und die wechselt mit dem Schema.
 * Im hellen Schema faellt nichts auf, im dunklen stand Weiss auf Neongruen:
 * gemessener Kontrast 1.00, also unlesbar.
 *
 * Die Regel dahinter ist einfach genug fuer eine Maschine: wer den Grund
 * festnagelt, nagelt auch die Schrift fest.
 */
const FESTE_FLAECHE = /background\s*:\s*var\(--(acid|violett|orange|tinte|papier)\)/;
const offen = [];
for (const treffer of css.matchAll(/(?:^|\})\s*([^{}@]+?)\s*\{([^}]*)\}/gm)) {
    const waehler = treffer[1];
    const rumpf = treffer[2];
    if (!FESTE_FLAECHE.test(rumpf)) continue;
    if (/(?:^|;)\s*color\s*:/.test(rumpf)) continue;
    offen.push(waehler.trim().replace(/\s+/g, " "));
}

// Regeln ohne eigenen Text - dort ist die Schriftfarbe belanglos.
const OHNE_TEXT = /(:before|:after|^\.orbit|-marke\b|-punkt\b|^\.ampel|^\.puls|scroll-progress|brand-dot|visual-stage|^\.needle|^\.menu-button span|^\.eyebrow i|^\.play-button|^\.speaker-icon|^\.community-stat i|^\.timeline b i|nav-links a$)/;

// Wer weiter unten eine Dunkel-Fassung bekommt, ist versorgt: dort steht die
// Schriftfarbe dann ausdruecklich. Ohne diese Ausnahme meldete der Pruefer
// jeden solchen Fall doppelt - und ein Pruefer, der bekannte Fehlalarme
// ausgibt, wird nach der dritten Meldung nicht mehr gelesen.
function hatDunkelfassung(waehler) {
    const gesucht = ':root[data-theme="dark"] ' + waehler;
    return css.includes(gesucht + " ") || css.includes(gesucht + "{");
}

// Das Theme-Praefix vor dem Vergleich abschneiden: ".timeline b i" steht in
// der Liste der textlosen Regeln, ':root[data-theme="dark"] .timeline b i'
// aber nicht - und es ist dieselbe Regel.
const ohnePraefix = (w) => w.replace(/^:root\[data-theme="[a-z]+"\]\s*/, "");
const echte = offen.filter((w) => !OHNE_TEXT.test(ohnePraefix(w)) && !hatDunkelfassung(w));

console.log("\n=== FESTE FLAECHE OHNE FESTE SCHRIFT");
if (echte.length === 0) {
    console.log("  ok  keine.");
} else {
    for (const w of echte) console.log("  !! " + w);
}

const gesamt = schlecht + echte.length;
console.log("\n" + (gesamt === 0
    ? "Alles ueber der Grenze."
    : schlecht + " Kombination(en) unter der Grenze, "
      + echte.length + " Regel(n) ohne eigene Schriftfarbe."));
process.exit(gesamt === 0 ? 0 : 1);
