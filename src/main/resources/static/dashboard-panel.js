/*
 * Serverpanel.
 *
 * Ablösung der alten dashboard.js (118 KB). Drei Dinge sind bewusst anders:
 *
 *  1. Es wird immer nur die aktive Seite gerendert, nicht das gesamte Panel.
 *  2. Der Player wird nur abgefragt, wenn eine Musikseite offen ist UND der
 *     Tab sichtbar ist. Vorher lief die Abfrage dauerhaft und zeichnete jedes
 *     Mal alles neu — das war der eigentliche Grund für das ruckelnde Scrollen,
 *     nicht das Stylesheet.
 *  3. Die Fortschrittsanzeige wird über transform bewegt, nicht über width.
 *     Eine Breitenänderung löst in Firefox ein Layout aus, ein transform nicht.
 *
 * Bearbeitet wird immer auf einer Arbeitskopie ("draft") des jeweiligen Moduls.
 * Erst Speichern schickt sie an den Server; Zurücksetzen wirft sie weg.
 */
(function () {
    "use strict";

    // ------------------------------------------------------------------
    // Zustand
    // ------------------------------------------------------------------

    var state = {
        guilds: (window.PANEL_BOOT && window.PANEL_BOOT.guilds) || [],
        adminAllowed: Boolean(window.PANEL_BOOT && window.PANEL_BOOT.adminAllowed),
        guildId: null,
        config: null,
        player: null,
        stations: [],
        voiceChannels: [],
        permissions: null,
        page: "overview"
    };

    var draft = null;        // Arbeitskopie des gerade bearbeiteten Moduls
    var draftModule = null;  // Name des Moduls, zu dem die Arbeitskopie gehört
    var pollTimer = null;

    var PAGES = {
        overview:      { icon: "#i-home",   title: "Übersicht",      live: true },
        permissions:   { icon: "#i-shield", title: "Rollenrechte" },
        player:        { icon: "#i-music",  title: "Player",         live: true },
        queue:         { icon: "#i-queue",  title: "Warteschlange",  live: true },
        radio:         { icon: "#i-radio",  title: "Webradio",       live: true },
        welcome:       { icon: "#i-welcome",title: "Willkommen" },
        verify:        { icon: "#i-verify", title: "Verifizierung" },
        reactionroles: { icon: "#i-roles",  title: "Reaction-Roles" },
        invites:       { icon: "#i-invite", title: "Invite-Tracking" },
        tickets:       { icon: "#i-ticket", title: "Tickets" },
        logs:          { icon: "#i-logs",   title: "Discord-Logs" },
        jtc:           { icon: "#i-voice",  title: "Join-to-Create" },
        llm:           { icon: "#i-ai",     title: "KI-Chat" },
        commands:      { icon: "#i-cmd",    title: "Commands" }
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

    function clone(value) {
        return value === null || value === undefined ? value : JSON.parse(JSON.stringify(value));
    }

    function toast(message, bad) {
        var box = document.getElementById("toast");
        var item = document.createElement("div");
        if (bad) {
            item.className = "bad";
        }
        item.textContent = message;
        box.appendChild(item);
        window.setTimeout(function () { item.remove(); }, bad ? 8000 : 4000);
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
            try { data = JSON.parse(text); } catch (ignored) { data = null; }
        }
        if (!response.ok) {
            var message = (data && (data.message || data.error)) || ("HTTP " + response.status);
            if (response.status === 401) {
                message = "Die Sitzung ist abgelaufen. Bitte neu anmelden.";
            }
            throw new Error(message);
        }
        return data;
    }

    function duration(ms, stream) {
        if (stream) {
            return "LIVE";
        }
        var total = Math.max(0, Math.floor((ms || 0) / 1000));
        var hours = Math.floor(total / 3600);
        var minutes = Math.floor((total % 3600) / 60);
        var seconds = total % 60;
        var pad = function (n) { return n < 10 ? "0" + n : String(n); };
        return hours > 0 ? hours + ":" + pad(minutes) + ":" + pad(seconds) : minutes + ":" + pad(seconds);
    }

    function dateText(iso) {
        if (!iso) { return "—"; }
        var parsed = new Date(iso);
        if (isNaN(parsed.getTime())) { return iso; }
        return parsed.toLocaleString("de-DE", {
            day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
        });
    }

    /** Setzt einen Wert über einen Punktpfad, z. B. "panels.0.title". */
    function setPath(target, path, value) {
        var parts = path.split(".");
        var node = target;
        for (var i = 0; i < parts.length - 1; i++) {
            node = node[parts[i]];
            if (node === undefined || node === null) { return; }
        }
        node[parts[parts.length - 1]] = value;
    }

    function getPath(target, path) {
        var parts = path.split(".");
        var node = target;
        for (var i = 0; i < parts.length; i++) {
            if (node === undefined || node === null) { return undefined; }
            node = node[parts[i]];
        }
        return node;
    }

    function beginDraft(moduleName) {
        if (draftModule !== moduleName) {
            draft = clone(state.config ? state.config[moduleName] : null);
            draftModule = moduleName;
            savebar.classList.remove("is-shown");
        }
        return draft;
    }

    function markDirty() {
        savebar.classList.add("is-shown");
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

    function masterToggle(path, checked) {
        return '<span class="lbl">Modul aktiv</span>'
            + '<label class="switch"><input type="checkbox" data-p="' + path + '" data-kind="bool"'
            + (checked ? " checked" : "") + '><span class="track"></span><span class="knob"></span></label>';
    }

    function textField(path, label, hint, opts) {
        var options = opts || {};
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<input class="input' + (options.mono ? " mono" : "") + '" data-p="' + path + '"'
            + ' value="' + esc(getPath(draft, path)) + '" placeholder="' + esc(options.placeholder || "") + '" autocomplete="off">'
            + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
    }

    function areaField(path, label, hint, rows) {
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<textarea class="textarea" data-p="' + path + '" rows="' + (rows || 4) + '">'
            + esc(getPath(draft, path)) + '</textarea>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
    }

    function numberField(path, label, hint, min, max) {
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<input class="input" type="number" data-p="' + path + '" data-kind="int" value="'
            + esc(getPath(draft, path)) + '"'
            + (min !== undefined ? ' min="' + min + '"' : "")
            + (max !== undefined ? ' max="' + max + '"' : "") + '>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
    }

    function colorField(path, label) {
        var current = getPath(draft, path) || "#5865f2";
        return '<div class="field"><label>' + esc(label) + '</label><div class="input-group" style="max-width:240px">'
            + '<input type="color" class="input" data-p="' + path + '" data-kind="color" value="' + esc(current) + '">'
            + '<input class="input mono" data-p="' + path + '" value="' + esc(current) + '">'
            + '</div></div>';
    }

    function channelPicker(path, label, hint, list, emptyLabel) {
        var current = String(getPath(draft, path) || "");
        var options = '<option value="">' + esc(emptyLabel || "— keiner —") + '</option>';
        (list || []).forEach(function (item) {
            options += '<option value="' + esc(item.id) + '"' + (item.id === current ? " selected" : "") + '>'
                + esc(item.name) + '</option>';
        });
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<div class="picker"><span class="glyph"><svg viewBox="0 0 24 24" width="18" height="18"><use href="#i-hash"/></svg></span>'
            + '<select data-p="' + path + '">' + options + '</select></div>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
    }

    function selectField(path, label, choices, hint) {
        var current = String(getPath(draft, path) || "");
        var options = "";
        choices.forEach(function (choice) {
            options += '<option value="' + esc(choice.value) + '"'
                + (choice.value === current ? " selected" : "") + '>' + esc(choice.label) + '</option>';
        });
        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<select class="select" data-p="' + path + '">' + options + '</select>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
    }

    function switchRow(path, title, description) {
        return '<div class="row"><div class="label"><b>' + esc(title) + '</b><span>' + description + '</span></div>'
            + '<div class="control"><label class="switch"><input type="checkbox" data-p="' + path + '" data-kind="bool"'
            + (getPath(draft, path) ? " checked" : "") + '><span class="track"></span><span class="knob"></span></label></div></div>';
    }

    // ---- Mehrfachauswahl für Rollen ------------------------------------

    function roleName(roleId) {
        var roles = (state.config && state.config.roles) || [];
        for (var i = 0; i < roles.length; i++) {
            if (roles[i].id === roleId) { return roles[i].name; }
        }
        return roleId;
    }

    function roleMulti(path, label, hint) {
        var selected = getPath(draft, path) || [];
        var chips = "";
        selected.forEach(function (roleId) {
            chips += '<span class="multi-chip"><span class="role-dot" style="background:#5865f2"></span>'
                + '<span class="nm">@' + esc(roleName(roleId)) + '</span>'
                + '<button type="button" data-role-remove="' + path + '|' + esc(roleId) + '">&times;</button></span>';
        });
        var available = ((state.config && state.config.roles) || []).filter(function (role) {
            return selected.indexOf(role.id) === -1;
        });
        var options = '<option value="">+ Rolle hinzufügen…</option>';
        available.forEach(function (role) {
            options += '<option value="' + esc(role.id) + '">@' + esc(role.name) + '</option>';
        });

        return '<div class="field"><label>' + esc(label) + '</label>'
            + '<div class="multi"><div class="multi-box">' + chips
            + '<select class="multi-add" data-role-add="' + path + '" style="min-width:170px">' + options + '</select>'
            + '</div></div>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
    }

    // ------------------------------------------------------------------
    // Seiten: Musik
    // ------------------------------------------------------------------

    function playerCard() {
        var player = state.player;
        if (!player) {
            return '<div class="card"><div class="empty"><span class="spinner"></span></div></div>';
        }

        var track = player.currentTrack;
        var total = track ? track.durationMs : 0;
        var percent = total > 0 && !player.playingRadio ? Math.min(1, player.positionMs / total) : 0;

        var art = track && track.artworkUrl
            ? '<img src="' + esc(track.artworkUrl) + '" alt="">'
            : '<svg viewBox="0 0 24 24" width="40" height="40"><use href="#i-music"/></svg>';

        return '<div class="card">'
            + '<div class="player">'
            + '<div class="art">' + art + '</div>'
            + '<div class="meta">'
            + '<span class="kicker">' + (player.playingRadio
                ? "Webradio · " + esc(player.activeRadioName || "")
                : (player.paused ? "Pausiert" : "Wiedergabe"))
            + (player.voiceChannelName ? " · " + esc(player.voiceChannelName) : "") + '</span>'
            + '<div class="title" id="pTitle">' + esc(track ? track.title : "Nichts ausgewählt") + '</div>'
            + '<div class="artist" id="pArtist">' + esc(track ? (track.author || "") : "") + '</div>'
            + '<div class="progress"><div class="bar"><div class="fill" id="pFill" style="transform:scaleX('
            + percent.toFixed(4) + ')"></div></div>'
            + '<div class="times"><span id="pPos">' + duration(player.positionMs, false) + '</span>'
            + '<span id="pDur">' + duration(total, track && track.stream) + '</span></div></div>'
            + '</div></div>'
            + '<div class="transport">'
            + (player.paused
                ? '<button class="tbtn play" data-player="resume" title="Fortsetzen"><svg viewBox="0 0 24 24"><use href="#i-play"/></svg></button>'
                : '<button class="tbtn play" data-player="pause" title="Pause"><svg viewBox="0 0 24 24"><use href="#i-pause"/></svg></button>')
            + '<button class="tbtn" data-player="skip" title="Weiter"><svg viewBox="0 0 24 24"><use href="#i-skip"/></svg></button>'
            + '<button class="tbtn" data-player="stop" title="Stopp"><svg viewBox="0 0 24 24"><use href="#i-stop"/></svg></button>'
            + '<button class="tbtn' + (player.repeatEnabled ? " is-on" : "") + '" data-toggle="repeat" title="Wiederholen">'
            + '<svg viewBox="0 0 24 24"><use href="#i-repeat"/></svg></button>'
            + '<button class="tbtn' + (player.bassBoostEnabled ? " is-on" : "") + '" data-toggle="bass" title="Bass-Boost">'
            + '<svg viewBox="0 0 24 24"><use href="#i-bass"/></svg></button>'
            + '<div class="vol"><svg viewBox="0 0 24 24"><use href="#i-vol"/></svg>'
            + '<input type="range" min="0" max="150" value="' + player.volume + '" id="volRange">'
            + '<span class="val" id="volValue">' + player.volume + ' %</span></div>'
            + '</div></div>';
    }

    function voiceChannelOptions() {
        var player = state.player || {};
        var preferred = player.userVoiceChannelId ? String(player.userVoiceChannelId) : "";
        var options = '<option value="">' + (preferred ? "dein Kanal" : "— Kanal wählen —") + '</option>';
        state.voiceChannels.forEach(function (channel) {
            options += '<option value="' + esc(channel.id) + '">' + esc(channel.name)
                + (channel.memberCount ? " (" + channel.memberCount + ")" : "") + '</option>';
        });
        return options;
    }

    function renderPlayer() {
        var player = state.player || {};
        var extra = player.connected
            ? '<span class="status-pill"><span class="dot"></span>verbunden</span>'
            : '<span class="status-pill bad"><span class="dot"></span>nicht verbunden</span>';

        var html = head("player", "Wiedergabe und Klang für diesen Server.", extra);
        html += playerCard();

        html += '<div class="card"><h2>Titel hinzufügen</h2><div class="card-body">'
            + '<div class="input-group"><input class="input" id="playQuery" placeholder="Suchbegriff oder URL (YouTube, SoundCloud, Direktlink)">'
            + '<select class="select" id="playChannel" style="max-width:220px">' + voiceChannelOptions() + '</select>'
            + '<button class="btn btn-primary" data-action="play">Abspielen</button></div>'
            + '<div class="hint">Liefert YouTube nichts, wird automatisch über SoundCloud gesucht.</div>'
            + '</div></div>';

        if (player.waitingForListeners) {
            html = html.replace('<div class="card">',
                '<div class="notice">Der Bot wartet auf Zuhörer im Sprachkanal, bevor er weiterspielt.</div><div class="card">');
        }
        return html;
    }

    function renderQueue() {
        var player = state.player || {};
        var queue = player.queue || [];
        var totalMs = queue.reduce(function (sum, track) { return sum + (track.stream ? 0 : track.durationMs || 0); }, 0);

        var html = head("queue", queue.length
            ? queue.length + " Titel · etwa " + Math.round(totalMs / 60000) + " Minuten."
            : "Die Warteschlange ist leer.",
            queue.length ? '<button class="btn btn-secondary btn-sm" data-player="stop">Leeren &amp; stoppen</button>' : "");

        if (!queue.length) {
            html += '<div class="card"><div class="empty">Nichts in der Warteschlange. Titel fügst du unter „Player" hinzu.</div></div>';
            return html;
        }

        html += '<div class="card"><div class="list">';
        queue.forEach(function (track, index) {
            html += '<div class="list-item">'
                + '<span class="meta" style="width:24px;text-align:right">' + (index + 1) + '</span>'
                + '<span class="lt"><b>' + esc(track.title) + '</b><span>' + esc(track.author || "") + '</span></span>'
                + '<span class="meta">' + duration(track.durationMs, track.stream) + '</span>'
                + (index > 0
                    ? '<button class="icon-btn" data-queue-up="' + index + '" title="Nach oben">'
                      + '<svg viewBox="0 0 24 24" style="transform:rotate(-90deg)"><use href="#i-skip"/></svg></button>'
                    : '<span style="width:32px"></span>')
                + '<button class="icon-btn danger" data-queue-remove="' + index + '" title="Entfernen">'
                + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button>'
                + '</div>';
        });
        html += '</div></div>';
        return html;
    }

    function renderRadio() {
        var player = state.player || {};
        var html = head("radio", "Sender aus der Datenbank. Die Auswahl gilt für Panel und Slash-Commands gleichermaßen.");

        if (player.radioCooldownRemainingMs > 0) {
            html += '<div class="notice">Senderwechsel gesperrt für noch '
                + Math.ceil(player.radioCooldownRemainingMs / 1000) + ' Sekunden.</div>';
        }

        html += '<div class="card"><h2>Sender</h2><div class="card-body">'
            + '<div class="field"><label>Sprachkanal</label>'
            + '<select class="select" id="radioChannel" style="max-width:280px">' + voiceChannelOptions() + '</select></div>'
            + '<div class="chips">';
        if (!state.stations.length) {
            html += '<div class="repeat-empty" style="width:100%">Keine Sender hinterlegt. Die Liste pflegt der Bot-Betreiber.</div>';
        }
        state.stations.forEach(function (station) {
            var active = player.playingRadio && player.activeRadioName === station.name;
            html += '<button class="chip' + (active ? " is-on" : "") + '" data-radio="' + station.id + '">📻 '
                + esc(station.name) + '</button>';
        });
        html += '</div></div></div>';
        return html;
    }

    // ------------------------------------------------------------------
    // Seiten: Übersicht und Rechte
    // ------------------------------------------------------------------

    function renderOverview() {
        var config = state.config || {};
        var player = state.player || {};
        var modules = [
            ["welcome", "Willkommen", "#i-welcome", config.welcome && config.welcome.enabled],
            ["verify", "Verifizierung", "#i-verify", config.verify && config.verify.enabled],
            ["reactionroles", "Reaction-Roles", "#i-roles", config.reactionRoles && config.reactionRoles.enabled],
            ["invites", "Invite-Tracking", "#i-invite", config.inviteTracker && config.inviteTracker.enabled],
            ["tickets", "Tickets", "#i-ticket", config.tickets && config.tickets.enabled],
            ["logs", "Discord-Logs", "#i-logs", config.discordLogs && config.discordLogs.enabled],
            ["jtc", "Join-to-Create", "#i-voice", config.joinToCreate && config.joinToCreate.enabled],
            ["llm", "KI-Chat", "#i-ai", config.llm && config.llm.enabled]
        ];

        var guild = currentGuild() || {};
        var activeCommands = (config.commands || []).filter(function (c) { return c.enabled; }).length;

        var html = head("overview", "Was gerade läuft und welche Module auf diesem Server aktiv sind.");

        html += '<div class="stats">'
            + '<div class="stat"><div class="k">Mitglieder</div><div class="v">'
            + (guild.memberCount || 0).toLocaleString("de-DE") + '</div><div class="s">'
            + esc(guild.name || "") + '</div></div>'
            + '<div class="stat"><div class="k">Warteschlange</div><div class="v">'
            + ((player.queue && player.queue.length) || 0) + '</div><div class="s">'
            + (player.connected ? "Bot ist verbunden" : "Bot ist nicht verbunden") + '</div></div>'
            + '<div class="stat"><div class="k">Offene Tickets</div><div class="v">'
            + ((config.tickets && config.tickets.activeTicketCount) || 0) + '</div><div class="s">'
            + ((config.tickets && config.tickets.panels && config.tickets.panels.length) || 0) + ' Panels</div></div>'
            + '<div class="stat"><div class="k">Commands</div><div class="v">' + activeCommands + '</div>'
            + '<div class="s">von ' + ((config.commands || []).length) + ' aktiv</div></div>'
            + '</div>';

        html += '<div class="card"><h2>Läuft gerade</h2>' + playerCard().replace('<div class="card">', "").replace(/<\/div>$/, "") + '</div>';

        html += '<h2 style="font-size:12px;font-weight:700;letter-spacing:.02em;text-transform:uppercase;'
            + 'color:var(--text-muted);margin:24px 0 10px">Module</h2><div class="tiles">';
        modules.forEach(function (entry) {
            html += '<button class="tile" data-goto="' + entry[0] + '">'
                + '<span class="glyph"><svg viewBox="0 0 24 24"><use href="' + entry[2] + '"/></svg></span>'
                + '<span class="tt"><b>' + esc(entry[1]) + '</b></span>'
                + '<span class="pill ' + (entry[3] ? "on" : "") + '">' + (entry[3] ? "an" : "aus") + '</span></button>';
        });
        html += '</div>';
        return html;
    }

    function renderPermissions() {
        var data = state.permissions;
        if (!data) {
            return head("permissions", "Wird geladen…") + '<div class="empty"><span class="spinner"></span></div>';
        }

        var html = head("permissions", "Welche Discord-Rolle darf im Panel und bei den Commands was. Bot-Admins umgehen diese Matrix grundsätzlich.");

        if (!data.configured) {
            html += '<div class="notice">Für diesen Server ist noch nichts gepflegt. Solange das so bleibt, gilt die alte Regel: '
                + 'wer auf Discord „Server verwalten" oder Administrator hat, darf alles. Sobald du hier speicherst, zählt nur noch die Matrix.</div>';
        }

        var canManage = data.ownPermissions.indexOf("PERMISSION_MANAGE") !== -1;
        if (!canManage) {
            html += '<div class="notice">Dir fehlt das Recht „Rollenrechte verwalten" — du kannst die Matrix ansehen, aber nicht ändern.</div>';
        }

        html += '<div class="card"><div class="matrix-wrap"><table class="matrix"><thead><tr><th class="role">Rolle</th>';
        data.permissions.forEach(function (permission) {
            html += '<th title="' + esc(permission.description) + '">' + esc(permission.label) + '</th>';
        });
        html += '</tr></thead><tbody>';

        data.roles.slice().sort(function (a, b) { return b.position - a.position; }).forEach(function (role) {
            var granted = data.matrix[role.id] || [];
            html += '<tr><td class="role"><span class="rolecell">'
                + '<span class="role-dot" style="background:' + esc(role.color || "#949ba4") + '"></span>'
                + esc(role.everyone ? "@everyone" : "@" + role.name)
                + (role.managed ? ' <span class="pill">Integration</span>' : "")
                + '</span></td>';
            data.permissions.forEach(function (permission) {
                html += '<td><label class="switch"><input type="checkbox" data-perm="' + esc(role.id) + '|' + esc(permission.key) + '"'
                    + (granted.indexOf(permission.key) !== -1 ? " checked" : "")
                    + (canManage ? "" : " disabled")
                    + '><span class="track"></span><span class="knob"></span></label></td>';
            });
            html += '</tr>';
        });
        html += '</tbody></table></div></div>';

        if (canManage) {
            html += '<div class="card"><div class="card-body">'
                + '<div class="row"><div class="label"><b>Matrix speichern</b>'
                + '<span>Mindestens eine Rolle muss „Rollenrechte verwalten" behalten — sonst kommt hier niemand mehr heran.</span></div>'
                + '<div class="control"><button class="btn btn-success btn-sm" data-action="save-permissions">Speichern</button></div></div>'
                + '</div></div>';
        }
        return html;
    }

    // ------------------------------------------------------------------
    // Seiten: Module
    // ------------------------------------------------------------------

    function renderWelcome() {
        beginDraft("welcome");
        if (!draft) { return head("welcome", "Nicht verfügbar."); }

        var html = head("welcome", "Begrüßt neue Mitglieder und vergibt optional Rollen.",
            masterToggle("enabled", draft.enabled));
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>'
            + '<div class="card"><h2>Nachricht</h2><div class="card-body">'
            + channelPicker("channelId", "Kanal", "", state.config.textChannels)
            + areaField("welcomeText", "Text",
                "Platzhalter: <code>{user}</code>, <code>{server}</code>, <code>{count}</code>", 4)
            + colorField("accentColor", "Akzentfarbe")
            + '</div></div>'
            + '<div class="card"><h2>Bild</h2><div class="card-body">'
            + switchRow("sendImage", "Willkommensbild erzeugen", "Avatar und Name werden auf ein Hintergrundbild gerendert.")
            + '<div class="row" style="display:block"><div class="label" style="margin-bottom:10px"><b>Hintergrundbild</b>'
            + '<span>URL zu einem Bild, empfohlen 1024×360.</span></div>'
            + '<input class="input mono" data-p="backgroundImageUrl" value="' + esc(draft.backgroundImageUrl) + '" placeholder="https://…"></div>'
            + '</div></div>'
            + '<div class="card"><h2>Autorollen</h2><div class="card-body">'
            + roleMulti("roleIds", "Rollen bei Beitritt",
                "Mehrere möglich. Der Bot braucht eine höhere Rolle als jede vergebene.")
            + '</div></div>'
            + '</div>';
        return html;
    }

    function renderVerify() {
        beginDraft("verify");
        if (!draft) { return head("verify", "Nicht verfügbar."); }

        var html = head("verify", "Mitglieder schalten sich per Knopfdruck selbst frei.",
            masterToggle("enabled", draft.enabled));
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>'
            + '<div class="card"><h2>Panel</h2><div class="card-body">'
            + channelPicker("publishChannelId", "Kanal", "", state.config.textChannels)
            + textField("title", "Titel", "")
            + areaField("description", "Beschreibung", "", 3)
            + colorField("accentColor", "Akzentfarbe")
            + textField("imageUrl", "Bild-URL", "", { mono: true, placeholder: "https://…" })
            + textField("thumbnailUrl", "Thumbnail-URL", "", { mono: true, placeholder: "https://…" })
            + '</div></div>'
            + '<div class="card"><h2>Rollen</h2><div class="card-body">'
            + roleMulti("verifiedRoleIds", "Rollen nach Verifizierung", "Mehrere möglich — alle werden gleichzeitig vergeben.")
            + '</div></div>'
            + '</div>';
        return html;
    }

    function renderReactionRoles() {
        beginDraft("reactionRoles");
        if (!draft) { return head("reactionroles", "Nicht verfügbar."); }
        if (!draft.panels) { draft.panels = []; }

        var html = head("reactionroles", "Rollen zur Selbstvergabe über Reaktionen an einer Panel-Nachricht.",
            masterToggle("enabled", draft.enabled));
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>';

        draft.panels.forEach(function (panel, panelIndex) {
            var base = "panels." + panelIndex + ".";
            html += '<div class="card"><h2>Panel ' + (panelIndex + 1)
                + '<span class="sub">' + (panel.messageId ? "veröffentlicht" : "noch nicht veröffentlicht") + '</span></h2>'
                + '<div class="card-body">'
                + channelPicker(base + "publishChannelId", "Kanal", "", state.config.textChannels)
                + textField(base + "title", "Titel", "")
                + areaField(base + "description", "Beschreibung", "", 3)
                + colorField(base + "accentColor", "Akzentfarbe")
                + '<div class="field"><label>Zuordnungen</label><div class="repeat">';

            (panel.entries || []).forEach(function (entry, entryIndex) {
                var entryBase = base + "entries." + entryIndex + ".";
                html += '<div class="repeat-item">'
                    + '<span class="fields">'
                    + '<input class="input" data-p="' + entryBase + 'emoji" value="' + esc(entry.emoji) + '" placeholder="Emoji">'
                    + '<input class="input" data-p="' + entryBase + 'label" value="' + esc(entry.label) + '" placeholder="Beschriftung">'
                    + '<div style="grid-column:1/-1">' + roleMulti(entryBase + "roleIds", "Rollen", "") + '</div>'
                    + '</span>'
                    + '<button class="icon-btn danger" data-remove="panels.' + panelIndex + '.entries|' + entryIndex + '" title="Entfernen">'
                    + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button>'
                    + '</div>';
            });

            html += '</div><button class="btn btn-secondary btn-sm" style="margin-top:12px" data-add="panels.'
                + panelIndex + '.entries|rrEntry"><svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Zuordnung hinzufügen</button>'
                + '</div>'
                + '<div class="row"><div class="label"><b>Panel entfernen</b><span>Die veröffentlichte Nachricht bleibt bestehen.</span></div>'
                + '<div class="control"><button class="btn btn-danger btn-sm" data-remove="panels|' + panelIndex + '">Entfernen</button></div></div>'
                + '</div></div>';
        });

        if (!draft.panels.length) {
            html += '<div class="card"><div class="card-body"><div class="repeat-empty">Noch kein Panel angelegt.</div></div></div>';
        }

        html += '<button class="btn btn-secondary btn-sm" data-add="panels|rrPanel">'
            + '<svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Panel hinzufügen</button></div>';
        return html;
    }

    function renderInvites() {
        beginDraft("inviteTracker");
        if (!draft) { return head("invites", "Nicht verfügbar."); }

        var html = head("invites", "Zeigt, über welche Einladung ein Mitglied gekommen ist.",
            masterToggle("enabled", draft.enabled));
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }
        if (!draft.canReadInvites) {
            html += '<div class="notice">Dem Bot fehlt das Recht „Einladungen verwalten" auf diesem Server — ohne das kann er nichts zuordnen.</div>';
        }

        html += '<div class="card"><h2>Aktive Einladungen</h2><div class="list">';
        if (!(draft.activeInvites || []).length) {
            html += '<div class="empty">Keine aktiven Einladungen.</div>';
        }
        (draft.activeInvites || []).forEach(function (invite) {
            html += '<div class="list-item"><span class="lt"><b>discord.gg/' + esc(invite.code) + '</b>'
                + '<span>' + esc(invite.inviter || "unbekannt") + (invite.temporary ? " · temporär" : "") + '</span></span>'
                + '<span class="meta">' + (invite.uses === null ? "—" : invite.uses + " Beitritte") + '</span></div>';
        });
        html += '</div></div>';

        html += '<div class="card"><h2>Letzte Beitritte</h2><div class="list">';
        if (!(draft.recentJoins || []).length) {
            html += '<div class="empty">Noch nichts erfasst.</div>';
        }
        (draft.recentJoins || []).forEach(function (event) {
            html += '<div class="list-item"><span class="lt"><b>' + esc(event.memberDisplay) + '</b>'
                + '<span>über ' + esc(event.inviteCode || "unbekannt")
                + (event.inviterDisplay ? " von " + esc(event.inviterDisplay) : "") + '</span></span>'
                + '<span class="meta">' + dateText(event.joinedAt) + '</span></div>';
        });
        html += '</div></div>';
        return html;
    }

    function renderTickets() {
        beginDraft("tickets");
        if (!draft) { return head("tickets", "Nicht verfügbar."); }
        if (!draft.panels) { draft.panels = []; }

        var modes = [
            { value: "BUTTON", label: "Knopf" },
            { value: "SELECT", label: "Auswahlmenü" }
        ];

        var html = head("tickets", "Support-Anfragen als private Kanäle, mit Protokoll beim Schließen.",
            masterToggle("enabled", draft.enabled));
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>'
            + '<div class="card"><h2>Protokolle</h2><div class="card-body">'
            + channelPicker("transcriptChannelId", "Protokoll-Kanal",
                "Beim Schließen wird der komplette Verlauf dort abgelegt.", state.config.textChannels)
            + '</div></div>';

        draft.panels.forEach(function (panel, panelIndex) {
            var base = "panels." + panelIndex + ".";
            html += '<div class="card"><h2>' + esc(panel.title || "Panel " + (panelIndex + 1))
                + '<span class="sub">' + (panel.messageId ? "veröffentlicht" : "noch nicht veröffentlicht") + '</span></h2>'
                + '<div class="card-body">'
                + textField(base + "title", "Titel", "")
                + areaField(base + "description", "Beschreibung", "", 3)
                + '<div class="grid2">'
                + channelPicker(base + "publishChannelId", "Kanal", "", state.config.textChannels)
                + channelPicker(base + "categoryId", "Kategorie für Tickets", "", state.config.categories)
                + '</div>'
                + selectField(base + "interactionMode", "Bedienung", modes, "")
                + areaField(base + "welcomeMessage", "Begrüßung im Ticket", "", 3)
                + colorField(base + "accentColor", "Akzentfarbe")
                + roleMulti(base + "supportRoleIds", "Ticket-Team", "Diese Rollen sehen und bearbeiten die Tickets.")
                + switchRow(base + "allowClaim", "Übernehmen erlauben", "Ein Teammitglied kann ein Ticket für sich beanspruchen.")
                + switchRow(base + "allowCreatorClose", "Ersteller darf schließen", "Sonst kann nur das Team schließen.")
                + switchRow(base + "oneTicketPerUser", "Nur ein Ticket pro Person", "Verhindert Mehrfachöffnungen.")
                + '<div class="field"><label>Auswahlmöglichkeiten</label><div class="repeat">';

            (panel.options || []).forEach(function (option, optionIndex) {
                var optionBase = base + "options." + optionIndex + ".";
                html += '<div class="repeat-item"><span class="fields">'
                    + '<input class="input" data-p="' + optionBase + 'emoji" value="' + esc(option.emoji) + '" placeholder="Emoji">'
                    + '<input class="input" data-p="' + optionBase + 'label" value="' + esc(option.label) + '" placeholder="Beschriftung">'
                    + '<input class="input" data-p="' + optionBase + 'description" value="' + esc(option.description) + '" placeholder="Beschreibung">'
                    + '<input class="input mono" data-p="' + optionBase + 'channelNameTemplate" value="'
                    + esc(option.channelNameTemplate) + '" placeholder="ticket-{nummer}">'
                    + '</span>'
                    + '<button class="icon-btn danger" data-remove="panels.' + panelIndex + '.options|' + optionIndex + '">'
                    + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button></div>';
            });

            html += '</div><button class="btn btn-secondary btn-sm" style="margin-top:12px" data-add="panels.'
                + panelIndex + '.options|ticketOption"><svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Möglichkeit hinzufügen</button></div>'
                + '<div class="row"><div class="label"><b>Panel entfernen</b><span>Die veröffentlichte Nachricht bleibt bestehen.</span></div>'
                + '<div class="control"><button class="btn btn-danger btn-sm" data-remove="panels|' + panelIndex + '">Entfernen</button></div></div>'
                + '</div></div>';
        });

        if (!draft.panels.length) {
            html += '<div class="card"><div class="card-body"><div class="repeat-empty">Noch kein Panel angelegt.</div></div></div>';
        }

        html += '<button class="btn btn-secondary btn-sm" data-add="panels|ticketPanel">'
            + '<svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Panel hinzufügen</button>';

        if ((draft.transcripts || []).length) {
            html += '<div class="card" style="margin-top:16px"><h2>Letzte Protokolle</h2><div class="list">';
            draft.transcripts.forEach(function (transcript) {
                html += '<div class="list-item"><span class="lt"><b>' + esc(transcript.ticketSubject || "Ticket") + '</b>'
                    + '<span>' + esc(transcript.openerDisplay || "") + '</span></span>'
                    + '<span class="meta">' + dateText(transcript.createdAt) + '</span>'
                    + '<a class="btn btn-secondary btn-sm" href="/api/dashboard/guilds/' + esc(state.guildId)
                    + '/tickets/transcripts/' + transcript.id + '">Öffnen</a></div>';
            });
            html += '</div></div>';
        }

        html += '</div>';
        return html;
    }

    function renderLogs() {
        beginDraft("discordLogs");
        if (!draft) { return head("logs", "Nicht verfügbar."); }

        var groups = [
            ["Mitglieder", [["memberJoin", "Beitritt"], ["memberLeave", "Austritt"],
                ["nicknameUpdates", "Nickname geändert"], ["roleUpdates", "Rollen geändert"]]],
            ["Moderation", [["bans", "Bans"], ["kicks", "Kicks"], ["timeouts", "Timeouts"],
                ["moderation", "Moderationsaktionen"]]],
            ["Nachrichten", [["messageDeletes", "Gelöschte Nachrichten"], ["commands", "Command-Aufrufe"]]],
            ["Sprachkanäle", [["voiceJoin", "Betreten"], ["voiceLeave", "Verlassen"],
                ["voiceModeration", "Stummschalten / Verschieben"], ["music", "Musik-Ereignisse"]]]
        ];

        var html = head("logs", "Ereignisse in einen Kanal schreiben. Jedes Ereignis lässt sich einzeln abschalten.",
            masterToggle("enabled", draft.enabled));
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>'
            + '<div class="card"><div class="card-body">'
            + channelPicker("textChannelId", "Log-Kanal", "", state.config.textChannels)
            + '</div></div>'
            + '<div class="card"><h2>Ereignisse</h2><div class="card-body"><div class="log-grid">';

        groups.forEach(function (group) {
            html += '<div class="log-sub">' + esc(group[0]) + '</div>';
            group[1].forEach(function (entry) {
                html += '<div class="log-item"><label class="switch"><input type="checkbox" data-p="' + entry[0] + '" data-kind="bool"'
                    + (draft[entry[0]] ? " checked" : "") + '><span class="track"></span><span class="knob"></span></label>'
                    + '<span class="t">' + esc(entry[1]) + '</span></div>';
            });
        });

        html += '</div></div></div></div>';
        return html;
    }

    function renderJtc() {
        beginDraft("joinToCreate");
        if (!draft) { return head("jtc", "Nicht verfügbar."); }
        if (!draft.entries) { draft.entries = []; }

        var html = head("jtc", "Wer einen Auslöser-Kanal betritt, bekommt automatisch einen eigenen Sprachkanal.",
            masterToggle("enabled", draft.enabled));

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>'
            + '<div class="card"><h2>Auslöser</h2><div class="card-body"><div class="repeat">';

        draft.entries.forEach(function (entry, index) {
            var base = "entries." + index + ".";
            var voiceOptions = '<option value="">— Kanal wählen —</option>';
            state.voiceChannels.forEach(function (channel) {
                voiceOptions += '<option value="' + esc(channel.id) + '"'
                    + (channel.id === entry.sourceChannelId ? " selected" : "") + '>' + esc(channel.name) + '</option>';
            });
            var categoryOptions = '<option value="">— gleiche Kategorie —</option>';
            (state.config.categories || []).forEach(function (category) {
                categoryOptions += '<option value="' + esc(category.id) + '"'
                    + (category.id === entry.categoryId ? " selected" : "") + '>' + esc(category.name) + '</option>';
            });

            html += '<div class="repeat-item"><span class="fields">'
                + '<div class="picker"><span class="glyph">🔊</span><select data-p="' + base + 'sourceChannelId">' + voiceOptions + '</select></div>'
                + '<div class="picker"><span class="glyph">📁</span><select data-p="' + base + 'categoryId">' + categoryOptions + '</select></div>'
                + '<input class="input" data-p="' + base + 'nameTemplate" value="' + esc(entry.nameTemplate) + '" placeholder="{user}s Kanal">'
                + '<input class="input" type="number" data-p="' + base + 'userLimit" data-kind="int" value="'
                + esc(entry.userLimit) + '" placeholder="Nutzerlimit (0 = frei)">'
                + '</span>'
                + '<button class="icon-btn danger" data-remove="entries|' + index + '">'
                + '<svg viewBox="0 0 24 24"><use href="#i-trash"/></svg></button></div>';
        });

        if (!draft.entries.length) {
            html += '<div class="repeat-empty">Noch kein Auslöser angelegt.</div>';
        }

        html += '</div><button class="btn btn-secondary btn-sm" style="margin-top:12px" data-add="entries|jtcEntry">'
            + '<svg viewBox="0 0 24 24"><use href="#i-plus"/></svg>Auslöser hinzufügen</button></div></div>'
            + '<div class="card"><h2>Verhalten</h2><div class="card-body">'
            + numberField("cleanupDelaySeconds", "Leeren Kanal löschen nach (Sekunden)",
                "Verzögerung, damit ein kurzer Wechsel den Kanal nicht sofort entfernt.", 0, 600)
            + numberField("audioIdleTimeoutSeconds", "Bot verlässt Kanal nach (Sekunden)",
                "Wenn nichts mehr läuft und niemand zuhört.", 0, 3600)
            + '<div class="row"><div class="label"><b>Aktuell verwaltet</b>'
            + '<span>Vom Modul erzeugte Kanäle, die gerade existieren.</span></div>'
            + '<div class="control"><span class="pill' + (draft.managedChannelCount ? " on" : "") + '">'
            + draft.managedChannelCount + ' Kanäle</span></div></div>'
            + '</div></div></div>';
        return html;
    }

    function renderLlm() {
        beginDraft("llm");
        if (!draft) { return head("llm", "Nicht verfügbar."); }

        var html = head("llm", "Der Bot antwortet in einem Kanal und kann dabei die Audio-Funktionen bedienen.",
            masterToggle("enabled", draft.enabled));

        if (!draft.configured) {
            html += '<div class="notice">Für diesen Server ist der KI-Chat nicht freigeschaltet oder global nicht konfiguriert. '
                + 'Die Funktion kostet im Betrieb Geld — die Freigabe erteilt der Betreiber des Bots.</div>';
        }
        if (draft.notice) { html += '<div class="notice">' + esc(draft.notice) + '</div>'; }

        var modelChoices = (draft.availableModels || []).map(function (model) {
            return { value: model, label: model };
        });
        if (!modelChoices.length) {
            modelChoices = [{ value: draft.model || "", label: draft.model || "— kein Modell freigegeben —" }];
        }

        html += '<div id="modBody"' + (draft.enabled ? "" : ' class="dim"') + '>'
            + '<div class="card"><div class="card-body">'
            + channelPicker("textChannelId", "Kanal",
                "Leer lassen, damit der Bot in jedem Kanal auf eine Erwähnung reagiert.", state.config.textChannels)
            + selectField("model", "Modell", modelChoices,
                "Anbieter: <b>" + esc(draft.provider || "—") + "</b>")
            + '</div></div></div>';
        return html;
    }

    function renderCommands() {
        var commands = (state.config && state.config.commands) || [];
        var byCategory = {};
        commands.forEach(function (command) {
            var key = command.category || "Sonstiges";
            (byCategory[key] = byCategory[key] || []).push(command);
        });

        var active = commands.filter(function (c) { return c.enabled; }).length;
        var html = head("commands", commands.length + " Slash-Commands · " + active
            + " aktiv. Abschalten gilt nur für diesen Server.");

        Object.keys(byCategory).sort().forEach(function (category) {
            html += '<div class="card"><h2>' + esc(category) + '</h2><div class="card-body"><div class="cmd-grid">';
            byCategory[category].forEach(function (command) {
                html += '<div class="cmd"><label class="switch"><input type="checkbox" data-command="' + esc(command.name) + '"'
                    + (command.enabled ? " checked" : "") + '><span class="track"></span><span class="knob"></span></label>'
                    + '<code>/' + esc(command.name) + '</code>'
                    + '<span class="cd">' + esc(command.description || "") + '</span></div>';
            });
            html += '</div></div></div>';
        });
        return html;
    }

    var RENDERERS = {
        overview: renderOverview, permissions: renderPermissions,
        player: renderPlayer, queue: renderQueue, radio: renderRadio,
        welcome: renderWelcome, verify: renderVerify, reactionroles: renderReactionRoles,
        invites: renderInvites, tickets: renderTickets, logs: renderLogs,
        jtc: renderJtc, llm: renderLlm, commands: renderCommands
    };

    // Welches Modul gehört zu welcher Seite (für Speichern)
    var PAGE_MODULE = {
        welcome: "welcome", verify: "verify", reactionroles: "reactionRoles",
        invites: "inviteTracker", tickets: "tickets", logs: "discordLogs",
        jtc: "joinToCreate", llm: "llm"
    };

    var MODULE_ENDPOINT = {
        welcome: "welcome", verify: "verify", reactionRoles: "reaction-roles",
        inviteTracker: "invite-tracker", tickets: "tickets", discordLogs: "discord-logs",
        joinToCreate: "join-to-create", llm: "llm"
    };

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    function currentGuild() {
        for (var i = 0; i < state.guilds.length; i++) {
            if (state.guilds[i].id === state.guildId) { return state.guilds[i]; }
        }
        return null;
    }

    function renderRail() {
        var rail = document.getElementById("guildRail");
        var html = "";
        state.guilds.forEach(function (guild) {
            var initials = guild.name.split(/\s+/).slice(0, 2).map(function (word) {
                return word.charAt(0).toUpperCase();
            }).join("");
            html += '<button class="rail-item' + (guild.id === state.guildId ? " is-active" : "")
                + '" data-guild="' + esc(guild.id) + '" title="' + esc(guild.name) + '">'
                + (guild.iconUrl ? '<img src="' + esc(guild.iconUrl) + '" alt="">' : esc(initials))
                + '</button>';
        });
        rail.innerHTML = html;
    }

    function show(name) {
        if (!PAGES[name]) { name = "overview"; }

        var moduleName = PAGE_MODULE[name];
        if (draftModule && draftModule !== moduleName && savebar.classList.contains("is-shown")) {
            if (!window.confirm("Du hast ungespeicherte Änderungen. Trotzdem wechseln?")) {
                return;
            }
        }
        if (draftModule !== moduleName) {
            draft = null;
            draftModule = null;
            savebar.classList.remove("is-shown");
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

        schedulePolling();

        if (name === "permissions" && !state.permissions) {
            loadPermissions();
        }
    }

    function refreshBadges() {
        var config = state.config || {};
        Array.prototype.forEach.call(document.querySelectorAll("[data-mod]"), function (badge) {
            var moduleName = badge.dataset.mod;
            var enabled = config[moduleName] && config[moduleName].enabled;
            badge.textContent = enabled ? "an" : "aus";
            badge.classList.toggle("on", Boolean(enabled));
        });

        var commands = config.commands || [];
        document.getElementById("commandBadge").textContent = commands.length || "";

        var player = state.player;
        var queueBadge = document.getElementById("queueBadge");
        var playerBadge = document.getElementById("playerBadge");
        if (player) {
            queueBadge.textContent = (player.queue || []).length || "";
            playerBadge.textContent = player.connected ? "live" : "";
            playerBadge.classList.toggle("on", Boolean(player.connected));

            var connPill = document.getElementById("connPill");
            connPill.className = "status-pill" + (player.connected ? "" : " bad");
            connPill.innerHTML = '<span class="dot"></span>'
                + (player.connected ? esc(player.voiceChannelName || "verbunden") : "getrennt");

            var voicePill = document.getElementById("voicePill");
            if (player.userInVoiceChannel) {
                voicePill.hidden = false;
                voicePill.textContent = "du: " + (player.userVoiceChannelName || "");
            } else {
                voicePill.hidden = true;
            }
        }

        var guild = currentGuild();
        document.getElementById("guildName").textContent = guild ? guild.name : "Server wählen";
    }

    // ------------------------------------------------------------------
    // Laden
    // ------------------------------------------------------------------

    async function selectGuild(guildId) {
        state.guildId = guildId;
        state.config = null;
        state.player = null;
        state.permissions = null;
        draft = null;
        draftModule = null;
        renderRail();
        page.innerHTML = '<div class="empty"><span class="spinner"></span></div>';

        try {
            var results = await Promise.all([
                api("GET", "/api/dashboard/guilds/" + guildId + "/config"),
                api("GET", "/api/dashboard/guilds/" + guildId + "/player"),
                api("GET", "/api/dashboard/guilds/" + guildId + "/voice-channels"),
                api("GET", "/api/dashboard/radio/stations")
            ]);
            state.config = results[0];
            state.player = results[1];
            state.voiceChannels = results[2];
            state.stations = results[3];
        } catch (error) {
            page.innerHTML = '<div class="notice">Der Server konnte nicht geladen werden: ' + esc(error.message) + '</div>';
            return;
        }

        refreshBadges();
        show(state.page);
    }

    async function loadPermissions() {
        try {
            state.permissions = await api("GET", "/api/dashboard/guilds/" + state.guildId + "/permissions");
            if (state.page === "permissions") { show("permissions"); }
        } catch (error) {
            toast(error.message, true);
        }
    }

    // ------------------------------------------------------------------
    // Player: Abfrage nur wenn nötig
    // ------------------------------------------------------------------

    function schedulePolling() {
        if (pollTimer) {
            window.clearInterval(pollTimer);
            pollTimer = null;
        }
        if (!PAGES[state.page] || !PAGES[state.page].live || !state.guildId) {
            return;
        }
        pollTimer = window.setInterval(function () {
            if (document.visibilityState !== "visible") {
                return;
            }
            refreshPlayer();
        }, 5000);
    }

    async function refreshPlayer(force) {
        if (!state.guildId) { return; }
        try {
            var player = await api("GET", "/api/dashboard/guilds/" + state.guildId + "/player");
            var previous = state.player;
            state.player = player;
            refreshBadges();

            // Wenn sich nur die Position geändert hat, reicht ein Feld-Update -
            // ein kompletter Neuaufbau würde bei jedem Tick das Scrollen stören.
            var sameTrack = previous && player.currentTrack && previous.currentTrack
                && previous.currentTrack.identifier === player.currentTrack.identifier
                && previous.paused === player.paused
                && (previous.queue || []).length === (player.queue || []).length;

            if (force || !sameTrack) {
                if (state.page === "player" || state.page === "queue" || state.page === "overview" || state.page === "radio") {
                    show(state.page);
                }
                return;
            }

            var total = player.currentTrack ? player.currentTrack.durationMs : 0;
            var fill = document.getElementById("pFill");
            var pos = document.getElementById("pPos");
            if (fill && total > 0 && !player.playingRadio) {
                fill.style.transform = "scaleX(" + Math.min(1, player.positionMs / total).toFixed(4) + ")";
            }
            if (pos) {
                pos.textContent = duration(player.positionMs, false);
            }
        } catch (ignored) {
            // Ein fehlgeschlagener Tick ist kein Grund, den Nutzer zu behelligen.
        }
    }

    // ------------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------------

    function buildRequest(moduleName, working) {
        switch (moduleName) {
            case "welcome":
                return {
                    enabled: working.enabled, roleIds: working.roleIds, channelId: working.channelId,
                    welcomeText: working.welcomeText, sendImage: working.sendImage,
                    backgroundImageUrl: working.backgroundImageUrl, accentColor: working.accentColor
                };
            case "verify":
                return {
                    enabled: working.enabled, publishChannelId: working.publishChannelId,
                    verifiedRoleIds: working.verifiedRoleIds, title: working.title,
                    description: working.description, imageUrl: working.imageUrl,
                    thumbnailUrl: working.thumbnailUrl, accentColor: working.accentColor
                };
            case "reactionRoles":
                return {
                    enabled: working.enabled,
                    panels: (working.panels || []).map(function (panel) {
                        return {
                            id: panel.id, publishChannelId: panel.publishChannelId, title: panel.title,
                            description: panel.description, imageUrl: panel.imageUrl,
                            thumbnailUrl: panel.thumbnailUrl, accentColor: panel.accentColor,
                            entries: (panel.entries || []).map(function (entry) {
                                return {
                                    id: entry.id, emoji: entry.emoji, roleIds: entry.roleIds,
                                    label: entry.label, description: entry.description
                                };
                            })
                        };
                    })
                };
            case "inviteTracker":
                return { enabled: working.enabled };
            case "tickets":
                return {
                    enabled: working.enabled, transcriptChannelId: working.transcriptChannelId,
                    panels: (working.panels || []).map(function (panel) {
                        return {
                            id: panel.id, title: panel.title, description: panel.description,
                            interactionMode: panel.interactionMode, publishChannelId: panel.publishChannelId,
                            categoryId: panel.categoryId, placeholder: panel.placeholder,
                            welcomeMessage: panel.welcomeMessage, imageUrl: panel.imageUrl,
                            thumbnailUrl: panel.thumbnailUrl, accentColor: panel.accentColor,
                            notifyRoleId: panel.notifyRoleId, supportRoleIds: panel.supportRoleIds,
                            allowClaim: panel.allowClaim, allowPause: panel.allowPause,
                            allowCreatorClose: panel.allowCreatorClose, oneTicketPerUser: panel.oneTicketPerUser,
                            options: (panel.options || []).map(function (option) {
                                return {
                                    id: option.id, label: option.label, description: option.description,
                                    emoji: option.emoji, channelNameTemplate: option.channelNameTemplate,
                                    supportRoleIds: option.supportRoleIds
                                };
                            })
                        };
                    })
                };
            case "discordLogs":
                return {
                    enabled: working.enabled, textChannelId: working.textChannelId,
                    memberJoin: working.memberJoin, memberLeave: working.memberLeave,
                    voiceJoin: working.voiceJoin, voiceLeave: working.voiceLeave,
                    music: working.music, moderation: working.moderation,
                    roleUpdates: working.roleUpdates, nicknameUpdates: working.nicknameUpdates,
                    timeouts: working.timeouts, kicks: working.kicks, bans: working.bans,
                    messageDeletes: working.messageDeletes, voiceModeration: working.voiceModeration,
                    commands: working.commands
                };
            case "joinToCreate":
                return {
                    enabled: working.enabled,
                    cleanupDelaySeconds: working.cleanupDelaySeconds,
                    audioIdleTimeoutSeconds: working.audioIdleTimeoutSeconds,
                    entries: (working.entries || []).map(function (entry) {
                        return {
                            id: entry.id, sourceChannelId: entry.sourceChannelId, categoryId: entry.categoryId,
                            nameTemplate: entry.nameTemplate, userLimit: entry.userLimit,
                            bitrateKbps: entry.bitrateKbps, sendConfigPrompt: entry.sendConfigPrompt
                        };
                    })
                };
            case "llm":
                return { enabled: working.enabled, textChannelId: working.textChannelId, model: working.model };
            default:
                return working;
        }
    }

    async function save() {
        if (!draftModule || !draft) {
            savebar.classList.remove("is-shown");
            return;
        }
        var button = document.getElementById("saveBtn");
        button.disabled = true;
        try {
            var result = await api("POST",
                "/api/dashboard/guilds/" + state.guildId + "/modules/" + MODULE_ENDPOINT[draftModule],
                buildRequest(draftModule, draft));
            toast(result.message || "Gespeichert.");
            state.config = await api("GET", "/api/dashboard/guilds/" + state.guildId + "/config");
            draft = null;
            draftModule = null;
            savebar.classList.remove("is-shown");
            refreshBadges();
            show(state.page);
        } catch (error) {
            toast(error.message, true);
        } finally {
            button.disabled = false;
        }
    }

    function resetChanges() {
        draft = null;
        draftModule = null;
        savebar.classList.remove("is-shown");
        show(state.page);
    }

    // ------------------------------------------------------------------
    // Ereignisse
    // ------------------------------------------------------------------

    document.getElementById("guildRail").addEventListener("click", function (event) {
        var button = event.target.closest("[data-guild]");
        if (button && button.dataset.guild !== state.guildId) {
            selectGuild(button.dataset.guild);
        }
    });

    document.getElementById("navScroll").addEventListener("click", function (event) {
        var link = event.target.closest(".nav-link");
        if (link) { show(link.dataset.page); }
    });

    document.getElementById("navSearch").addEventListener("input", function (event) {
        var query = event.target.value.trim().toLowerCase();
        Array.prototype.forEach.call(document.querySelectorAll(".nav-group"), function (group) {
            var any = false;
            Array.prototype.forEach.call(group.querySelectorAll(".nav-link"), function (link) {
                var hit = !query || link.textContent.toLowerCase().indexOf(query) !== -1;
                link.hidden = !hit;
                if (hit) { any = true; }
            });
            group.hidden = !any;
        });
    });

    page.addEventListener("input", function (event) {
        var element = event.target;

        if (element.id === "volRange") {
            document.getElementById("volValue").textContent = element.value + " %";
            return;
        }
        if (!element.dataset.p || !draft) { return; }

        var raw = element.value;
        if (element.dataset.kind === "int") {
            setPath(draft, element.dataset.p, raw === "" ? null : parseInt(raw, 10));
        } else {
            setPath(draft, element.dataset.p, raw);
            if (element.dataset.kind === "color") {
                // Farbwähler und Textfeld zeigen denselben Wert.
                var partner = page.querySelector('input.mono[data-p="' + element.dataset.p + '"]');
                if (partner && partner !== element) { partner.value = raw; }
            }
        }
        markDirty();
    });

    page.addEventListener("change", async function (event) {
        var element = event.target;

        if (element.id === "volRange") {
            try {
                await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/volume",
                    { volume: parseInt(element.value, 10) });
                await refreshPlayer(false);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        if (element.dataset.command) {
            try {
                var result = await api("POST",
                    "/api/dashboard/guilds/" + state.guildId + "/commands/" + element.dataset.command,
                    { enabled: element.checked });
                toast(result.message || "Gespeichert.");
                state.config = await api("GET", "/api/dashboard/guilds/" + state.guildId + "/config");
                refreshBadges();
            } catch (error) {
                element.checked = !element.checked;
                toast(error.message, true);
            }
            return;
        }

        if (element.dataset.perm) { return; }   // wird erst beim Speichern übertragen

        if (element.dataset.roleAdd) {
            if (!element.value || !draft) { return; }
            var list = getPath(draft, element.dataset.roleAdd) || [];
            if (list.indexOf(element.value) === -1) { list.push(element.value); }
            setPath(draft, element.dataset.roleAdd, list);
            markDirty();
            show(state.page);
            return;
        }

        if (element.dataset.p && draft) {
            if (element.dataset.kind === "bool") {
                setPath(draft, element.dataset.p, element.checked);
                var body = document.getElementById("modBody");
                if (body && element.dataset.p === "enabled") {
                    body.classList.toggle("dim", !element.checked);
                }
            } else {
                setPath(draft, element.dataset.p, element.value);
            }
            markDirty();
        }
    });

    page.addEventListener("click", async function (event) {
        var target = event.target;

        var goto = target.closest("[data-goto]");
        if (goto) { show(goto.dataset.goto); return; }

        var roleRemove = target.closest("[data-role-remove]");
        if (roleRemove && draft) {
            var parts = roleRemove.dataset.roleRemove.split("|");
            var current = (getPath(draft, parts[0]) || []).filter(function (id) { return id !== parts[1]; });
            setPath(draft, parts[0], current);
            markDirty();
            show(state.page);
            return;
        }

        var add = target.closest("[data-add]");
        if (add && draft) {
            var addParts = add.dataset.add.split("|");
            var listPath = addParts[0];
            var list = getPath(draft, listPath) || [];
            list.push(newEntry(addParts[1], list.length));
            setPath(draft, listPath, list);
            markDirty();
            show(state.page);
            return;
        }

        var remove = target.closest("[data-remove]");
        if (remove && draft) {
            var removeParts = remove.dataset.remove.split("|");
            var target2 = getPath(draft, removeParts[0]) || [];
            target2.splice(Number(removeParts[1]), 1);
            setPath(draft, removeParts[0], target2);
            markDirty();
            show(state.page);
            return;
        }

        var playerAction = target.closest("[data-player]");
        if (playerAction) {
            await sendPlayer(playerAction.dataset.player);
            return;
        }

        var toggle = target.closest("[data-toggle]");
        if (toggle) {
            var name = toggle.dataset.toggle;
            var enabled = name === "repeat" ? !state.player.repeatEnabled : !state.player.bassBoostEnabled;
            try {
                var result = await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/" + name, { enabled: enabled });
                toast(result.message || "Übernommen.");
                await refreshPlayer(true);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        var radio = target.closest("[data-radio]");
        if (radio) {
            var channelSelect = document.getElementById("radioChannel");
            try {
                var radioResult = await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/radio",
                    { radioId: Number(radio.dataset.radio), voiceChannelId: channelSelect ? channelSelect.value : "" });
                toast(radioResult.message || "Gestartet.", !radioResult.success);
                await refreshPlayer(true);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        var queueUp = target.closest("[data-queue-up]");
        if (queueUp) {
            var index = Number(queueUp.dataset.queueUp);
            try {
                await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/queue/move",
                    { fromIndex: index, toIndex: index - 1 });
                await refreshPlayer(true);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        var queueRemove = target.closest("[data-queue-remove]");
        if (queueRemove) {
            try {
                await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/queue/remove",
                    { index: Number(queueRemove.dataset.queueRemove) });
                await refreshPlayer(true);
            } catch (error) {
                toast(error.message, true);
            }
            return;
        }

        var action = target.closest("[data-action]");
        if (action) {
            await runAction(action.dataset.action, action);
        }
    });

    function newEntry(kind, index) {
        switch (kind) {
            case "rrPanel":
                return { id: "", publishChannelId: "", title: "Wähle deine Rollen", description: "",
                         imageUrl: "", thumbnailUrl: "", accentColor: "#5865f2", messageId: "", entries: [] };
            case "rrEntry":
                return { id: "", emoji: "", roleIds: [], label: "", description: "" };
            case "ticketPanel":
                return { id: "", title: "Support", description: "", interactionMode: "BUTTON",
                         publishChannelId: "", categoryId: "", placeholder: "", welcomeMessage: "",
                         imageUrl: "", thumbnailUrl: "", accentColor: "#5865f2", notifyRoleId: "",
                         supportRoleIds: [], allowClaim: true, allowPause: false, allowCreatorClose: true,
                         oneTicketPerUser: true, messageId: "", options: [] };
            case "ticketOption":
                return { id: "", label: "Anliegen", description: "", emoji: "",
                         channelNameTemplate: "ticket-{nummer}", supportRoleIds: [] };
            case "jtcEntry":
                return { id: "", sourceChannelId: "", categoryId: "", nameTemplate: "{user}s Kanal",
                         userLimit: 0, bitrateKbps: 0, nextCounter: 1, sendConfigPrompt: false };
            default:
                return {};
        }
    }

    async function sendPlayer(action) {
        try {
            var result = await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/" + action);
            toast(result.message || "Übernommen.", result.success === false);
            await refreshPlayer(true);
        } catch (error) {
            toast(error.message, true);
        }
    }

    async function runAction(name, element) {
        if (name === "play") {
            var query = document.getElementById("playQuery").value.trim();
            var channel = document.getElementById("playChannel").value;
            if (!query) {
                toast("Bitte einen Suchbegriff oder eine URL angeben.", true);
                return;
            }
            element.disabled = true;
            try {
                var result = await api("POST", "/api/dashboard/guilds/" + state.guildId + "/player/play",
                    { query: query, voiceChannelId: channel });
                toast(result.message || "Wird abgespielt.", result.success === false);
                document.getElementById("playQuery").value = "";
                await refreshPlayer(true);
            } catch (error) {
                toast(error.message, true);
            } finally {
                element.disabled = false;
            }
            return;
        }

        if (name === "save-permissions") {
            var matrix = {};
            Array.prototype.forEach.call(page.querySelectorAll("[data-perm]"), function (input) {
                if (!input.checked) { return; }
                var parts = input.dataset.perm.split("|");
                (matrix[parts[0]] = matrix[parts[0]] || []).push(parts[1]);
            });
            element.disabled = true;
            try {
                var saved = await api("POST", "/api/dashboard/guilds/" + state.guildId + "/permissions", { matrix: matrix });
                toast(saved.message || "Gespeichert.");
                state.permissions = null;
                await loadPermissions();
            } catch (error) {
                toast(error.message, true);
            } finally {
                element.disabled = false;
            }
        }
    }

    document.getElementById("saveBtn").addEventListener("click", save);
    document.getElementById("resetBtn").addEventListener("click", resetChanges);

    document.addEventListener("visibilitychange", function () {
        if (document.visibilityState === "visible" && PAGES[state.page] && PAGES[state.page].live) {
            refreshPlayer(false);
        }
    });

    // Design
    var themeBtn = document.getElementById("themeBtn");
    function applyTheme(next) {
        document.documentElement.setAttribute("data-theme", next);
        themeBtn.querySelector("use").setAttribute("href", next === "dark" ? "#i-sun" : "#i-moon");
        try { window.localStorage.setItem("panel-theme", next); } catch (ignored) { /* privater Modus */ }
    }
    themeBtn.addEventListener("click", function () {
        applyTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark");
    });
    (function initTheme() {
        var stored = null;
        try { stored = window.localStorage.getItem("panel-theme"); } catch (ignored) { stored = null; }
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
        if (savebar.classList.contains("is-shown")) {
            event.preventDefault();
            event.returnValue = "";
        }
    });

    // Start
    if (state.guilds.length) {
        selectGuild(state.guilds[0].id);
    } else {
        page.innerHTML = '<div class="notice">Auf keinem deiner Server ist der Bot vorhanden — oder dir fehlt dort das Recht, das Panel zu öffnen.</div>';
    }
})();
