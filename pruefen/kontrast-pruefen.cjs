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

const dunkel = block(":root {");
const hell = { ...dunkel, ...block(':root[data-theme="light"]') };

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

for (const [schema, token] of [["dunkel", dunkel], ["hell", hell]]) {
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

console.log("\n" + (schlecht === 0
    ? "Alles ueber der Grenze."
    : schlecht + " Kombination(en) unter der Grenze."));
process.exit(schlecht === 0 ? 0 : 1);
