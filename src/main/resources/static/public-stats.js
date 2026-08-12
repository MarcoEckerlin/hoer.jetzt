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

    function currentChartColors() {
        const accent = readColor("--accent", "#5865f2");
        const success = readColor("--success", "#23a559");
        return {
            stream: {
                line: accent,
                area: readColor("--accent-soft", "rgba(88, 101, 242, 0.16)"),
                point: accent
            },
            listeners: {
                line: success,
                area: readColor("--success-soft", "rgba(35, 165, 89, 0.16)"),
                point: success
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

        summaryRefreshHandle = window.setInterval(() => {
            // Ein Tab im Hintergrund muss den Server nicht befragen.
            if (!document.hidden) {
                refreshSummary();
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
                emptyText: "Noch keine gestreamte Zeit im gewählten Zeitraum."
            }
        );

        renderLineChart(
            listenerChart,
            points.map((point) => ({
                label: point.label,
                value: Number(point.uniqueListeners || 0),
                meta: `${number(point.uniqueListeners)} Hörer`
            })),
            {
                ...chartColors.listeners,
                emptyText: "Noch keine Hörer im gewählten Zeitraum."
            }
        );
    }

    function renderLineChart(container, points, colors) {
        if (!container) {
            return;
        }

        if (!Array.isArray(points) || points.length === 0 || points.every((point) => point.value <= 0)) {
            renderChartEmpty(container, colors.emptyText);
            return;
        }

        const width = 820;
        const height = 260;
        const paddingLeft = 38;
        const paddingRight = 16;
        const paddingTop = 16;
        const paddingBottom = 34;
        const plotWidth = width - paddingLeft - paddingRight;
        const plotHeight = height - paddingTop - paddingBottom;
        const maxValue = Math.max(...points.map((point) => point.value), 1);
        const stepX = points.length > 1 ? plotWidth / (points.length - 1) : 0;

        const chartPoints = points.map((point, index) => {
            const x = paddingLeft + stepX * index;
            const y = paddingTop + plotHeight - ((point.value / maxValue) * plotHeight);
            return { ...point, x, y };
        });

        const linePath = chartPoints
            .map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`)
            .join(" ");
        const areaPath = `${linePath} L ${chartPoints[chartPoints.length - 1].x.toFixed(2)} ${(paddingTop + plotHeight).toFixed(2)} L ${chartPoints[0].x.toFixed(2)} ${(paddingTop + plotHeight).toFixed(2)} Z`;

        const gridLines = [];
        for (let lineIndex = 0; lineIndex <= 4; lineIndex += 1) {
            const y = paddingTop + (plotHeight / 4) * lineIndex;
            gridLines.push(`<line class="chart-grid-line" x1="${paddingLeft}" y1="${y.toFixed(2)}" x2="${(paddingLeft + plotWidth).toFixed(2)}" y2="${y.toFixed(2)}"></line>`);
        }

        const pointNodes = chartPoints
            .map((point) => [
                `<circle class="chart-point" cx="${point.x.toFixed(2)}" cy="${point.y.toFixed(2)}" r="4" fill="${colors.point}">`,
                `<title>${escapeHtml(point.label)} · ${escapeHtml(point.meta)}</title>`,
                `</circle>`
            ].join(""))
            .join("");

        const labelRow = buildLabelRow(points);
        container.innerHTML = `
            <svg class="chart-svg" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="Statusdiagramm">
                ${gridLines.join("")}
                <path class="chart-area" d="${areaPath}" fill="${colors.area}"></path>
                <path class="chart-line" d="${linePath}" stroke="${colors.line}"></path>
                ${pointNodes}
            </svg>
            <div class="chart-label-row">${labelRow}</div>
        `;
    }

    function buildLabelRow(points) {
        const visibleLabels = [];
        const maxLabels = 6;
        if (points.length <= maxLabels) {
            points.forEach((point) => visibleLabels.push(point.label));
        } else {
            const step = (points.length - 1) / (maxLabels - 1);
            for (let index = 0; index < maxLabels; index += 1) {
                visibleLabels.push(points[Math.round(index * step)]?.label || "");
            }
        }
        return visibleLabels
            .map((label) => `<span>${escapeHtml(label)}</span>`)
            .join("");
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
        const minutes = Math.max(1, Math.floor((totalSeconds % 3600) / 60));
        if (days > 0) {
            return `${days} T ${hours} h`;
        }
        if (hours > 0) {
            return `${hours} h ${String(minutes).padStart(2, "0")} min`;
        }
        return `${minutes} min`;
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#39;");
    }

    init();
})();
