/*
 * Bot-Verwaltung.
 *
 * Aufbau: ein Zustandsobjekt, Seiten als reine Renderfunktionen, und ein
 * Aenderungsprotokoll ("changed"), das nur die tatsaechlich angefassten Felder
 * an den Server schickt.
 *
 * Warum nur die geaenderten Felder? Der Server behandelt in
 * AdminSettingsRequest ein null als "unveraendert lassen". Wuerden wir jedes
 * Mal alle Felder mitschicken, wuerde ein leer zurueckgeliefertes Geheimnis
 * (Token, Client-Secret, API-Schluessel) beim naechsten Speichern die echte
 * Konfiguration ueberschreiben. Genau dieser Fehler ist leicht zu machen und
 * schwer zu bemerken.
 */
(function () {
    "use strict";

    // ------------------------------------------------------------------
    // Zustand
    // ------------------------------------------------------------------

    var state = {
        config: null,
        runtime: null,
        admins: null,
        guilds: null,
        audit: null,
        page: "overview",
        loading: {}
    };

    var changed = {};          // Feldname -> neuer Wert (nur Instanzkonfiguration)
    var listsDirty = false;    // Deployments/Lavalink wurden angefasst

    var PAGES = {
        overview:    { icon: "#i-home",     title: "Übersicht" },
        brand:       { icon: "#i-brand",    title: "Marke & Rechtliches" },
        maintenance: { icon: "#i-wrench",   title: "Wartung" },
        bot:         { icon: "#i-key",      title: "Zugang & Status" },
        login:       { icon: "#i-shield",   title: "Discord-Login" },
        llm:         { icon: "#i-ai",       title: "KI-Anbieter" },
        deployments: { icon: "#i-deploy",   title: "Deployments" },
        lavalink:    { icon: "#i-plug",     title: "Lavalink" },
        guilds:      { icon: "#i-server",   title: "Server" },
        admins:      { icon: "#i-users",    title: "Admins" },
        audit:       { icon: "#i-clock",    title: "Protokoll" }
    };

    var page = document.getElementById("page");
    var savebar = document.getElementById("savebar");

    // ------------------------------------------------------------------
    // Werkzeug
    // ------------------------------------------------------------------

    function esc(value) {
        return String(value === null || value === undefined ? "" : value)
            .replace(/[&<>"']/g, function (c) {
                return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
            });
    }

    function toast(message, bad) {
        var box = document.getElementById("toast");
        var item = document.createElement("div");
        if (bad) {
            item.className = "bad";
        }
        item.textContent = message;
        box.appendChild(item);
        window.setTimeout(function () {
            item.remove();
        }, bad ? 8000 : 4000);
    }

    async function api(method, url, body) {
        var options = { method: method, headers: { "Accept": "application/json" } };
        if (body !== undefined) {
            options.headers["Content-Type"] = "application/json";
            options.body = JSON.stringify(body);
        }

        var response = await fetch(url, options);
        var text = await response.text();
        var data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (ignored) {
                data = null;
            }
        }

        if (!response.ok) {
            // Spring liefert bei ResponseStatusException ein JSON mit "message";
            // sonst bleibt nur der Statuscode.
            var message = (data && (data.message || data.error)) || ("HTTP " + response.status);
            if (response.status === 401) {
                message = "Die Sitzung ist abgelaufen. Bitte neu anmelden.";
            }
            throw new Error(message);
        }
        return data;
    }

    function dateText(iso) {
        if (!iso) {
            return "—";
        }
        var parsed = new Date(iso);
        if (isNaN(parsed.getTime())) {
            return iso;
        }
        return parsed.toLocaleString("de-DE", {
            day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
        });
    }

    function markChanged(key, value) {
        changed[key] = value;
        savebar.classList.add("is-shown");
    }

    function value(key, fallback) {
        if (Object.prototype.hasOwnProperty.call(changed, key)) {
            return changed[key];
        }
        if (state.config && state.config[key] !== null && state.config[key] !== undefined) {
            return state.config[key];
        }
        return fallback === undefined ? "" : fallback;
    }

    // ------------------------------------------------------------------
    // Bausteine
    // ------------------------------------------------------------------

    function head(key, description, extra) {
        var meta = PAGES[key];
        return '<div class="page-head">'
            + '<div class="glyph"><svg viewBox="0 0 24 24"><use href="' + meta.icon + '"/></svg></div>'
            + '<div><h1>' + esc(meta.title) + '</h1><p>' + description + '</p></div>'
            + (extra ? '<div class="head-extra">' + extra + '</div>' : '')
            + '</div>';
    }

    function textField(key, label, hint, opts) {
        var options = opts || {};
        var type = options.password ? "password" : "text";
        var placeholder = options.placeholder || "";
        var shown = options.secret && !Object.prototype.hasOwnProperty.call(changed, key)
            ? ""
            : value(key);
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<input class="input' + (options.mono ? " mono" : "") + '" type="' + type + '" data-bind="' + key + '"'
            + ' value="' + esc(shown) + '" placeholder="' + esc(placeholder) + '" autocomplete="off">'
            + (hint ? '<div class="hint">' + hint + '</div>' : '')
            + '</div>';
    }

    function numberField(key, label, hint, opts) {
        var options = opts || {};
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<input class="input" type="number" data-bind="' + key + '" data-kind="' + (options.decimal ? "double" : "int") + '"'
            + ' value="' + esc(value(key, 0)) + '"'
            + (options.min !== undefined ? ' min="' + options.min + '"' : "")
            + (options.max !== undefined ? ' max="' + options.max + '"' : "")
            + (options.step !== undefined ? ' step="' + options.step + '"' : "")
            + '>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '')
            + '</div>';
    }

    function areaField(key, label, hint, opts) {
        var options = opts || {};
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<textarea class="textarea' + (options.mono ? " mono" : "") + '" data-bind="' + key + '"'
            + (options.rows ? ' rows="' + options.rows + '"' : "") + '>' + esc(value(key)) + '</textarea>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '')
            + '</div>';
    }

    function selectField(key, label, options, hint) {
        var current = String(value(key));
        var html = '<div class="field"><label>' + esc(label) + '</label><select class="select" data-bind="' + key + '">';
        options.forEach(function (option) {
            html += '<option value="' + esc(option.value) + '"'
                + (option.value === current ? " selected" : "") + '>' + esc(option.label) + '</option>';
        });
        html += '</select>' + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
        return html;
    }

    function switchRow(key, title, description, checked) {
        return '<div class="row"><div class="label"><b>' + esc(title) + '</b><span>' + description + '</span></div>'
            + '<div class="control"><label class="switch"><input type="checkbox" data-bind="' + key + '" data-kind="bool"'
            + (checked ? " checked" : "") + '><span class="track"></span><span class="knob"></span></label></div></div>';
    }

    // ------------------------------------------------------------------
    // Seiten
    // ------------------------------------------------------------------

    function renderOverview() {
        var config = state.config || {};
        var runtime = state.runtime || {};
        var guilds = state.guilds || [];
        var admins = (state.admins && state.admins.admins) || [];

        var members = guilds.reduce(function (sum, guild) { return sum + (guild.memberCount || 0); }, 0);
        var granted = guilds.filter(function (guild) {
            return (guild.entitlements || []).some(function (entry) { return entry.enabled; });
        }).length;

        var html = head("overview", "Zustand der Instanz und die wichtigsten Zahlen auf einen Blick.");

        html += '<div class="stats">'
            + '<div class="stat"><div class="k">Server</div><div class="v">' + guilds.length + '</div>'
            + '<div class="s">' + members.toLocaleString("de-DE") + ' Mitglieder insgesamt</div></div>'
            + '<div class="stat"><div class="k">Freigeschaltet</div><div class="v">' + granted + '</div>'
            + '<div class="s">Server mit KI oder AI-Radio</div></div>'
            + '<div class="stat"><div class="k">Bot-Admins</div><div class="v">' + admins.length + '</div>'
            + '<div class="s">' + esc((state.admins && state.admins.currentRole) || "—") + ' — deine Stufe</div></div>'
            + '<div class="stat"><div class="k">Status</div><div class="v" style="font-size:20px">'
            + (runtime.online ? "Online" : "Offline") + '</div>'
            + '<div class="s">' + esc(runtime.activity || "keine Aktivität") + '</div></div>'
            + '</div>';

        html += '<div class="card"><h2>Instanz</h2><div class="card-body">'
            + '<div class="row"><div class="label"><b>Anwendung</b><span>Discord-Application-ID dieser Instanz.</span></div>'
            + '<div class="control"><code>' + esc(config.applicationId || "—") + '</code></div></div>'
            + '<div class="row"><div class="label"><b>Botinhaber</b><span>Wird automatisch als Owner geführt und kann nicht entfernt werden.</span></div>'
            + '<div class="control">' + esc(config.applicationOwnerName || "—") + '</div></div>'
            + '<div class="row"><div class="label"><b>Deployment</b><span>Welcher Eintrag aus der Deployment-Tabelle gerade gilt.</span></div>'
            + '<div class="control">' + esc(config.currentDeploymentDisplayName || config.currentDeploymentKey || "—") + '</div></div>'
            + '<div class="row"><div class="label"><b>Basis-URL</b><span>Grundlage für Login-Rücksprung und Links.</span></div>'
            + '<div class="control">' + esc(config.webBaseUrl || "—") + '</div></div>'
            + '</div></div>';

        if (config.maintenanceEnabled) {
            html = html.replace('<div class="stats">',
                '<div class="notice">Der Wartungsmodus ist aktiv. Außer der Bot-Verwaltung kommt gerade niemand ins Panel.</div><div class="stats">');
        }
        return html;
    }

    function renderBrand() {
        return head("brand", "Name, Bilder und Pflichtangaben dieser Installation. Nichts davon steckt im Programmcode — wer den Bot selbst betreibt, trägt hier seine eigenen Angaben ein.")
            + '<div class="card"><h2>Darstellung</h2><div class="card-body">'
            + textField("brandImageUrl", "Logo-URL", "Erscheint in der Kopfzeile und auf der Startseite. Leer lassen, um den Bot-Avatar zu verwenden.")
            + textField("heroImageUrl", "Titelbild-URL", "Großes Bild auf der öffentlichen Startseite.")
            + '</div></div>'
            + '<div class="card"><h2>Adressen</h2><div class="card-body">'
            + textField("webBaseUrl", "Basis-URL", "Zum Beispiel <code>https://bot.example.org</code>. Ohne abschließenden Schrägstrich.")
            + textField("noGuildInviteUrl", "Einladungslink", "Dorthin wird geschickt, wer sich anmeldet, aber keinen passenden Server hat.")
            + '</div></div>'
            + '<div class="card"><h2>Rechtliches</h2>'
            + '<div class="card-body">'
            + textField("legalOwnerName", "Betreiber", "Name oder Firma. Steht im Impressum.")
            + textField("legalEmail", "E-Mail für Anfragen", "")
            + areaField("legalAddress", "Anschrift", "Mehrzeilig. Wird im Impressum so ausgegeben, wie es hier steht.")
            + '</div></div>';
    }

    function renderMaintenance() {
        return head("maintenance", "Zugang vorübergehend sperren und den Dienst neu starten.")
            + '<div class="card"><h2>Wartungsmodus</h2><div class="card-body">'
            + switchRow("maintenanceEnabled", "Wartungsmodus aktiv",
                "Sperrt das Panel für alle außer der Bot-Verwaltung. Der Bot selbst läuft weiter.",
                Boolean(value("maintenanceEnabled", false)))
            + '<div class="row" style="display:block"><div class="label" style="margin-bottom:10px"><b>Hinweistext</b>'
            + '<span>Wird auf der Startseite angezeigt, solange die Sperre gilt.</span></div>'
            + '<textarea class="textarea" data-bind="maintenanceMessage" rows="3">' + esc(value("maintenanceMessage")) + '</textarea>'
            + '</div>'
            + '</div></div>'
            + '<div class="card"><h2>Dienst</h2><div class="card-body">'
            + '<div class="row"><div class="label"><b>Server neu starten</b>'
            + '<span>Startet die komplette Maschine neu. Die Verbindung bricht dabei ab, der Bot ist rund eine Minute offline.</span></div>'
            + '<div class="control"><button class="btn btn-danger btn-sm" data-action="restart-vm">Neu starten</button></div></div>'
            + '</div></div>';
    }

    function renderBot() {
        var statusOptions = [
            { value: "ONLINE", label: "Online" },
            { value: "IDLE", label: "Abwesend" },
            { value: "DO_NOT_DISTURB", label: "Bitte nicht stören" },
            { value: "INVISIBLE", label: "Unsichtbar" }
        ];

        return head("bot", "Zugangsdaten und Auftreten des Bots auf Discord.")
            + '<div class="notice info">Geheimnisse werden aus Sicherheitsgründen nicht angezeigt. '
            + 'Ein leeres Feld bleibt unverändert — nur was du hier einträgst, wird überschrieben.</div>'
            + '<div class="card"><h2>Zugang</h2><div class="card-body">'
            + textField("token", "Bot-Token", "Aus dem Discord Developer Portal, Reiter „Bot\". Nach dem Speichern startet der Bot neu.",
                { password: true, secret: true, placeholder: "unverändert lassen", mono: true })
            + '</div></div>'
            + '<div class="card"><h2>Auftreten</h2><div class="card-body">'
            + selectField("status", "Status", statusOptions, "")
            + textField("activity", "Aktivität", "Der Text hinter „Spielt\". Leer lassen für keine Aktivität.")
            + areaField("activityRotation", "Wechselnde Aktivitäten",
                "Eine Zeile pro Eintrag. Sind hier Zeilen hinterlegt, wechseln sie sich ab und das Feld darüber wird ignoriert.")
            + '</div></div>';
    }

    function renderLogin() {
        return head("login", "Damit sich Serverbetreiber über Discord an diesem Panel anmelden können.")
            + '<div class="notice info">Die Rücksprung-Adresse muss im Discord Developer Portal unter '
            + '„OAuth2 → Redirects\" <strong>zeichengenau</strong> eingetragen sein, sonst bricht der Login mit '
            + '„invalid redirect_uri\" ab.</div>'
            + '<div class="card"><h2>OAuth2</h2><div class="card-body">'
            + textField("discordClientId", "Client-ID", "", { mono: true })
            + textField("discordClientSecret", "Client-Secret", "Leer lassen, um das gespeicherte Secret zu behalten.",
                { password: true, secret: true, placeholder: "unverändert lassen", mono: true })
            + textField("redirectUri", "Rücksprung-Adresse", "Üblicherweise <code>&lt;Basis-URL&gt;/auth/discord/callback</code>.", { mono: true })
            + '</div></div>';
    }

    function renderLlm() {
        var config = state.config || {};
        var providerOptions = [
            { value: "ollama", label: "Ollama (selbst gehostet)" },
            { value: "openai", label: "OpenAI-kompatibel" }
        ];
        var provider = String(value("llmProvider", "ollama")).toLowerCase();

        var html = head("llm", "Anbieter und Grenzen für den KI-Chat. Ob ein Server die Funktion überhaupt nutzen darf, entscheidest du unter „Server\".");

        html += '<div class="card"><h2>Anbieter</h2><div class="card-body">'
            + selectField("llmProvider", "Anbieter", providerOptions, "")
            + (provider === "openai"
                ? textField("llmOpenAiBaseUrl", "Basis-URL", "Zum Beispiel <code>https://api.openai.com/v1</code>.", { mono: true })
                  + textField("llmApiKey", "API-Schlüssel", "Leer lassen, um den gespeicherten Schlüssel zu behalten.",
                        { password: true, secret: true, placeholder: "unverändert lassen", mono: true })
                : textField("llmOllamaUrl", "Ollama-Adresse", "Zum Beispiel <code>http://127.0.0.1:11434</code>.", { mono: true }))
            + '</div></div>';

        html += '<div class="card"><h2>Modelle</h2><div class="card-body">'
            + textField("llmModel", "Standardmodell", "Wird verwendet, wenn ein Server nichts eigenes wählt.", { mono: true })
            + areaField("llmAvailableModels", "Freigegebene Modelle",
                "Eine Zeile pro Modell. Nur diese stehen den Serverbetreibern zur Auswahl — alles andere wird abgelehnt.",
                { mono: true, rows: 5 })
            + '</div></div>';

        html += '<div class="card"><h2>Grenzen</h2><div class="card-body"><div class="grid3">'
            + numberField("llmTimeoutMs", "Zeitlimit (ms)", "", { min: 1000, step: 500 })
            + numberField("llmMaxTokens", "Maximale Tokens", "", { min: 1 })
            + numberField("llmHistoryTurns", "Gesprächsverlauf", "", { min: 0, max: 50 })
            + '</div>'
            + numberField("llmTemperature", "Temperatur", "Niedrig heißt vorhersehbar, hoch heißt kreativ. 0,7 ist ein guter Ausgangspunkt.",
                { decimal: true, min: 0, max: 2, step: 0.1 })
            + areaField("llmSystemMessage", "Systemnachricht", "Grundanweisung, die jedem Gespräch vorangestellt wird.", { rows: 5 })
            + '</div></div>';

        if (config.llmAvailableModels && config.llmAvailableModels.length) {
            html += '<div class="card"><h2>Derzeit erkannt</h2><div class="card-body">'
                + '<div class="hint">' + config.llmAvailableModels.map(esc).join(" · ") + '</div></div></div>';
        }
        return html;
    }

    function renderDeployments() {
        var list = (changed.deployments || (state.config && state.config.deployments) || []);
        var html = head("deployments", "Mehrere Installationen desselben Bots — etwa Test und Produktion — mit je eigener Adresse und eigenem Port.");

        html += '<div class="card"><h2>Einträge</h2><div class="card-body">';
        if (!list.length) {
            html += '<div class="repeat-empty">Noch kein Deployment hinterlegt.</div>';
        } else {
            html += '<div class="repeat">';
            list.forEach(function (entry, index) {
                html += '<div class="repeat-item" data-list="deployments" data-index="' + index + '">'
                    + '<span class="grip"><svg viewBox="0 0 24 24" width="18" height="18"><use href="#i-grip"/></svg></span>'
                    + '<span class="fields">'
                    + '<input class="input mono" data-field="deploymentKey" placeholder="Schlüssel, z. B. prod" value="' + esc(entry.deploymentKey) + '">'
                    + '<input class="input" data-field="displayName" placeholder="Anzeigename" value="' + esc(entry.displayName) + '">'
                    + '<input class="input mono" data-field="baseUrl" placeholder="Basis-URL" value="' + esc(entry.baseUrl) + '">'
                    + '<input class="input mono" data-field="redirectUri" placeholder="Rücksprung-Adresse" value="' + esc(entry.redirectUri) + '">'
                    + '<input class="input" type="number" data-field="webPort" placeholder="Port" value="' + esc(entry.webPort) + '">'
                    + '<label class="row" style="padding:0;border:0;gap:8px;align-items:center">'
                    + '<span class="switch"><input type="checkbox" data-field="enabled"' + (entry.enabled ? " checked" : "")
                    + '><span class="track"></span><span class="knob"></span></span>'
                    + '<span style="font-size:14px;color:var(--text-muted)">aktiv</span></label>'
                    + '</span>'
                    + '<button class="icon-btn danger" data-remove="deployments" data-index="' + index + '" title="Entfernen">'
                    + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button>'
                    + '</div>';
            });
            html += '</div>';
        }
        html += '<button class="btn btn-secondary btn-sm" style="margin-top:12px" data-add="deployments">'
            + '<svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Deployment hinzufügen</button>'
            + '</div></div>';
        return html;
    }

    function renderLavalink() {
        var list = (changed.lavalinkNodes || (state.config && state.config.lavalinkNodes) || []);
        var html = head("lavalink", "Audio-Knoten. Fällt der erste aus, wird der nächste aktive genommen.");

        html += '<div class="card"><h2>Knoten</h2><div class="card-body">';
        if (!list.length) {
            html += '<div class="repeat-empty">Kein Knoten hinterlegt — ohne Lavalink gibt es keine Musikwiedergabe.</div>';
        } else {
            html += '<div class="repeat">';
            list.forEach(function (entry, index) {
                html += '<div class="repeat-item" data-list="lavalinkNodes" data-index="' + index + '">'
                    + '<span class="grip"><svg viewBox="0 0 24 24" width="18" height="18"><use href="#i-grip"/></svg></span>'
                    + '<span class="fields">'
                    + '<input class="input" data-field="nodeName" placeholder="Name" value="' + esc(entry.nodeName) + '">'
                    + '<input class="input mono" data-field="deploymentKey" placeholder="Deployment-Schlüssel" value="' + esc(entry.deploymentKey) + '">'
                    + '<input class="input mono" data-field="serverUri" placeholder="ws://127.0.0.1:2333" value="' + esc(entry.serverUri) + '">'
                    + '<input class="input mono" type="password" data-field="password" placeholder="Passwort (leer = unverändert)" value="">'
                    + '<input class="input" type="number" data-field="httpTimeoutMs" placeholder="Zeitlimit (ms)" value="' + esc(entry.httpTimeoutMs) + '">'
                    + '<input class="input" type="number" data-field="resumeTimeoutSeconds" placeholder="Wiederaufnahme (s)" value="' + esc(entry.resumeTimeoutSeconds) + '">'
                    + '<label class="row" style="padding:0;border:0;gap:8px;align-items:center">'
                    + '<span class="switch"><input type="checkbox" data-field="enabled"' + (entry.enabled ? " checked" : "")
                    + '><span class="track"></span><span class="knob"></span></span>'
                    + '<span style="font-size:14px;color:var(--text-muted)">aktiv</span></label>'
                    + '<label class="row" style="padding:0;border:0;gap:8px;align-items:center">'
                    + '<span class="switch"><input type="checkbox" data-field="resumeEnabled"' + (entry.resumeEnabled ? " checked" : "")
                    + '><span class="track"></span><span class="knob"></span></span>'
                    + '<span style="font-size:14px;color:var(--text-muted)">Wiederaufnahme</span></label>'
                    + '</span>'
                    + '<button class="icon-btn danger" data-remove="lavalinkNodes" data-index="' + index + '" title="Entfernen">'
                    + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button>'
                    + '</div>';
            });
            html += '</div>';
        }
        html += '<button class="btn btn-secondary btn-sm" style="margin-top:12px" data-add="lavalinkNodes">'
            + '<svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Knoten hinzufügen</button>'
            + '<div class="hint" style="margin-top:12px">Das Passwort wird nie zurückgeliefert. Ein leeres Feld lässt das gespeicherte Passwort unangetastet.</div>'
            + '</div></div>';
        return html;
    }

    function renderGuilds() {
        var guilds = state.guilds || [];
        var canWrite = roleAtLeast("ADMIN");

        var html = head("guilds", "Alle Server, auf denen der Bot ist. Hier entscheidest du, wer die kostspieligen Funktionen nutzen darf.");

        if (!canWrite) {
            html += '<div class="notice">Deine Stufe erlaubt nur das Lesen. Freischaltungen kann nur ein Admin ändern.</div>';
        }

        html += '<div class="field" style="max-width:340px"><input class="input" id="guildFilter" placeholder="Server suchen…" autocomplete="off"></div>';

        if (!guilds.length) {
            html += '<div class="card"><div class="empty">Der Bot ist auf keinem Server.</div></div>';
            return html;
        }

        guilds.forEach(function (guild) {
            var entitlements = guild.entitlements || [];
            html += '<div class="card guild-card" data-name="' + esc(guild.name.toLowerCase()) + '">'
                + '<h2>' + esc(guild.name)
                + '<span class="sub">' + guild.memberCount.toLocaleString("de-DE") + ' Mitglieder · Inhaber '
                + esc(guild.ownerName || guild.ownerId) + ' · dabei seit ' + dateText(guild.joinedAt)
                + (guild.permissionsConfigured ? ' · eigene Rechtematrix' : '')
                + '</span></h2>'
                + '<div class="card-body">';

            entitlements.forEach(function (entry) {
                html += '<div class="row"><div class="label"><b>' + esc(entry.featureLabel) + '</b>'
                    + '<span>' + (entry.enabled
                        ? ('freigeschaltet' + (entry.grantedBy ? ' von ' + esc(entry.grantedBy) : '')
                           + (entry.dailyLimit > 0
                                ? ' · heute ' + entry.usedToday + ' von ' + entry.dailyLimit + ' Aufrufen'
                                : ' · ohne Tageslimit'))
                        : 'gesperrt') + '</span></div>'
                    + '<div class="control">'
                    + '<input class="input" type="number" min="0" style="width:110px" title="Tageslimit, 0 = unbegrenzt"'
                    + ' data-limit="' + esc(guild.id) + '|' + esc(entry.feature) + '" value="' + entry.dailyLimit + '"'
                    + (canWrite ? "" : " disabled") + '>'
                    + '<label class="switch"><input type="checkbox" data-entitlement="' + esc(guild.id) + '|' + esc(entry.feature) + '"'
                    + (entry.enabled ? " checked" : "") + (canWrite ? "" : " disabled")
                    + '><span class="track"></span><span class="knob"></span></label>'
                    + '</div></div>';
            });

            html += '<div class="row"><div class="label"><b>Server-ID</b><span><code>' + esc(guild.id) + '</code></span></div>'
                + '<div class="control">'
                + (canWrite
                    ? '<button class="btn btn-danger btn-sm" data-leave="' + esc(guild.id) + '" data-name="' + esc(guild.name) + '">Bot entfernen</button>'
                    : '')
                + '</div></div>';

            html += '</div></div>';
        });

        return html;
    }

    function renderAdmins() {
        var data = state.admins || { admins: [], assignableRoles: [] };
        var canManage = data.canManageAdmins;

        var html = head("admins", "Wer diese Instanz verwalten darf. Ein Bot-Admin umgeht auf jedem Server sämtliche Rollenprüfungen — auch dort, wo er selbst kein Mitglied ist.");

        if (!canManage) {
            html += '<div class="notice">Nur der Botinhaber darf Admins hinzufügen oder entfernen.</div>';
        }

        html += '<div class="card"><h2>Eingetragen</h2><div class="list">';
        if (!data.admins.length) {
            html += '<div class="empty">Noch niemand eingetragen. Der Botinhaber wird beim ersten Aufruf automatisch ergänzt.</div>';
        }
        data.admins.forEach(function (admin) {
            html += '<div class="list-item">'
                + '<span class="avatar">' + esc((admin.displayName || admin.userId).substring(0, 1).toUpperCase()) + '</span>'
                + '<span class="lt"><b>' + esc(admin.displayName || admin.userId) + '</b>'
                + '<span>' + esc(admin.userId) + ' · seit ' + dateText(admin.createdAt) + '</span></span>'
                + '<span class="pill ' + (admin.role === "OWNER" ? "on" : "") + '">' + esc(admin.role) + '</span>'
                + (canManage && !admin.applicationOwner
                    ? '<button class="icon-btn danger" data-remove-admin="' + esc(admin.userId) + '" title="Entfernen">'
                      + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button>'
                    : '<span style="width:32px"></span>')
                + '</div>';
        });
        html += '</div></div>';

        if (canManage) {
            var roleOptions = data.assignableRoles.map(function (role) {
                return '<option value="' + esc(role.key) + '">' + esc(role.label) + ' — ' + esc(role.description) + '</option>';
            }).join("");

            html += '<div class="card"><h2>Hinzufügen</h2><div class="card-body">'
                + '<div class="grid2">'
                + '<div class="field"><label>Discord-Benutzer-ID</label>'
                + '<input class="input mono" id="newAdminId" placeholder="z. B. 216773450624172032" autocomplete="off">'
                + '<div class="hint">In Discord unter Einstellungen → Erweitert → Entwicklermodus, dann Rechtsklick auf den Nutzer → ID kopieren.</div></div>'
                + '<div class="field"><label>Name (nur zur Anzeige)</label>'
                + '<input class="input" id="newAdminName" placeholder="optional" autocomplete="off"></div>'
                + '</div>'
                + '<div class="field"><label>Stufe</label><select class="select" id="newAdminRole">' + roleOptions + '</select></div>'
                + '<button class="btn btn-primary btn-sm" data-action="add-admin">'
                + '<svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Eintragen</button>'
                + '</div></div>';
        }

        return html;
    }

    function renderAudit() {
        var entries = state.audit || [];
        var html = head("audit", "Was in der Bot-Verwaltung passiert ist — wer, wann, auf welchem Server.");

        html += '<div class="card"><h2>Letzte Einträge</h2><div class="list">';
        if (!entries.length) {
            html += '<div class="empty">Noch nichts protokolliert.</div>';
        }
        entries.forEach(function (entry) {
            html += '<div class="list-item">'
                + '<span class="lt"><b>' + esc(entry.action) + '</b>'
                + '<span>' + esc(entry.actorName || entry.actorUserId) + ' · '
                + esc(entry.targetType || "") + ' ' + esc(entry.targetId || "")
                + (entry.details ? ' · ' + esc(entry.details) : "") + '</span></span>'
                + '<span class="meta">' + dateText(entry.createdAt) + '</span>'
                + '</div>';
        });
        html += '</div></div>';
        return html;
    }

    var RENDERERS = {
        overview: renderOverview, brand: renderBrand, maintenance: renderMaintenance,
        bot: renderBot, login: renderLogin, llm: renderLlm,
        deployments: renderDeployments, lavalink: renderLavalink,
        guilds: renderGuilds, admins: renderAdmins, audit: renderAudit
    };

    function roleAtLeast(required) {
        var order = { SUPPORT: 1, ADMIN: 2, OWNER: 3 };
        var own = state.admins && state.admins.currentRole;
        return Boolean(own) && (order[own] || 0) >= (order[required] || 99);
    }

    // ------------------------------------------------------------------
    // Navigation und Rendern
    // ------------------------------------------------------------------

    function show(name) {
        if (!PAGES[name]) {
            name = "overview";
        }
        state.page = name;

        Array.prototype.forEach.call(document.querySelectorAll(".nav-link"), function (link) {
            link.classList.toggle("is-active", link.dataset.page === name);
        });
        document.getElementById("crumbText").textContent = PAGES[name].title;
        document.getElementById("crumbIcon").querySelector("use").setAttribute("href", PAGES[name].icon);

        page.innerHTML = RENDERERS[name]();
        document.getElementById("scroll").scrollTop = 0;
        document.body.classList.remove("drawer-open");
        document.getElementById("scrim").classList.remove("is-shown");
    }

    function refreshBadges() {
        var guildBadge = document.getElementById("guildCountBadge");
        var adminBadge = document.getElementById("adminCountBadge");
        if (state.guilds) {
            guildBadge.textContent = state.guilds.length;
        }
        if (state.admins) {
            adminBadge.textContent = state.admins.admins.length;
            document.getElementById("ownRole").textContent = "Stufe: " + state.admins.currentRole;
        }
        if (state.runtime) {
            var pill = document.getElementById("botPill");
            pill.className = "status-pill" + (state.runtime.online ? "" : " bad");
            pill.innerHTML = '<span class="dot"></span>' + (state.runtime.online ? "Bot online" : "Bot offline");
            document.getElementById("botDot").className = "dot" + (state.runtime.online ? "" : " off");
        }
    }

    // ------------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------------

    async function save() {
        if (!Object.keys(changed).length) {
            savebar.classList.remove("is-shown");
            return;
        }

        var button = document.getElementById("saveBtn");
        button.disabled = true;
        try {
            await api("POST", "/api/admin/config", changed);
            changed = {};
            listsDirty = false;
            savebar.classList.remove("is-shown");
            toast("Gespeichert.");
            await loadConfig();
            show(state.page);
        } catch (error) {
            toast(error.message, true);
        } finally {
            button.disabled = false;
        }
    }

    function resetChanges() {
        changed = {};
        listsDirty = false;
        savebar.classList.remove("is-shown");
        show(state.page);
    }

    // ------------------------------------------------------------------
    // Laden
    // ------------------------------------------------------------------

    async function loadConfig() {
        state.config = await api("GET", "/api/admin/config");
    }

    async function loadAll() {
        try {
            var results = await Promise.all([
                api("GET", "/api/admin/config"),
                api("GET", "/api/admin/runtime"),
                api("GET", "/api/admin/management/admins"),
                api("GET", "/api/admin/management/guilds"),
                api("GET", "/api/admin/management/audit?limit=100")
            ]);
            state.config = results[0];
            state.runtime = results[1];
            state.admins = results[2];
            state.guilds = results[3];
            state.audit = results[4];
        } catch (error) {
            page.innerHTML = '<div class="notice">Die Verwaltung konnte nicht geladen werden: ' + esc(error.message) + '</div>';
            return;
        }
        refreshBadges();
        show(state.page);
    }

    // ------------------------------------------------------------------
    // Ereignisse
    // ------------------------------------------------------------------

    document.getElementById("navScroll").addEventListener("click", function (event) {
        var link = event.target.closest(".nav-link");
        if (link) {
            show(link.dataset.page);
        }
    });

    document.getElementById("navSearch").addEventListener("input", function (event) {
        var query = event.target.value.trim().toLowerCase();
        Array.prototype.forEach.call(document.querySelectorAll(".nav-group"), function (group) {
            var any = false;
            Array.prototype.forEach.call(group.querySelectorAll(".nav-link"), function (link) {
                var hit = !query || link.textContent.toLowerCase().indexOf(query) !== -1;
                link.hidden = !hit;
                if (hit) {
                    any = true;
                }
            });
            group.hidden = !any;
        });
    });

    // Eingaben der Instanzkonfiguration
    page.addEventListener("input", function (event) {
        var element = event.target;

        if (element.id === "guildFilter") {
            var query = element.value.trim().toLowerCase();
            Array.prototype.forEach.call(document.querySelectorAll(".guild-card"), function (card) {
                card.hidden = Boolean(query) && card.dataset.name.indexOf(query) === -1;
            });
            return;
        }

        if (element.dataset.bind) {
            var kind = element.dataset.kind;
            var raw = element.value;
            if (kind === "int") {
                markChanged(element.dataset.bind, raw === "" ? null : parseInt(raw, 10));
            } else if (kind === "double") {
                markChanged(element.dataset.bind, raw === "" ? null : parseFloat(raw));
            } else {
                markChanged(element.dataset.bind, raw);
            }
            return;
        }

        if (element.dataset.field) {
            collectList(element);
        }
    });

    page.addEventListener("change", function (event) {
        var element = event.target;

        if (element.dataset.bind && element.dataset.kind === "bool") {
            markChanged(element.dataset.bind, element.checked);
            return;
        }
        if (element.dataset.field) {
            collectList(element);
            return;
        }
        if (element.dataset.entitlement) {
            saveEntitlement(element);
            return;
        }
        // Auch eine reine Limit-Änderung muss gespeichert werden, sonst tippt
        // man eine Zahl ein und nichts passiert.
        if (element.dataset.limit) {
            var partner = document.querySelector('[data-entitlement="' + element.dataset.limit + '"]');
            if (partner) {
                saveEntitlement(partner);
            }
        }
    });

    /** Liest eine komplette Wiederholungsliste aus dem DOM zurueck in changed. */
    function collectList(element) {
        var item = element.closest("[data-list]");
        if (!item) {
            return;
        }
        var listName = item.dataset.list;
        var container = item.parentNode;
        var entries = [];

        Array.prototype.forEach.call(container.querySelectorAll('[data-list="' + listName + '"]'), function (node) {
            var entry = {};
            Array.prototype.forEach.call(node.querySelectorAll("[data-field]"), function (field) {
                var name = field.dataset.field;
                if (field.type === "checkbox") {
                    entry[name] = field.checked;
                } else if (field.type === "number") {
                    entry[name] = field.value === "" ? null : Number(field.value);
                } else {
                    entry[name] = field.value;
                }
            });
            // Ein leeres Passwortfeld bedeutet "unveraendert" - null statt "".
            if (Object.prototype.hasOwnProperty.call(entry, "password") && entry.password === "") {
                entry.password = null;
            }
            entries.push(entry);
        });

        listsDirty = true;
        markChanged(listName, entries);
    }

    page.addEventListener("click", async function (event) {
        var target = event.target;

        var add = target.closest("[data-add]");
        if (add) {
            var name = add.dataset.add;
            var list = (changed[name] || (state.config && state.config[name]) || []).slice();
            list.push(name === "deployments"
                ? { deploymentKey: "", displayName: "", baseUrl: "", redirectUri: "", webPort: null, enabled: true, sortOrder: list.length }
                : { nodeName: "", deploymentKey: "", serverUri: "", password: null, httpTimeoutMs: 10000, resumeEnabled: true, resumeTimeoutSeconds: 60, enabled: true });
            markChanged(name, list);
            show(state.page);
            return;
        }

        var remove = target.closest("[data-remove]");
        if (remove) {
            var listName = remove.dataset.remove;
            var index = Number(remove.dataset.index);
            var current = (changed[listName] || (state.config && state.config[listName]) || []).slice();
            current.splice(index, 1);
            markChanged(listName, current);
            show(state.page);
            return;
        }

        var action = target.closest("[data-action]");
        if (action) {
            await runAction(action.dataset.action, action);
            return;
        }

        var leave = target.closest("[data-leave]");
        if (leave) {
            if (!window.confirm('Soll der Bot den Server „' + leave.dataset.name + '" wirklich verlassen?')) {
                return;
            }
            try {
                var result = await api("POST", "/api/admin/management/guilds/" + leave.dataset.leave + "/leave");
                toast(result.message);
                state.guilds = await api("GET", "/api/admin/management/guilds");
                refreshBadges();
                show(state.page);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        var removeAdmin = target.closest("[data-remove-admin]");
        if (removeAdmin) {
            if (!window.confirm("Diesen Eintrag wirklich entfernen?")) {
                return;
            }
            try {
                await api("DELETE", "/api/admin/management/admins/" + removeAdmin.dataset.removeAdmin);
                toast("Entfernt.");
                await reloadAdmins();
            } catch (error) {
                toast(error.message, true);
            }
        }
    });

    async function runAction(name, element) {
        if (name === "restart-vm") {
            if (!window.confirm("Die Maschine wird neu gestartet. Fortfahren?")) {
                return;
            }
            try {
                var result = await api("POST", "/api/admin/actions/restart-vm");
                toast(result.message);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        if (name === "add-admin") {
            var userId = document.getElementById("newAdminId").value.trim();
            var displayName = document.getElementById("newAdminName").value.trim();
            var role = document.getElementById("newAdminRole").value;
            element.disabled = true;
            try {
                await api("POST", "/api/admin/management/admins", { userId: userId, role: role, displayName: displayName });
                toast("Eingetragen.");
                await reloadAdmins();
            } catch (error) {
                toast(error.message, true);
            } finally {
                element.disabled = false;
            }
        }
    }

    async function reloadAdmins() {
        state.admins = await api("GET", "/api/admin/management/admins");
        refreshBadges();
        show(state.page);
    }

    async function saveEntitlement(element) {
        var parts = element.dataset.entitlement.split("|");
        var guildId = parts[0];
        var feature = parts[1];
        var limitInput = document.querySelector('[data-limit="' + guildId + "|" + feature + '"]');
        var dailyLimit = limitInput ? Math.max(0, parseInt(limitInput.value, 10) || 0) : 0;

        element.disabled = true;
        try {
            var result = await api("POST", "/api/admin/management/guilds/" + guildId + "/entitlements", {
                feature: feature,
                enabled: element.checked,
                dailyLimit: dailyLimit,
                note: ""
            });
            toast(result.message);
            state.guilds = await api("GET", "/api/admin/management/guilds");
            state.audit = await api("GET", "/api/admin/management/audit?limit=100");
            show(state.page);
        } catch (error) {
            element.checked = !element.checked;
            toast(error.message, true);
        } finally {
            element.disabled = false;
        }
    }

    document.getElementById("saveBtn").addEventListener("click", save);
    document.getElementById("resetBtn").addEventListener("click", resetChanges);

    // Design
    var themeBtn = document.getElementById("themeBtn");
    function applyTheme(next) {
        document.documentElement.setAttribute("data-theme", next);
        themeBtn.querySelector("use").setAttribute("href", next === "dark" ? "#i-sun" : "#i-moon");
        try {
            window.localStorage.setItem("panel-theme", next);
        } catch (ignored) {
            // Privater Modus: dann gilt die Wahl eben nur fuer diese Sitzung.
        }
    }
    themeBtn.addEventListener("click", function () {
        applyTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark");
    });
    (function initTheme() {
        var stored = null;
        try {
            stored = window.localStorage.getItem("panel-theme");
        } catch (ignored) {
            stored = null;
        }
        if (!stored) {
            stored = window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
        }
        applyTheme(stored);
    })();

    // Mobile Navigation
    var scrim = document.getElementById("scrim");
    document.getElementById("burger").addEventListener("click", function () {
        document.body.classList.add("drawer-open");
        scrim.classList.add("is-shown");
    });
    scrim.addEventListener("click", function () {
        document.body.classList.remove("drawer-open");
        scrim.classList.remove("is-shown");
    });

    window.addEventListener("beforeunload", function (event) {
        if (Object.keys(changed).length) {
            event.preventDefault();
            event.returnValue = "";
        }
    });

    loadAll();
})();
