(() => {
    const SUMMARY_REFRESH_MS = 60_000;
    const CHART_REFRESH_MS = 120_000;
    /*
     * Die Diagrammfarben waren fest verdrahtet und passten nach der
     * Design-Ueberarbeitung weder zum dunklen noch zum hellen Modus. Sie
     * kommen jetzt aus denselben CSS-Variablen wie der Rest der Oberflaeche
     * und werden bei einem Theme-Wechsel neu gelesen.
     */
    function readColor(name, fallback) {
        const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
        return value || fallback;
    }

    /*
     * Diagrammfarben sind eigene Toene, nicht die Akzentfarbe der Oberflaeche.
     *
     * Das klingt nach unnoetiger Doppelung, ist aber der Punkt: eine Farbe, die
     * als Knopfflaeche gut aussieht, ist als 2px-Linie auf derselben Flaeche
     * oft zu blass. Die hier gewaehlten Toene sind gegen die Kartenflaeche
     * geprueft - Helligkeitsband, Buntheit, Unterscheidbarkeit bei Farbsehschwaeche
     * und Kontrast, in beiden Modi.
     *
     * Gruen und Blau sind fuer Rot-Gruen-Schwaeche der beste verfuegbare
     * Abstand (Delta E 19.6 im dunklen, 20.1 im hellen Modus). Zusaetzlich
     * traegt jedes Diagramm seinen eigenen Titel und seine eigene Achse - die
     * Farbe allein muss die beiden nie auseinanderhalten.
     */
    function currentChartColors() {
        return {
            stream: {
                line: readColor("--chart-1", "#3987e5"),
                area: readColor("--chart-1-soft", "rgba(57, 135, 229, 0.18)")
            },
            listeners: {
                line: readColor("--chart-2", "#199e70"),
                area: readColor("--chart-2-soft", "rgba(25, 158, 112, 0.18)")
            }
        };
    }

    let chartColors = currentChartColors();

    const rangeTabs = Array.from(document.querySelectorAll(".stats-range-tab"));
    const statsGeneratedLabel = document.getElementById("statsGeneratedLabel");
    const statsSystemHeadline = document.getElementById("statsSystemHeadline");
    const statsSystemNote = document.getElementById("statsSystemNote");
    const statsSystemState = document.getElementById("statsSystemState");
    const liveStatusBadge = document.getElementById("liveStatusBadge");
    const liveListenersValue = document.getElementById("liveListenersValue");
    const liveStreamsValue = document.getElementById("liveStreamsValue");
    const listenTime30dValue = document.getElementById("listenTime30dValue");
    const uniqueListeners30dValue = document.getElementById("uniqueListeners30dValue");
    const liveStatsList = document.getElementById("liveStatsList");
    const liveStatsEmpty = document.getElementById("liveStatsEmpty");
    const chartRangeLabel = document.getElementById("chartRangeLabel");
    const chartSummaryTime = document.getElementById("chartSummaryTime");
    const chartSummaryListeners = document.getElementById("chartSummaryListeners");
    const chartSummaryPeak = document.getElementById("chartSummaryPeak");
    const nodeListe = document.getElementById("nodeListe");
    const nodeLeer = document.getElementById("nodeLeer");
    const nodeStatusBadge = document.getElementById("nodeStatusBadge");
    const streamTimeChart = document.getElementById("streamTimeChart");
    const listenerChart = document.getElementById("listenerChart");
    const topTracksList = document.getElementById("topTracksList");
    const topTracksEmpty = document.getElementById("topTracksEmpty");
    const topArtistsList = document.getElementById("topArtistsList");
    const topArtistsEmpty = document.getElementById("topArtistsEmpty");
    const topSourcesList = document.getElementById("topSourcesList");
    const topSourcesEmpty = document.getElementById("topSourcesEmpty");

    let currentRange = rangeTabs.find((tab) => tab.classList.contains("active"))?.dataset.range || "30t";
    let summaryRefreshHandle = null;
    let chartRefreshHandle = null;
    let chartRequestToken = 0;
    let summaryRequestToken = 0;

    function init() {
        rangeTabs.forEach((tab) => {
            tab.addEventListener("click", () => {
                const nextRange = tab.dataset.range || "30t";
                if (nextRange === currentRange) {
                    return;
                }
                currentRange = nextRange;
                updateRangeButtons();
                refreshCharts();
            });
        });

        refreshSummary();
        refreshCharts();
        ladeKnoten();

        summaryRefreshHandle = window.setInterval(() => {
            // Ein Tab im Hintergrund muss den Server nicht befragen.
            if (!document.hidden) {
                refreshSummary();
                ladeKnoten();
            }
        }, SUMMARY_REFRESH_MS);
        chartRefreshHandle = window.setInterval(() => {
            if (!document.hidden) {
                refreshCharts();
            }
        }, CHART_REFRESH_MS);

        document.addEventListener("visibilitychange", () => {
            if (!document.hidden) {
                refreshSummary();
            }
        });

        // Nach einem Theme-Wechsel muessen die Diagramme mit den neuen
        // Farbwerten neu gezeichnet werden.
        new MutationObserver(() => {
            chartColors = currentChartColors();
            refreshCharts();
        }).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

        window.addEventListener("beforeunload", clearTimers, { once: true });
    }

    function clearTimers() {
        if (summaryRefreshHandle) {
            window.clearInterval(summaryRefreshHandle);
        }
        if (chartRefreshHandle) {
            window.clearInterval(chartRefreshHandle);
        }
    }

    async function refreshSummary() {
        const requestToken = ++summaryRequestToken;
        try {
            const response = await fetch("/api/public/stats", {
                headers: { "Accept": "application/json" },
                cache: "no-store"
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const payload = await response.json();
            if (requestToken !== summaryRequestToken) {
                return;
            }
            renderSummary(payload);
        } catch (error) {
            console.error("Öffentliche Statistik konnte nicht geladen werden.", error);
        }
    }

    async function refreshCharts() {
        const requestToken = ++chartRequestToken;
        try {
            const response = await fetch(`/api/public/stats/chart?range=${encodeURIComponent(currentRange)}`, {
                headers: { "Accept": "application/json" },
                cache: "no-store"
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const payload = await response.json();
            if (requestToken !== chartRequestToken) {
                return;
            }
            renderCharts(payload);
        } catch (error) {
            console.error("Diagrammdaten konnten nicht geladen werden.", error);
            renderChartEmpty(streamTimeChart, "Diagrammdaten konnten nicht geladen werden.");
            renderChartEmpty(listenerChart, "Diagrammdaten konnten nicht geladen werden.");
        }
    }

    function renderSummary(payload) {
        const summary = payload?.summary || {};
        const liveItems = Array.isArray(payload?.liveItems) ? payload.liveItems : [];

        setText(statsGeneratedLabel, summary.generatedAtLabel ? `Stand ${summary.generatedAtLabel}` : "Stand");
        setText(liveListenersValue, number(summary.liveListeners));
        setText(liveStreamsValue, number(summary.liveStreams));
        setText(listenTime30dValue, summary.listenedTime30d || "0 min");
        setText(uniqueListeners30dValue, number(summary.uniqueListeners30d));

        const liveStreams = Number(summary.liveStreams || 0);
        const liveListeners = Number(summary.liveListeners || 0);
        if (liveStreams > 0) {
            setText(statsSystemHeadline, "Wiedergabe läuft stabil");
            setText(statsSystemNote, `${liveStreams} aktive Streams mit ${liveListeners} Live-Hörern.`);
            setBadge(statsSystemState, "Streaming aktiv", "success");
            setBadge(liveStatusBadge, "Online", "success");
        } else {
            setText(statsSystemHeadline, "System bereit");
            setText(statsSystemNote, "Der Bot ist online und wartet aktuell auf die nächste Wiedergabe.");
            setBadge(statsSystemState, "Bereit", "muted");
            setBadge(liveStatusBadge, "Kein Stream aktiv", "muted");
        }

        renderLiveItems(liveItems);
        renderRankedList(topTracksList, topTracksEmpty, payload?.topTracks, "Noch keine Track-Daten vorhanden.");
        renderRankedList(topArtistsList, topArtistsEmpty, payload?.topArtists, "Noch keine Artist-Daten vorhanden.");
        renderRankedList(topSourcesList, topSourcesEmpty, payload?.topSources, "Noch keine Radio- oder Quell-Daten vorhanden.");
    }

    function renderLiveItems(items) {
        if (!liveStatsList || !liveStatsEmpty) {
            return;
        }

        if (!Array.isArray(items) || items.length === 0) {
            liveStatsList.hidden = true;
            liveStatsEmpty.hidden = false;
            liveStatsEmpty.textContent = "Aktuell läuft keine öffentliche Wiedergabe.";
            return;
        }

        liveStatsList.hidden = false;
        liveStatsEmpty.hidden = true;
        liveStatsList.innerHTML = "";

        items.forEach((item) => {
            const row = document.createElement("article");
            row.className = "list-row stats-live-row";

            const content = document.createElement("div");
            const title = document.createElement("strong");
            title.textContent = item?.title || "Unbekannt";
            const subtitle = document.createElement("p");
            subtitle.textContent = item?.subtitle || "Live";
            content.append(title, subtitle);

            const meta = document.createElement("div");
            meta.className = "stats-live-meta";
            meta.append(
                createBadge(item?.modeLabel || "Live", "muted"),
                createBadge(`${number(item?.listenerCount)} Hörer`, "success")
            );

            row.append(content, meta);
            liveStatsList.appendChild(row);
        });
    }

    function renderRankedList(container, emptyState, items, emptyMessage) {
        if (!container || !emptyState) {
            return;
        }

        if (!Array.isArray(items) || items.length === 0) {
            container.hidden = true;
            emptyState.hidden = false;
            emptyState.textContent = emptyMessage;
            return;
        }

        container.hidden = false;
        emptyState.hidden = true;
        container.innerHTML = "";

        items.forEach((item) => {
            const row = document.createElement("div");
            row.className = "list-row";

            const content = document.createElement("div");
            const title = document.createElement("strong");
            title.textContent = item?.title || "Unbekannt";
            const subtitle = document.createElement("p");
            subtitle.textContent = item?.subtitle || "";
            content.append(title, subtitle);

            const meta = createBadge(item?.meta || "", "muted");
            row.append(content, meta);
            container.appendChild(row);
        });
    }

    function renderCharts(payload) {
        const points = Array.isArray(payload?.points) ? payload.points : [];
        const listenedTimeLabel = payload?.listenedTimeLabel || "0 min";
        const totalUniqueListeners = Number(payload?.totalUniqueListeners || 0);
        const peakListeners = Number(payload?.peakListeners || 0);
        const rangeLabel = payload?.rangeLabel || currentRange;

        setText(chartRangeLabel, rangeLabel);
        setText(chartSummaryTime, listenedTimeLabel);
        setText(chartSummaryListeners, number(totalUniqueListeners));
        setText(chartSummaryPeak, number(peakListeners));
        updateRangeButtons();

        renderLineChart(
            streamTimeChart,
            points.map((point) => ({
                label: point.label,
                value: Number(point.listenedSeconds || 0),
                meta: formatDuration(Number(point.listenedSeconds || 0))
            })),
            {
                ...chartColors.stream,
                titel: "Gestreamte Zeit im Zeitverlauf",
                // Die Achse zeigt Minuten und Stunden, nicht 43200 Sekunden.
                achse: (wert) => formatDuration(wert),
                emptyText: "Noch keine gestreamte Zeit im gewählten Zeitraum."
            }
        );

        fuelleTabelle(points);

        renderLineChart(
            listenerChart,
            points.map((point) => ({
                label: point.label,
                value: Number(point.uniqueListeners || 0),
                meta: `${number(point.uniqueListeners)} Hörer`
            })),
            {
                ...chartColors.listeners,
                titel: "Eindeutige Hörer im Zeitverlauf",
                emptyText: "Noch keine Hörer im gewählten Zeitraum."
            }
        );
    }

    /*
     * Ein Liniendiagramm als SVG.
     *
     * Gegenueber der ersten Fassung geaendert, und jedes Mal aus einem Grund:
     *
     *  - Die Groesse wird gemessen statt gedehnt. Vorher stand am SVG
     *    preserveAspectRatio="none": das streckt die Zeichnung ungleichmaessig
     *    auf die Breite des Kastens. Aus einer 2px-Linie wurde je nach
     *    Fensterbreite eine 1px- oder 4px-Linie, aus Kreisen wurden Eier.
     *  - Die Y-Achse ist beschriftet. Vier Gitterlinien ohne Zahlen daneben
     *    sagen nur "es geht rauf und runter" - die Frage ist aber "wie viel".
     *  - Punkte nur, wo sie etwas nuetzen. Bei 90 Messwerten ist eine Kette aus
     *    90 Kreisen keine Information, sondern eine dicke Linie.
     *  - Fadenkreuz und Tooltip beim Ueberfahren. Ein <title> im SVG zeigt
     *    Discords Vorschau nicht und erscheint erst nach einer Sekunde
     *    Stillstand genau ueber dem getroffenen Punkt - man muss also erst
     *    treffen, um zu erfahren, was man getroffen hat.
     */
    function renderLineChart(container, points, colors) {
        if (!container) {
            return;
        }

        if (!Array.isArray(points) || points.length === 0 || points.every((point) => point.value <= 0)) {
            renderChartEmpty(container, colors.emptyText);
            return;
        }

        // Fuer den spaeteren Neuaufbau beim Groessenwechsel merken.
        container._daten = points;
        container._farben = colors;

        const width = Math.max(320, Math.round(container.clientWidth || 820));
        const height = 260;
        const links = 58;
        const rechts = 14;
        const oben = 14;
        const unten = 30;
        const plotB = width - links - rechts;
        const plotH = height - oben - unten;

        const maxWert = Math.max(...points.map((p) => p.value), 1);
        const obergrenze = schoeneObergrenze(maxWert, !!colors.achse);
        const stepX = points.length > 1 ? plotB / (points.length - 1) : 0;

        const punkte = points.map((p, i) => ({
            ...p,
            x: links + stepX * i,
            y: oben + plotH - (p.value / obergrenze) * plotH
        }));

        const linie = punkte
            .map((p, i) => `${i === 0 ? "M" : "L"} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`)
            .join(" ");
        const flaeche = `${linie} L ${punkte[punkte.length - 1].x.toFixed(1)} ${(oben + plotH).toFixed(1)}`
            + ` L ${punkte[0].x.toFixed(1)} ${(oben + plotH).toFixed(1)} Z`;

        // Gitter samt Beschriftung. Die Achse traegt die Einheit, nicht jeder
        // einzelne Punkt - deshalb formatiert sie ueber dieselbe Funktion wie
        // der Tooltip.
        let gitter = "";
        const stufen = 4;
        for (let i = 0; i <= stufen; i += 1) {
            const y = oben + (plotH / stufen) * i;
            const wert = obergrenze * (1 - i / stufen);
            gitter += `<line class="chart-grid-line" x1="${links}" y1="${y.toFixed(1)}"`
                + ` x2="${(links + plotB).toFixed(1)}" y2="${y.toFixed(1)}"></line>`;
            gitter += `<text class="chart-axis-text" x="${links - 8}" y="${(y + 4).toFixed(1)}"`
                + ` text-anchor="end">${escapeHtml(colors.achse ? colors.achse(wert) : number(Math.round(wert)))}</text>`;
        }

        // Bei wenigen Messwerten ist jeder Punkt eine Aussage, bei vielen ist
        // die Linie die Aussage.
        const zeigePunkte = punkte.length <= 24;
        const punktKnoten = zeigePunkte
            ? punkte.map((p) => `<circle class="chart-point" cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}"`
                + ` r="4" fill="${colors.line}"></circle>`).join("")
            : "";

        let xBeschriftung = "";
        const maxLabels = Math.max(2, Math.min(7, Math.floor(plotB / 110)));
        const stellen = new Set();
        if (punkte.length <= maxLabels) {
            punkte.forEach((_, i) => stellen.add(i));
        } else {
            for (let i = 0; i < maxLabels; i += 1) {
                stellen.add(Math.round((i * (punkte.length - 1)) / (maxLabels - 1)));
            }
        }
        [...stellen].sort((a, b) => a - b).forEach((i) => {
            const p = punkte[i];
            const anker = i === 0 ? "start" : (i === punkte.length - 1 ? "end" : "middle");
            xBeschriftung += `<text class="chart-axis-text" x="${p.x.toFixed(1)}" y="${height - 10}"`
                + ` text-anchor="${anker}">${escapeHtml(p.label)}</text>`;
        });

        const kennung = `verlauf-${Math.random().toString(36).slice(2, 8)}`;

        container.innerHTML = `
            <svg class="chart-svg" viewBox="0 0 ${width} ${height}" width="100%" height="${height}"
                 role="img" aria-label="${escapeHtml(colors.titel || "Verlauf")}">
                <defs>
                    <linearGradient id="${kennung}" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stop-color="${colors.line}" stop-opacity="0.28"></stop>
                        <stop offset="100%" stop-color="${colors.line}" stop-opacity="0"></stop>
                    </linearGradient>
                </defs>
                ${gitter}
                <path class="chart-area" d="${flaeche}" fill="url(#${kennung})"></path>
                <path class="chart-line" d="${linie}" stroke="${colors.line}"></path>
                ${punktKnoten}
                ${xBeschriftung}
                <line class="chart-crosshair" x1="0" y1="${oben}" x2="0" y2="${oben + plotH}" style="opacity:0"></line>
                <circle class="chart-cursor" r="5" style="opacity:0" fill="${colors.line}"></circle>
                <rect class="chart-hitbox" x="${links}" y="${oben}" width="${plotB}" height="${plotH}"
                      fill="transparent"></rect>
            </svg>
            <div class="chart-tooltip" hidden></div>
        `;

        verdrahteZeiger(container, punkte, { links, plotB, oben, plotH });
    }

    /*
     * Der Zeiger springt auf den naechstgelegenen Messwert, statt zwischen
     * ihnen zu schweben. Ein Tooltip, der einen Wert zeigt, den es nicht gibt,
     * ist schlimmer als keiner.
     */
    function verdrahteZeiger(container, punkte, mass) {
        const svg = container.querySelector(".chart-svg");
        const tooltip = container.querySelector(".chart-tooltip");
        const kreuz = container.querySelector(".chart-crosshair");
        const cursor = container.querySelector(".chart-cursor");
        const flaeche = container.querySelector(".chart-hitbox");
        if (!svg || !flaeche) {
            return;
        }

        function verstecken() {
            tooltip.hidden = true;
            kreuz.style.opacity = "0";
            cursor.style.opacity = "0";
        }

        function bewegen(ereignis) {
            const kasten = svg.getBoundingClientRect();
            const zeigerX = ereignis.clientX ?? ereignis.touches?.[0]?.clientX;
            if (zeigerX === undefined) {
                return;
            }
            // Von Bildschirm- auf Zeichenkoordinaten. Ohne diese Umrechnung
            // waere der Tooltip auf jedem Bildschirm anders verschoben.
            const x = ((zeigerX - kasten.left) / kasten.width) * svg.viewBox.baseVal.width;

            let naechster = punkte[0];
            for (const p of punkte) {
                if (Math.abs(p.x - x) < Math.abs(naechster.x - x)) {
                    naechster = p;
                }
            }

            kreuz.setAttribute("x1", naechster.x.toFixed(1));
            kreuz.setAttribute("x2", naechster.x.toFixed(1));
            kreuz.style.opacity = "1";
            cursor.setAttribute("cx", naechster.x.toFixed(1));
            cursor.setAttribute("cy", naechster.y.toFixed(1));
            cursor.style.opacity = "1";

            tooltip.hidden = false;
            tooltip.innerHTML = `<strong>${escapeHtml(naechster.meta)}</strong>`
                + `<span>${escapeHtml(naechster.label)}</span>`;

            // Am Rand kippt der Tooltip auf die andere Seite, sonst haengt er
            // halb ausserhalb der Karte.
            const anteil = naechster.x / svg.viewBox.baseVal.width;
            tooltip.style.left = `${(anteil * 100).toFixed(2)}%`;
            tooltip.style.transform = anteil > 0.75
                ? "translate(-100%, -50%)"
                : (anteil < 0.25 ? "translate(0, -50%)" : "translate(-50%, -50%)");
        }

        flaeche.addEventListener("mousemove", bewegen);
        flaeche.addEventListener("touchmove", bewegen, { passive: true });
        svg.addEventListener("mouseleave", verstecken);
        flaeche.addEventListener("touchend", verstecken);
    }

    /*
     * Eine runde Obergrenze fuer die Achse. Endet die Skala bei 8437, liest
     * niemand die Zwischenwerte ab; bei 10000 kann man es im Kopf.
     */
    function schoeneObergrenze(wert, alsZeit) {
        if (wert <= 0) {
            return 1;
        }

        // Zeit rechnet nicht dezimal. 60, 300, 900, 1800 Sekunden sind runde
        // Werte; 10000 ist es nur auf dem Papier.
        if (alsZeit) {
            const stufen = [60, 300, 900, 1800, 3600, 7200, 10800, 21600, 43200,
                            86400, 172800, 604800, 2592000];
            for (const stufe of stufen) {
                if (wert <= stufe) {
                    return stufe;
                }
            }
            return Math.ceil(wert / 2592000) * 2592000;
        }
        const groesse = Math.pow(10, Math.floor(Math.log10(wert)));
        const rest = wert / groesse;
        const stufe = rest <= 1 ? 1 : (rest <= 2 ? 2 : (rest <= 5 ? 5 : 10));
        return stufe * groesse;
    }

    /*
     * Dieselben Zahlen als Tabelle.
     *
     * Nicht als Zugabe, sondern als Pflichtteil: ein Diagramm ist fuer alle
     * unbrauchbar, die es nicht sehen koennen, und im hellen Modus liegt der
     * gruene Ton dicht genug an der weissen Flaeche, dass die Zahlen auch
     * woertlich lesbar sein muessen. Eingeklappt, damit sie niemanden stoert,
     * der sie nicht braucht.
     */
    /*
     * Die Audio-Knoten.
     *
     * Eigener, magerer Endpunkt: Name, Stufe, erreichbar, Auslastung. Der
     * Adminbereich zeigt mehr - Adresse, Strafpunkte, welche Server wo liegen -
     * und genau das gehoert auf eine Seite ohne Anmeldung nicht.
     */
    async function ladeKnoten() {
        if (!nodeListe) {
            return;
        }
        let knoten = [];
        try {
            const antwort = await fetch("/api/public/nodes", { headers: { Accept: "application/json" } });
            if (antwort.ok) {
                knoten = await antwort.json();
            }
        } catch (fehler) {
            // Die Statusseite soll auch dann etwas zeigen, wenn ausgerechnet
            // diese eine Abfrage klemmt.
        }

        nodeLeer.hidden = knoten.length > 0;
        const erreichbar = knoten.filter((k) => k.erreichbar).length;
        setBadge(nodeStatusBadge,
            knoten.length === 0 ? "—" : `${erreichbar} von ${knoten.length} erreichbar`,
            erreichbar === knoten.length && knoten.length > 0 ? "success" : "muted");

        nodeListe.innerHTML = knoten.map((k) => {
            const last = Math.min(100, Math.round((k.cpuLast || 0) * 100));
            return `
            <article class="node-karte ${k.erreichbar ? "" : "ist-weg"}">
                <div class="node-kopf">
                    <span class="node-name">${escapeHtml(k.name)}</span>
                    <span class="node-ampel ${k.erreichbar ? "" : "ist-weg"}"
                          title="${k.erreichbar ? "erreichbar" : "antwortet nicht"}"></span>
                </div>
                <div class="node-werte">
                    <span class="node-wert"><span>Stufe</span><strong>${escapeHtml(k.stufe || "—")}</strong></span>
                    <span class="node-wert"><span>Spielt</span><strong>${number(k.spielend)}</strong></span>
                    <span class="node-wert"><span>Server</span><strong>${number(k.server)}</strong></span>
                    <span class="node-wert"><span>Laufzeit</span><strong>${escapeHtml(laufzeit(k.laufzeitSekunden))}</strong></span>
                </div>
                <div class="node-balken">
                    <div class="node-balken-fuell ${last > 85 ? "ist-heiss" : ""}" style="width:${last}%"></div>
                </div>
            </article>`;
        }).join("");
    }

    function laufzeit(sekunden) {
        const s = Number(sekunden || 0);
        if (s <= 0) return "—";
        const tage = Math.floor(s / 86400);
        if (tage > 0) return `${tage} d`;
        const stunden = Math.floor(s / 3600);
        if (stunden > 0) return `${stunden} h`;
        return `${Math.floor(s / 60)} min`;
    }

    function fuelleTabelle(points) {
        const koerper = document.getElementById("chartTableBody");
        const zahl = document.getElementById("chartTableCount");
        if (!koerper) {
            return;
        }
        koerper.innerHTML = points.map((p) => `
            <tr>
                <td>${escapeHtml(p.label)}</td>
                <td>${escapeHtml(formatDuration(Number(p.listenedSeconds || 0)))}</td>
                <td>${escapeHtml(number(p.uniqueListeners))}</td>
            </tr>`).join("");
        if (zahl) {
            zahl.textContent = points.length === 1 ? "1 Messpunkt" : `${points.length} Messpunkte`;
        }
    }

    function renderChartEmpty(container, message) {
        if (!container) {
            return;
        }
        container.innerHTML = `<div class="chart-empty">${escapeHtml(message)}</div>`;
    }

    function updateRangeButtons() {
        rangeTabs.forEach((tab) => {
            tab.classList.toggle("active", tab.dataset.range === currentRange);
        });
    }

    function setText(node, value) {
        if (node) {
            node.textContent = value;
        }
    }

    function setBadge(node, text, tone) {
        if (!node) {
            return;
        }
        node.textContent = text;
        node.className = `badge ${tone}`;
    }

    function createBadge(text, tone) {
        const badge = document.createElement("span");
        badge.className = `badge ${tone}`;
        badge.textContent = text;
        return badge;
    }

    function number(value) {
        return new Intl.NumberFormat("de-DE").format(Number(value || 0));
    }

    function formatDuration(seconds) {
        const totalSeconds = Number(seconds || 0);
        if (totalSeconds <= 0) {
            return "0 min";
        }
        const days = Math.floor(totalSeconds / 86400);
        const hours = Math.floor((totalSeconds % 86400) / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);

        if (days > 0) {
            return `${days} T ${hours} h`;
        }
        if (hours > 0) {
            // Volle Stunde ohne "00 min" dahinter. Das Aufrunden auf mindestens
            // eine Minute stand frueher weiter oben und galt fuer alle Werte -
            // damit wurde aus einer glatten Achsenmarke bei 6 Stunden ein
            // "6 h 01 min", also eine Zahl, an der man nichts ablesen kann.
            return minutes > 0 ? `${hours} h ${String(minutes).padStart(2, "0")} min` : `${hours} h`;
        }
        if (minutes > 0) {
            return `${minutes} min`;
        }
        // Weniger als eine Minute, aber mehr als nichts: nicht auf "0 min"
        // abrunden - das laese sich nicht von "gar nichts" unterscheiden.
        return "unter 1 min";
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#39;");
    }

    /*
     * Beim Groessenwechsel neu zeichnen.
     *
     * Frueher war das nicht noetig, weil das SVG gedehnt wurde - genau der
     * Trick, der die Linienstaerken verzerrt hat. Wer misst, muss neu messen.
     * Mit Verzoegerung, sonst rechnet die Seite waehrend des Ziehens am
     * Fensterrand hundertmal.
     */
    let groessenHandle = null;
    window.addEventListener("resize", () => {
        window.clearTimeout(groessenHandle);
        groessenHandle = window.setTimeout(() => {
            [streamTimeChart, listenerChart].forEach((behaelter) => {
                if (behaelter && behaelter._daten) {
                    renderLineChart(behaelter, behaelter._daten, behaelter._farben);
                }
            });
        }, 180);
    });

    init();
})();
