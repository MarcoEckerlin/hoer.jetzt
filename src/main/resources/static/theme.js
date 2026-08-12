/*
 * Theme-Umschaltung fuer alle Seiten.
 *
 * Reihenfolge ist wichtig: das gespeicherte Theme wird gesetzt, sobald das
 * Skript laeuft - deshalb wird es im <head> eingebunden. Wuerde man bis
 * DOMContentLoaded warten, blitzt beim Laden kurz das falsche Farbschema auf.
 */
(function () {
    "use strict";

    var STORAGE_KEY = "discordbot-theme";
    var MODES = ["system", "dark", "light"];

    function readStoredMode() {
        try {
            var stored = window.localStorage.getItem(STORAGE_KEY);
            return MODES.indexOf(stored) === -1 ? "system" : stored;
        } catch (error) {
            // Privater Modus oder blockierte Storage-API: Systemvorgabe nutzen.
            return "system";
        }
    }

    function storeMode(mode) {
        try {
            window.localStorage.setItem(STORAGE_KEY, mode);
        } catch (error) {
            /* Nicht kritisch - die Auswahl gilt dann nur fuer diese Sitzung. */
        }
    }

    function resolveTheme(mode) {
        if (mode === "dark" || mode === "light") {
            return mode;
        }
        return window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches
            ? "light"
            : "dark";
    }

    function apply(mode) {
        document.documentElement.setAttribute("data-theme", resolveTheme(mode));
        document.documentElement.setAttribute("data-theme-mode", mode);
        updateButtons(mode);
    }

    function updateButtons(mode) {
        var buttons = document.querySelectorAll("[data-theme-option]");
        for (var index = 0; index < buttons.length; index++) {
            var button = buttons[index];
            button.setAttribute("aria-pressed", String(button.dataset.themeOption === mode));
        }
    }

    var currentMode = readStoredMode();
    apply(currentMode);

    // Wechselt der Nutzer die Systemeinstellung, folgt die Seite - aber nur,
    // solange keine explizite Auswahl getroffen wurde.
    if (window.matchMedia) {
        var query = window.matchMedia("(prefers-color-scheme: light)");
        var onChange = function () {
            if (currentMode === "system") {
                apply("system");
            }
        };
        if (query.addEventListener) {
            query.addEventListener("change", onChange);
        } else if (query.addListener) {
            query.addListener(onChange);
        }
    }

    function wireButtons() {
        updateButtons(currentMode);
        document.addEventListener("click", function (event) {
            var target = event.target.closest ? event.target.closest("[data-theme-option]") : null;
            if (!target) {
                return;
            }
            var mode = target.dataset.themeOption;
            if (MODES.indexOf(mode) === -1) {
                return;
            }
            currentMode = mode;
            storeMode(mode);
            apply(mode);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", wireButtons);
    } else {
        wireButtons();
    }

    window.discordBotTheme = {
        get: function () {
            return currentMode;
        },
        set: function (mode) {
            if (MODES.indexOf(mode) === -1) {
                return;
            }
            currentMode = mode;
            storeMode(mode);
            apply(mode);
        }
    };
})();
