import React, { useEffect, useState } from "react";
import Startseite from "./seiten/Startseite.jsx";
import Dashboard from "./seiten/Dashboard.jsx";
import Betrieb from "./seiten/Betrieb.jsx";
import { api } from "./lib/api.js";

/**
 * Was gezeigt wird, entscheidet der Pfad - nicht der Anmeldezustand.
 *
 * <p>Vorher hing es allein daran, ob jemand angemeldet war: wer es war, bekam
 * ueberall das Dashboard. Damit war die Startseite fuer genau die Leute
 * unerreichbar, die den Bot benutzen - sie haetten sich abmelden muessen, um
 * die eigene Seite anzusehen. Und ein Lesezeichen auf /dashboard fuehrte
 * abgemeldet auf die Startseite, ohne zu sagen warum.</p>
 *
 * <p>Jetzt gilt: <code>/</code> ist die Startseite, <code>/dashboard</code> ist
 * das Panel. Die Anmeldung entscheidet nur noch, was auf dem jeweiligen Pfad zu
 * sehen ist.</p>
 *
 * <p>Die Abfrage von <code>/api/dashboard/guilds</code> laeuft trotzdem immer:
 * eine 401 ist hier kein Fehler, sondern die Auskunft "nicht angemeldet" - und
 * die braucht auch die Startseite, um den richtigen Knopf zu zeigen.</p>
 */
export default function App() {
    const [zustand, setZustand] = useState("laedt");
    const [server, setServer] = useState([]);

    useEffect(() => {
        api("GET", "/api/dashboard/guilds")
            .then((liste) => {
                setServer(liste || []);
                setZustand("angemeldet");
            })
            .catch(() => setZustand("abgemeldet"));
    }, []);

    if (zustand === "laedt") {
        return (
            <div className="ladeschirm">
                <div className="puls" />
            </div>
        );
    }

    const imAdmin = window.location.pathname.startsWith("/admin");
    const imPanel = window.location.pathname.startsWith("/dashboard") || imAdmin;
    const angemeldet = zustand === "angemeldet";

    /*
     * Der Betriebsbereich haengt an der Adresse, nicht an einem Zustand: so
     * ueberlebt er ein F5 und ist verlinkbar wie jede Modulseite.
     *
     * Zwei Wege fuehren hin, und das ist Absicht:
     *   /admin[/seite]              - die Adresse, die man sich merkt
     *   /dashboard#/betrieb/seite   - der Weg aus dem Panel heraus
     * Der zweite bleibt bestehen, weil es Lesezeichen darauf gibt.
     */
    const ausHash = /^#\/betrieb(?:\/([a-z]+))?/.exec(window.location.hash || "");
    const ausPfad = /^\/admin(?:\/([a-z]+))?\/?$/.exec(window.location.pathname);

    if (imAdmin && !angemeldet) {
        // Ohne Anmeldung gibt es hier nichts zu sehen - und der Bot laesse
        // ohnehin keinen Aufruf durch. Zur Startseite, dort ist der Login.
        window.location.replace("/");
        return <div className="ladeschirm"><div className="puls" /></div>;
    }

    if (imPanel && angemeldet && (imAdmin || ausHash)) {
        return (
            <Betrieb
                seite={imAdmin ? ausPfad?.[1] : ausHash[1]}
                gehe={(ziel) => {
                    if (ziel === "zurueck") {
                        window.location.href = "/dashboard";
                        return;
                    }
                    // Unter /admin traegt der Pfad den Ort, im Panel der Hash.
                    // Ein Bereichswechsel ist ein Seitenwechsel, kein
                    // Zustandswechsel - neu laden ist hier ehrlicher als ein
                    // zweiter Wegweiser, der beide Baeume auseinanderhaelt.
                    if (imAdmin) {
                        window.location.href = `/admin/${ziel}`;
                    } else {
                        window.location.hash = `#/betrieb/${ziel}`;
                        window.location.reload();
                    }
                }}
            />
        );
    }

    if (imPanel && angemeldet) {
        return <Dashboard server={server} />;
    }

    if (imPanel) {
        return (
            <div className="hinweisseite">
                <h1>Nicht angemeldet</h1>
                <p>
                    Für das Panel brauchst du eine Anmeldung über Discord — nur so weiß der Bot,
                    welche Server dir gehören.
                </p>
                <a className="knopf" href="/login">Mit Discord anmelden</a>
            </div>
        );
    }

    return <Startseite angemeldet={angemeldet} />;
}
