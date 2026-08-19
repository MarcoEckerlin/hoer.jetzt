import React from "react";
import { Feld, Text, Mehrzeilig, Schalter } from "../teile/felder.jsx";
import { useInstanzKonfig, Betriebsseite } from "./instanz-felder.jsx";
import Bildfeld from "../teile/Bildfeld.jsx";

/**
 * Marke, Rechtliches und Wartung - aus dem alten Adminbereich hierher geholt.
 *
 * <p>Der Wartungsschalter steht bewusst ganz unten und nicht bei den
 * Adressen: er nimmt allen ausser den Bot-Admins die Oberflaeche weg. Das
 * gehoert nicht zwischen zwei Textfelder.</p>
 */
export default function Instanz() {
    const { konfig, fehler, wert, setzen, leiste } = useInstanzKonfig();

    if (!konfig && !fehler) return <div className="ladeschirm"><div className="puls" /></div>;

    return (
        <Betriebsseite
            titel="Instanz"
            hilfe="Wie sich der Bot nach außen zeigt — Name, Bilder, Rechtliches."
            fehler={fehler}
        >
            <section className="karte-flach">
                <h2>Adressen</h2>
                <div className="feldgitter">
                    <Feld
                        titel="Öffentliche Adresse"
                        hilfe="Ohne Schrägstrich am Ende. Bestimmt, wohin Discord nach der Anmeldung zurückschickt."
                        kind={<Text wert={wert("webBaseUrl")} setzen={(w) => setzen("webBaseUrl", w)}
                                    platzhalter="https://hoer.jetzt" />}
                    />
                    <Feld
                        titel="Support-Server"
                        hilfe="Einladung zu deinem Hilfe-Server. Leer heißt: /support sagt, dass es keinen gibt, und der Knopf auf der Webseite fällt weg."
                        kind={<Text wert={wert("supportUrl")} setzen={(w) => setzen("supportUrl", w)}
                                    platzhalter="https://discord.gg/… oder https://hoer.jetzt/invite/support" />}
                    />
                    <Feld
                        titel="Einladungslink"
                        hilfe="Wohin jemand geschickt wird, der den Bot auf keinem Server hat."
                        kind={<Text wert={wert("noGuildInviteUrl")} setzen={(w) => setzen("noGuildInviteUrl", w)}
                                    platzhalter="https://discord.com/oauth2/authorize?…" />}
                    />
                </div>
            </section>

            <section className="karte-flach">
                <h2>Bilder</h2>
                <div className="feldgitter">
                    <Feld
                        titel="Markenbild"
                        hilfe="Kleines Logo in der Kopfzeile."
                        kind={<Bildfeld wert={wert("brandImageUrl")} setzen={(w) => setzen("brandImageUrl", w)}
                                    platzhalter="https://…/logo.png" seitenverhaeltnis={1} zielbreite={512} />}
                    />
                    <Feld
                        titel="Titelbild"
                        hilfe="Großes Bild auf der Startseite."
                        kind={<Bildfeld wert={wert("heroImageUrl")} setzen={(w) => setzen("heroImageUrl", w)}
                                    platzhalter="https://…/hero.jpg" seitenverhaeltnis={16 / 9} zielbreite={1600} />}
                    />
                </div>
            </section>

            <section className="karte-flach">
                <h2>Rechtliches</h2>
                <p className="leise">
                    Steht im Impressum. In Deutschland ist das keine Kür — ein Dienst ohne
                    Anbieterkennzeichnung ist abmahnfähig.
                </p>
                <div className="feldgitter">
                    <Feld
                        titel="Betreiber"
                        kind={<Text wert={wert("legalOwnerName")} setzen={(w) => setzen("legalOwnerName", w)} />}
                    />
                    <Feld
                        titel="E-Mail"
                        kind={<Text wert={wert("legalEmail")} setzen={(w) => setzen("legalEmail", w)} typ="email" />}
                    />
                </div>
                <Feld
                    titel="Anschrift"
                    breit
                    kind={<Mehrzeilig wert={wert("legalAddress")} setzen={(w) => setzen("legalAddress", w)} zeilen={3} />}
                />
            </section>

            <section className="karte-flach">
                <h2>Wartung</h2>
                <Schalter
                    an={wert("maintenanceEnabled") === true}
                    setzen={(w) => setzen("maintenanceEnabled", w)}
                    titel="Wartungsmodus"
                    hilfe="Sperrt das Dashboard für alle außer Bot-Admins. Der Bot selbst spielt weiter."
                />
                <Feld
                    titel="Hinweis"
                    breit
                    hilfe="Was die Ausgesperrten zu sehen bekommen."
                    kind={<Mehrzeilig wert={wert("maintenanceMessage")} setzen={(w) => setzen("maintenanceMessage", w)}
                                      zeilen={2} platzhalter="Wir sind gleich zurück." />}
                />
            </section>

            {leiste}
        </Betriebsseite>
    );
}
