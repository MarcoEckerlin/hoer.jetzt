import React, { useEffect, useState } from "react";
import { markeLaden, seitensymbolSetzen } from "../lib/api.js";
import Farbschema from "../teile/Farbschema.jsx";

/**
 * Die Seite vor der Anmeldung.
 *
 * <h2>Was hier bewusst nicht steht</h2>
 *
 * <p>Der Entwurf kam mit "9.400+ Server" und "Spotify Playlists". Beides gibt
 * es nicht: der Bot laeuft auf einer knapp zweistelligen Zahl von Servern und
 * hat keine Spotify-Anbindung. Erfundene Zahlen auf der Startseite sind kein
 * Marketing, sondern eine Behauptung, die beim ersten Blick auf /stats
 * auffliegt - und dort stehen die echten.</p>
 *
 * <p>Stattdessen holt die Seite die Live-Zahlen aus derselben oeffentlichen
 * Schnittstelle wie die Statistikseite. Sind sie klein, stehen sie klein da;
 * werden sie groesser, waechst die Seite mit. Antwortet die Schnittstelle
 * nicht, verschwindet der Block - lieber nichts als eine geratene Zahl.</p>
 */
export default function Startseite({ angemeldet }) {
    const [marke, setMarke] = useState(null);
    const [zahlen, setZahlen] = useState(null);
    const [menueOffen, setMenueOffen] = useState(false);
    const [modus, setModus] = useState("WEBRADIO");

    useEffect(() => {
        markeLaden().then((m) => {
            setMarke(m);
            seitensymbolSetzen(m?.avatarUrl);
        });
        fetch("/api/public/stats")
            .then((a) => (a.ok ? a.json() : null))
            .then((d) => setZahlen(d?.summary || null))
            .catch(() => setZahlen(null));
    }, []);

    // Einblenden beim Scrollen - dieselbe Mechanik wie im Entwurf.
    useEffect(() => {
        const beobachter = new IntersectionObserver(
            (eintraege) => eintraege.forEach((e) => e.isIntersecting && e.target.classList.add("is-visible")),
            { threshold: 0.14 }
        );
        document.querySelectorAll(".reveal").forEach((el) => beobachter.observe(el));
        return () => beobachter.disconnect();
    }, []);

    const name = marke?.displayName || "Hör.Jetzt";
    const ziel = angemeldet ? "/dashboard" : "/login";

    return (
        <main className="site-shell">
            <nav className="nav-wrap" aria-label="Hauptnavigation">
                <a className="logo-link" href="#oben" aria-label={`${name} Startseite`}>
                    <span className="logo-crop">
                        <img src={marke?.avatarUrl || "/hoer-jetzt-logo.webp"} alt={name} />
                    </span>
                </a>
                <div className={`nav-links ${menueOffen ? "open" : ""}`}>
                    <a href="#kann" onClick={() => setMenueOffen(false)}>Was er kann</a>
                    <a href="#modi" onClick={() => setMenueOffen(false)}>Musik</a>
                    <a href="/stats" onClick={() => setMenueOffen(false)}>Statistiken</a>
                </div>
                {/*
                  Eine Huelle statt dreier Geschwister.
                  .nav-wrap ist ein Grid mit drei Spalten. Standen Umschalter,
                  Knopf und Menuetaste einzeln darin, waren es vier Kinder auf
                  drei Spalten - das vierte fiel in eine zweite Zeile, und der
                  Panel-Knopf sass versetzt unter der Leiste. Zusammengefasst
                  belegen sie eine Spalte und ordnen sich darin selbst.
                */}
                <div className="nav-rechts">
                    <Farbschema klein />
                    <a className="nav-cta" href={ziel}>
                        {angemeldet ? "Zum Panel" : "Anmelden"} <span>↗</span>
                    </a>
                    <button
                        className="menu-button"
                        onClick={() => setMenueOffen(!menueOffen)}
                        aria-label="Menü öffnen"
                        aria-expanded={menueOffen}
                    >
                        <span /><span />
                    </button>
                </div>
            </nav>

            <section className="hero" id="oben">
                <div className="noise" />
                <div className="orbit orbit-one" />
                <div className="orbit orbit-two" />

                <div className="hero-copy">
                    <div className="eyebrow"><i /> Musikbot für Discord</div>
                    <h1>DEIN SERVER.<br /><span>DEIN SOUND.</span></h1>
                    <p>
                        Warteschlange, Webradio, Lautstärke und Rollen — bedienbar im Browser statt
                        über zwanzig Slash-Commands, die sich niemand merkt. Der Bot bleibt in
                        Discord, die Arbeit macht die Oberfläche.
                    </p>
                    <div className="hero-actions">
                        <a className="primary-button" href={ziel}>
                            {angemeldet ? "ZUM PANEL" : "MIT DISCORD ANMELDEN"} <span>↗</span>
                        </a>
                        {/*
                          Nur wenn ein Ziel da ist.
                          Der Link kommt aus der Instanz-Einstellung; fehlt sie,
                          baut der Server ihn aus der Client-ID. Geht auch das
                          nicht, faellt der Knopf weg - ein Knopf, der ins Leere
                          fuehrt, ist schlimmer als keiner.

                          Getrennt vom Anmelden-Knopf, weil es zwei Vorgaenge
                          sind: anmelden heisst "ich will das Panel sehen",
                          hinzufuegen heisst "der Bot soll auf meinen Server".
                          Wer den Bot noch nicht hat, kam ueber die Anmeldung
                          bisher in ein leeres Panel.
                        */}
                        {marke?.inviteUrl && (
                            <a
                                className="secondary-button"
                                href={marke.inviteUrl}
                                target="_blank"
                                rel="noopener noreferrer"
                            >
                                BOT HINZUFÜGEN <span>↗</span>
                            </a>
                        )}
                        <a className="text-link" href="#kann">Erst ansehen <span>↓</span></a>
                    </div>

                    {zahlen && (
                        <div className="trust-row">
                            <div className="avatars" aria-hidden="true">
                                <b>♪</b><b>♫</b><b>♬</b>
                            </div>
                            <p>
                                <strong>
                                    {zahlen.liveListeners > 0
                                        ? `${zahlen.liveListeners} hören gerade zu`
                                        : "Gerade ist es still"}
                                </strong><br />
                                {zahlen.trackedGuilds30d > 0
                                    ? `${zahlen.trackedGuilds30d} Server in den letzten 30 Tagen.`
                                    : "Sei der erste Server."}
                            </p>
                        </div>
                    )}
                </div>

                <div className="visual-stage" id="player">
                    <div className="sticker sticker-top">24/7<br /><span>ONLINE</span></div>

                    <div className="player-card">
                        <div className="player-head">
                            <span>LÄUFT GERADE</span>
                            <b><i /> {name.toUpperCase()}</b>
                        </div>
                        <div className="cover-art">
                            <div className="cover-grid" />
                            <div className="cover-sun" />
                            <span>{zahlen?.liveStreams > 0 ? "WEBRADIO" : "BEREIT"}<br />IM KANAL</span>
                            <small>{zahlen?.listenedTime30d ? `${zahlen.listenedTime30d} in 30 Tagen` : "OHNE WERBUNG"}</small>
                        </div>
                        <div className="track-meta">
                            <div>
                                <strong>Alles im Browser</strong>
                                <span>Suchen · Warteschlange · Lautstärke</span>
                            </div>
                        </div>
                        <div className="waveform is-playing" aria-hidden="true">
                            {[28, 58, 86, 45, 74, 100, 62, 38, 82, 54, 92, 68, 32, 72, 48, 88, 60, 36].map((h, i) => (
                                <i key={i} style={{ height: `${h}%`, animationDelay: `${i * -0.08}s` }} />
                            ))}
                        </div>
                        <div className="timeline">
                            <span>0:00</span><b><i style={{ width: "38%" }} /></b><span>∞</span>
                        </div>
                    </div>

                    <div className="floating-note note-one">♪</div>
                    <div className="floating-note note-two">♫</div>
                    <div className="sticker sticker-bottom">KEINE WERBUNG.<br />KEIN KONTO.</div>
                </div>
            </section>

            <section className="ticker" aria-label="Kurz gesagt">
                <div>
                    WEBRADIO ✦ EIGENE SENDER ✦ WARTESCHLANGE IM BROWSER ✦ TICKETS ✦
                    REAKTIONSROLLEN ✦ AUTOMATISCHE SPRACHKANÄLE ✦ KEINE WERBUNG ✦
                    WEBRADIO ✦ EIGENE SENDER ✦ WARTESCHLANGE IM BROWSER ✦ TICKETS ✦
                    REAKTIONSROLLEN ✦ AUTOMATISCHE SPRACHKANÄLE ✦ KEINE WERBUNG ✦
                </div>
            </section>

            <section className="features-section reveal" id="kann">
                <div className="section-kicker">01 — WAS ER WIRKLICH KANN</div>
                <div className="section-heading">
                    <h2>MEHR ALS<br />NUR <em>PLAY.</em></h2>
                    <p>
                        Kein Funktionsversprechen, das erst noch gebaut wird. Das hier läuft
                        — und der Rest der Seite verspricht nichts darüber hinaus.
                    </p>
                </div>
                <div className="feature-grid">
                    <article className="feature-card featured">
                        <span className="feature-no">01</span>
                        <div className="feature-icon">⌁</div>
                        <h3>Eine Nachricht statt zwanzig</h3>
                        <p>
                            Der Bot schreibt pro Hörsitzung genau eine Nachricht und schreibt sie
                            fort. Kein Kanal, der nach zehn Titeln nur noch aus Bot-Blöcken besteht.
                        </p>
                        <b>BEDIENUNG IM BROWSER</b>
                    </article>
                    <article className="feature-card">
                        <span className="feature-no">02</span>
                        <div className="feature-icon">∞</div>
                        <h3>Audio, das nicht abreißt</h3>
                        <p>
                            Fällt ein Audio-Knoten aus, zieht die Wiedergabe auf einen anderen um —
                            mit Titel, Position und Lautstärke. Hörbar ist höchstens ein Aussetzer.
                        </p>
                        <b>MEHRERE KNOTEN</b>
                    </article>
                    <article className="feature-card">
                        <span className="feature-no">03</span>
                        <div className="feature-icon">◎</div>
                        <h3>Module je Server</h3>
                        <p>
                            Willkommen, Verifizierung, Reaktionsrollen, Tickets, automatische
                            Sprachkanäle. Einzeln einschaltbar, mit eigenen Nachrichten.
                        </p>
                        <b>EINZELN SCHALTBAR</b>
                    </article>
                </div>
            </section>

            <section className="mix-section reveal" id="modi">
                <div className="mix-art" aria-hidden="true">
                    {/*
                      Zwei Ebenen statt einer: aussen die Huelle mit dem
                      Schatten, innen die Platte, die sich dreht. Sonst wandert
                      der Schatten mit der Platte im Kreis - und ein Schatten,
                      der um sein Objekt herumlaeuft, sieht aus wie ein Fehler,
                      weil das Licht in Wirklichkeit stehen bleibt.
                    */}
                    <div className="mix-disc-huelle">
                        <div className={`mix-disc genre-${modus.toLowerCase().replace(/[^a-z]/g, "")}`}>
                            <span>HÖR<br />JETZT<br /><small>{modus}</small></span>
                        </div>
                    </div>
                    <div className="needle" />
                </div>
                <div className="mix-copy">
                    <div className="section-kicker">02 — WOHER DIE MUSIK KOMMT</div>
                    <h2>VIER WEGE.<br /><em>EIN KANAL.</em></h2>
                    <p>{BESCHREIBUNG[modus]}</p>
                    <div className="genre-tabs" role="tablist" aria-label="Wiedergabearten">
                        {Object.keys(BESCHREIBUNG).map((m) => (
                            <button
                                key={m}
                                className={modus === m ? "active" : ""}
                                onClick={() => setModus(m)}
                                role="tab"
                                aria-selected={modus === m}
                            >
                                {m}
                            </button>
                        ))}
                    </div>
                    <div className="live-mix">
                        <i />
                        <span>GERADE IM EINSATZ</span>
                        <b>{zahlen?.liveStreams > 0 ? `${zahlen.liveStreams} KANAL/KANÄLE` : "BEREIT"}</b>
                    </div>
                </div>
            </section>

            <section className="community-section reveal" id="einladen">
                <div className="community-stars">✦　✦　✦</div>
                <div className="community-copy">
                    <div className="section-kicker light">BEREIT?</div>
                    <h2>DRÜCK PLAY.<br /><em>BLEIB DABEI.</em></h2>
                    <p>
                        Anmelden mit Discord, Bot einladen, Sprachkanal wählen. Kein Formular,
                        keine Kreditkarte, kein Konto außer deinem Discord-Konto.
                    </p>
                    <a href={ziel} className="invite-big">
                        {angemeldet ? "ZUM PANEL" : "JETZT ANMELDEN"} <span>↗</span>
                    </a>
                </div>
                {zahlen && (
                    <div className="community-stat">
                        <strong>{zahlen.uniqueListeners30d ?? 0}</strong>
                        <span>Hörer in 30 Tagen</span>
                        <i />
                    </div>
                )}
            </section>

            <footer>
                <a className="logo-link footer-logo" href="#oben" aria-label={`${name} Startseite`}>
                    <span className="logo-crop">
                        <img src={marke?.avatarUrl || "/hoer-jetzt-logo.webp"} alt={name} />
                    </span>
                </a>
                <p>Der Musikbot für Server, die besser klingen wollen.</p>
                <div>
                    <a href="/stats">Statistiken</a>
                    {/* Nur wenn hinterlegt - ein Support-Link ins Leere ist
                        schlimmer als kein Support-Link. */}
                    {marke?.supportUrl && (
                        <a href={marke.supportUrl} target="_blank" rel="noopener noreferrer">Support</a>
                    )}
                    <a href="/impressum">Impressum</a>
                    <a href="/datenschutz">Datenschutz</a>
                    <a href="/nutzungsbedingungen">Nutzungsbedingungen</a>
                </div>
                <span>© {new Date().getFullYear()} {name}</span>
            </footer>
        </main>
    );
}

/**
 * Die vier Wege, auf denen Ton in den Kanal kommt.
 *
 * <p>Im Entwurf standen hier Musikrichtungen (LO-FI, HIP-HOP …) und ein
 * "Live Mix" - beides gibt es nicht. Was es gibt, sind diese vier
 * Wiedergabearten; die Kachel bleibt, der Inhalt stimmt jetzt.</p>
 */
const BESCHREIBUNG = {
    WEBRADIO: "Sender aus einer gepflegten Liste. Einmal gestartet läuft er weiter, "
        + "bis jemand ihn stoppt — keine Warteschlange, kein Ende.",
    "EIGENE SENDER": "Jeder Server trägt seine eigenen Stream-Adressen ein. Sie stehen "
        + "nur dort und mischen sich mit den globalen in einer Liste.",
    SUCHE: "Titel oder Link eingeben. Auf Wunsch erst eine Vorschau der Treffer, damit "
        + "nicht das erstbeste Ergebnis läuft.",
    WARTESCHLANGE: "Titel sammeln, Reihenfolge ändern, einzeln entfernen — im Browser "
        + "statt über Befehle."
};
