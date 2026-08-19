/*
 * Hell oder dunkel - fuer die Seiten, die core selbst ausliefert.
 *
 * Muss im <head> stehen und ohne "defer" laufen: das Attribut gehoert an das
 * <html>, bevor der erste Strich gezeichnet wird. Sonst sieht man beim Laden
 * kurz die helle Fassung aufblitzen und danach die dunkle - der Effekt, den
 * jeder kennt und niemand will.
 *
 * Speicherschluessel und Werte sind dieselben wie in der React-Oberflaeche
 * (web/src/lib/farbschema.js). Zwei Oberflaechen, eine Wahl: wer im Panel
 * dunkel einstellt, bekommt auch das Impressum dunkel.
 */
(function () {
    var SCHLUESSEL = "hoerjetzt.farbschema";

    function gewaehlt() {
        try {
            var wert = window.localStorage.getItem(SCHLUESSEL);
            return wert === "hell" || wert === "dunkel" ? wert : "system";
        } catch (keinSpeicher) {
            return "system";
        }
    }

    function wirksam(wahl) {
        if (wahl === "hell" || wahl === "dunkel") {
            return wahl;
        }
        return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
            ? "dunkel"
            : "hell";
    }

    function anwenden(wahl) {
        document.documentElement.setAttribute(
            "data-theme",
            wirksam(wahl) === "dunkel" ? "dark" : "light"
        );
    }

    anwenden(gewaehlt());

    // Die Knoepfe gibt es erst, wenn das Dokument steht.
    document.addEventListener("DOMContentLoaded", function () {
        var knoepfe = document.querySelectorAll("[data-theme-option]");

        function markieren() {
            var wahl = gewaehlt();
            for (var i = 0; i < knoepfe.length; i++) {
                knoepfe[i].setAttribute(
                    "aria-pressed",
                    knoepfe[i].getAttribute("data-theme-option") === wahl ? "true" : "false"
                );
            }
        }

        for (var i = 0; i < knoepfe.length; i++) {
            knoepfe[i].addEventListener("click", function () {
                var wahl = this.getAttribute("data-theme-option");
                try {
                    if (wahl === "system") {
                        window.localStorage.removeItem(SCHLUESSEL);
                    } else {
                        window.localStorage.setItem(SCHLUESSEL, wahl);
                    }
                } catch (keinSpeicher) {
                    // Dann gilt die Wahl nur fuer diese Seite.
                }
                anwenden(wahl);
                markieren();
            });
        }
        markieren();
    });

    // Nachziehen, solange "System" gewaehlt ist.
    if (window.matchMedia) {
        var abfrage = window.matchMedia("(prefers-color-scheme: dark)");
        var nachziehen = function () {
            if (gewaehlt() === "system") {
                anwenden("system");
            }
        };
        if (abfrage.addEventListener) {
            abfrage.addEventListener("change", nachziehen);
        } else if (abfrage.addListener) {
            abfrage.addListener(nachziehen);
        }
    }
})();
