/*
 * Gleicht die Adressen ab, die die Oberflaeche aufruft, mit denen, die core
 * anbietet.
 *
 * Der Anlass war ein Aufruf auf "queue/move" mit falschen Feldnamen: gebaut,
 * gebaut, deployt, und erst im Betrieb fiel auf, dass der Knopf nichts tut.
 * Ein Tippfehler in einer Zeichenkette faellt weder dem Compiler noch dem
 * Bundler auf - beide Seiten sind fuer sich genommen fehlerfrei.
 *
 * Deshalb steht in Player.jsx & Co. der volle Pfad woertlich im Aufruf und
 * nicht zusammengesetzt aus Bausteinen: nur so ist er hier auffindbar.
 *
 *   node pruefen/endpunkte-pruefen.cjs
 */
const fs = require("fs");
const path = require("path");

const WEB = path.join(__dirname, "..", "src");
const CORE = path.join(__dirname, "..", "..", "core", "src", "main", "java");

/** Alle Dateien unterhalb eines Ordners, gefiltert nach Endung. */
function dateien(ordner, endungen) {
    const gefunden = [];
    for (const eintrag of fs.readdirSync(ordner, { withFileTypes: true })) {
        const voll = path.join(ordner, eintrag.name);
        if (eintrag.isDirectory()) gefunden.push(...dateien(voll, endungen));
        else if (endungen.some((e) => eintrag.name.endsWith(e))) gefunden.push(voll);
    }
    return gefunden;
}

/**
 * Pfadschablone: Template-Platzhalter und Java-Pfadvariablen werden beide zu
 * "{}" - danach ist "/guilds/${guildId}/player" dasselbe wie
 * "/guilds/{guildId}/player" und laesst sich vergleichen.
 */
function schablone(pfad) {
    return pfad
        .replace(/\$\{[^}]*\}/g, "{}")
        .replace(/\{[^}]*\}/g, "{}")
        .replace(/\?.*$/, "")
        .replace(/\/+$/, "");
}

// --- Was die Oberflaeche aufruft -------------------------------------------

const aufrufe = new Map();
for (const datei of dateien(WEB, [".js", ".jsx"])) {
    const text = fs.readFileSync(datei, "utf8");
    // api("METHODE", "/api/...") - beide Anfuehrungsarten, auch Template-Strings.
    const muster = /api\(\s*["'`](GET|POST|PUT|DELETE)["'`]\s*,\s*["'`](\/api\/[^"'`]*)["'`]/g;
    let treffer;
    while ((treffer = muster.exec(text)) !== null) {
        const schluessel = `${treffer[1]} ${schablone(treffer[2])}`;
        if (!aufrufe.has(schluessel)) aufrufe.set(schluessel, []);
        aufrufe.get(schluessel).push(path.relative(WEB, datei));
    }
}

// --- Was core anbietet ------------------------------------------------------

const angebote = new Set();
for (const datei of dateien(CORE, [".java"])) {
    const text = fs.readFileSync(datei, "utf8");
    if (!text.includes("Mapping")) continue;

    // Der gemeinsame Praefix der Klasse.
    const klasse = /@RequestMapping\(\s*"([^"]*)"\s*\)/.exec(text);
    const praefix = klasse ? klasse[1] : "";

    const muster = /@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?(\{[^}]*\}|"[^"]*")?\s*\)/g;
    let treffer;
    while ((treffer = muster.exec(text)) !== null) {
        const methode = treffer[1].toUpperCase();
        const roh = treffer[2] || '""';
        for (const stueck of roh.match(/"[^"]*"/g) || ['""']) {
            const pfad = stueck.slice(1, -1);
            angebote.add(`${methode} ${schablone(praefix + pfad)}`);
        }
    }
}

// --- Abgleich ---------------------------------------------------------------

const fehlend = [...aufrufe.keys()].filter((k) => !angebote.has(k)).sort();

if (fehlend.length === 0) {
    console.log(`Alle ${aufrufe.size} Aufrufe haben eine Entsprechung in core.`);
    process.exit(0);
}

console.log("Ohne Entsprechung in core:\n");
for (const schluessel of fehlend) {
    console.log(`  ${schluessel}`);
    for (const datei of [...new Set(aufrufe.get(schluessel))]) console.log(`      ${datei}`);
}
console.log(`\n${fehlend.length} von ${aufrufe.size} Aufrufen gehen ins Leere.`);
process.exit(1);
