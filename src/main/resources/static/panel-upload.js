/*
 * Bild-Upload fuer Admin- und Serverpanel.
 *
 * Die alten Oberflaechen hatten dafuer ein eigenes Skript. Beim Neuaufbau
 * blieben nur die URL-Felder uebrig - der Endpunkt /api/assets/upload war
 * damit nicht mehr erreichbar. Statt jeden Renderpfad einzeln anzufassen,
 * werden die Felder hier nachtraeglich erkannt und mit einem Knopf versehen.
 * Das haelt die Panels frei davon und wirkt auch auf Felder, die erst
 * spaeter im DOM auftauchen.
 *
 * Erkannt wird an der Benennung: alles, was auf "imageUrl" oder
 * "thumbnailUrl" endet - unabhaengig davon, ob das Panel per data-bind
 * (Admin) oder data-p (Server) arbeitet.
 */
(function () {
    "use strict";

    var MUSTER = /(imageurl|thumbnailurl)$/;
    var dateiFeld = null;
    var zielFeld = null;

    function istBildfeld(input) {
        var name = input.getAttribute("data-bind") || input.getAttribute("data-p") || "";
        return MUSTER.test(name.toLowerCase());
    }

    function knopf() {
        var el = document.createElement("button");
        el.type = "button";
        el.className = "upload-btn";
        el.innerHTML = '<svg viewBox="0 0 24 24"><use href="#i-upload"/></svg>Bild';
        el.title = "Bild hochladen";
        return el;
    }

    /** Versieht alle noch unbehandelten Bildfelder mit einem Upload-Knopf. */
    function ausstatten(wurzel) {
        var felder = (wurzel || document).querySelectorAll("input[data-bind], input[data-p]");
        Array.prototype.forEach.call(felder, function (input) {
            if (input.dataset.uploadBereit === "1" || !istBildfeld(input)) {
                return;
            }
            var feld = input.closest(".field") || input.parentNode;
            if (!feld) {
                return;
            }
            input.dataset.uploadBereit = "1";
            feld.classList.add("has-upload");
            var el = knopf();
            el.addEventListener("click", function () {
                zielFeld = input;
                dateiFeld.value = "";
                dateiFeld.click();
            });
            feld.appendChild(el);
        });
    }

    // Gleiche Bauweise wie die Panels: ein Kindelement je Meldung, das sich
    // nach kurzer Zeit selbst entfernt.
    function meldung(text, fehler) {
        var box = document.getElementById("toast");
        if (!box) {
            return;
        }
        var eintrag = document.createElement("div");
        if (fehler) {
            eintrag.className = "bad";
        }
        eintrag.textContent = text;
        box.appendChild(eintrag);
        window.setTimeout(function () { eintrag.remove(); }, fehler ? 8000 : 4000);
    }

    function hochladen(datei) {
        if (!zielFeld || !datei) {
            return;
        }
        var input = zielFeld;
        var el = (input.closest(".field") || input.parentNode).querySelector(".upload-btn");
        if (el) {
            el.disabled = true;
        }

        var daten = new FormData();
        daten.append("file", datei);

        window.fetch("/api/assets/upload", { method: "POST", body: daten, credentials: "same-origin" })
            .then(function (antwort) {
                if (!antwort.ok) {
                    return antwort.text().then(function (text) {
                        throw new Error(text || ("HTTP " + antwort.status));
                    });
                }
                return antwort.json();
            })
            .then(function (ergebnis) {
                input.value = ergebnis.url || "";
                // Ohne dieses Ereignis merkt die Aenderungsverfolgung der
                // Panels nichts davon und der Wert wird nicht gespeichert.
                input.dispatchEvent(new Event("input", { bubbles: true }));
                input.dispatchEvent(new Event("change", { bubbles: true }));
                meldung("Bild hochgeladen.", false);
            })
            .catch(function (fehler) {
                meldung("Upload fehlgeschlagen: " + (fehler.message || fehler), true);
            })
            .then(function () {
                if (el) {
                    el.disabled = false;
                }
                zielFeld = null;
            });
    }

    function start() {
        dateiFeld = document.createElement("input");
        dateiFeld.type = "file";
        dateiFeld.accept = "image/png,image/jpeg,image/gif,image/webp";
        dateiFeld.style.display = "none";
        dateiFeld.addEventListener("change", function () {
            hochladen(dateiFeld.files && dateiFeld.files[0]);
        });
        document.body.appendChild(dateiFeld);

        ausstatten(document);

        // Die Panels bauen ihre Seiten bei jedem Wechsel neu auf. Ein
        // MutationObserver ist hier billiger als ein Hook in jede Renderfunktion.
        var seite = document.getElementById("page") || document.body;
        new MutationObserver(function () { ausstatten(seite); })
            .observe(seite, { childList: true, subtree: true });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
}());
