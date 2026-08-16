import React, { useEffect, useState } from "react";
import { markeLaden } from "../lib/api.js";
import Wellen from "../teile/Wellen.jsx";
import Farbschema from "../teile/Farbschema.jsx";

/**
 * Die Seite vor der Anmeldung.
 *
 * <p>Die alte bestand aus zwei Spalten Text und drei Kaesten, die alle gleich
 * laut waren - man las alles oder nichts. Diese hier hat eine Rangfolge: eine
 * Aussage, ein Knopf, dann der Beweis, dann die Einzelheiten.</p>
 */
export default function Startseite({ angemeldet }) {
    const [marke, setMarke] = useState(null);

    useEffect(() => {
        markeLaden().then(setMarke);
    }, []);

    const name = marke?.displayName || "hoer.jetzt";

    return (
        <div className="landung">
            <header className="kopfleiste">
                <a className="wortmarke" href="/">
                    {marke?.avatarUrl
                        ? <img src={marke.avatarUrl} alt="" />
                        : <span className="wortmarke-punkt" />}
                    <strong>{name}</strong>
                </a>
                <nav>
                    <a href="#kann">Was er kann</a>
                    <a href="#so">So läuft es</a>
                    <a href="/stats">Zahlen</a>
                </nav>
                <Farbschema klein />
                <a className="knopf knopf-hell" href={angemeldet ? "/dashboard" : "/login"}>
                    {angemeldet ? "Zum Panel" : "Anmelden"}
                </a>
            </header>

            <section className="held">
                <Wellen />
                <div className="held-inhalt">
                    <span className="marke-klein">Musikbot für Discord</span>
                    <h1>
                        Musik auf deinem Server.<br />
                        <em>Ohne Befehlsliste.</em>
                    </h1>
                    <p className="held-text">
                        Warteschlange, Webradio, Lautstärke und Rollen — bedienbar im Browser,
                        nicht über zwanzig Slash-Commands, die sich niemand merkt.
                        Der Bot bleibt in Discord, die Arbeit macht die Oberfläche.
                    </p>
                    <div className="held-knoepfe">
                        <a className="knopf knopf-gross" href={angemeldet ? "/dashboard" : "/login"}>
                            {angemeldet ? "Zum Panel" : "Mit Discord anmelden"}
                        </a>
                        <a className="knopf knopf-leise knopf-gross" href="#kann">Erst ansehen</a>
                    </div>
                    <p className="kleingedrucktes">
                        Kostenlos. Keine Werbung. Kein Konto außer deinem Discord-Konto.
                    </p>
                </div>
            </section>

            <section className="beweis" id="kann">
                <div className="beweis-gitter">
                    <Karte
                        farbe="a"
                        titel="Eine Nachricht statt zwanzig"
                        text="Der Bot schreibt pro Hörsitzung genau eine Nachricht und schreibt sie fort. Kein Kanal, der nach zehn Titeln nur noch aus Bot-Blöcken besteht."
                    />
                    <Karte
                        farbe="b"
                        titel="Audio, das nicht abreißt"
                        text="Fällt ein Audio-Knoten aus, zieht die Wiedergabe auf einen anderen um — mit Titel, Position und Lautstärke. Hörbar ist höchstens ein Aussetzer."
                    />
                    <Karte
                        farbe="c"
                        titel="Module pro Server"
                        text="Willkommen, Verifizierung, Reaction-Roles, Tickets, automatische Sprachkanäle. Einzeln einschaltbar, mit eigenen Nachrichten, die du selbst gestaltest."
                    />
                    <Karte
                        farbe="d"
                        titel="Webradio und KI-Radio"
                        text="Senderlisten aus der Datenbank, sauber sortiert. Und auf Wunsch ein Radio, das die Playlist selbst weiterbaut."
                    />
                </div>
            </section>

            <section className="ablauf" id="so">
                <h2>Drei Schritte, dann läuft Musik</h2>
                <ol className="schritte">
                    <li>
                        <span className="nummer">1</span>
                        <h3>Bot einladen</h3>
                        <p>Ein Klick auf Discord, die üblichen Rechte. Kein Formular.</p>
                    </li>
                    <li>
                        <span className="nummer">2</span>
                        <h3>Anmelden</h3>
                        <p>Mit deinem Discord-Konto. Du siehst genau die Server, auf denen du etwas zu sagen hast.</p>
                    </li>
                    <li>
                        <span className="nummer">3</span>
                        <h3>Abspielen</h3>
                        <p>Suchbegriff eintippen, Sprachkanal wählen, fertig. Der Rest steht im Dashboard.</p>
                    </li>
                </ol>
                <a className="knopf knopf-gross" href="/login">Los geht’s</a>
            </section>

            <footer className="fussleiste">
                <div>
                    <strong>{name}</strong>
                    <span>© {new Date().getFullYear()}</span>
                </div>
                <nav>
                    <a href="/impressum">Impressum</a>
                    <a href="/datenschutz">Datenschutz</a>
                    <a href="/stats">Stats</a>
                </nav>
            </footer>
        </div>
    );
}

function Karte({ farbe, titel, text }) {
    return (
        <article className={`karte karte-${farbe}`}>
            <h3>{titel}</h3>
            <p>{text}</p>
        </article>
    );
}
