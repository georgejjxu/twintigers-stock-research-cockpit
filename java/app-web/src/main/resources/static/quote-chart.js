// Stock Research Cockpit: the quote-page chart on Stock Detail.
// Ranges 1D–Max or from/to dates; candle / line / area; 50- and 200-day averages; volume panel; log scale;
// drag across the chart to zoom, mouse wheel zoom (Shift + wheel pans), + / − / ◀ / ▶ / Reset; an overview strip
// with a draggable window; a price readout under the cursor; signal markers and "why it fired" (daily views);
// delayed intraday bars for 1D and 5D, refreshed every minute while the session moves.
(function () {
    "use strict";

    var RANK = { info: 0, watch: 1, warning: 2 };
    var FADE = "#cfd8db";

    function $(id) { return document.getElementById(id); }
    function text(el, s) { if (el) { el.textContent = s; } }
    function make(tag, cls, txt) {
        var n = document.createElement(tag);
        if (cls) { n.className = cls; }
        if (txt !== undefined) { n.textContent = txt; }
        return n;
    }
    function fmt(v, digits) {
        if (v === null || v === undefined || isNaN(v)) { return "–"; }
        return Number(v).toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
    }
    function fmtVol(v) {
        if (!v) { return "–"; }
        if (v >= 1e9) { return (v / 1e9).toFixed(2) + "B"; }
        if (v >= 1e6) { return (v / 1e6).toFixed(2) + "M"; }
        if (v >= 1e3) { return (v / 1e3).toFixed(1) + "K"; }
        return String(v);
    }
    function timeKey(t) {
        if (typeof t === "string" || typeof t === "number") { return t; }
        return t.year + "-" + String(t.month).padStart(2, "0") + "-" + String(t.day).padStart(2, "0");
    }
    function timeLabel(t) {
        if (typeof t === "string") { return t; }
        var d = new Date(t * 1000); // exchange wall clock shifted into UTC
        return d.toISOString().slice(0, 10) + " " + d.toISOString().slice(11, 16);
    }

    function QuoteChart(root) {
        var LWC = window.LightweightCharts;
        var el = $("chart");
        var symbol = root.dataset.symbol;
        var state = { range: "1Y", from: null, to: null, bars: null, type: "candle", data: null, focus: null };
        var chart = null, mini = null, main = null, refresh = null;
        var markersPlugin = null, shown = [], focusLines = [];
        var index = {};

        // ---------- loading ----------
        function url() {
            var q = state.from ? "from=" + encodeURIComponent(state.from) + (state.to ? "&to=" + encodeURIComponent(state.to) : "")
                               : "range=" + encodeURIComponent(state.range);
            if (state.bars) { q += "&bars=" + encodeURIComponent(state.bars); }
            return "/api/chart/" + encodeURIComponent(symbol) + "?" + q;
        }

        function load(keepRange) {
            return fetch(url(), { headers: { "Accept": "application/json" } })
                .then(function (r) { return r.json(); })
                .then(function (d) {
                    if (d.error) { throw new Error(d.error); }
                    state.data = d;
                    controls(d);
                    render(keepRange);
                    schedule(d);
                    return d;
                })
                .catch(function (e) {
                    destroy();
                    text(el, "Chart unavailable: " + e.message);
                });
        }

        function schedule(d) {
            if (refresh) { clearInterval(refresh); refresh = null; }
            if (d.mode === "intraday" && d.live) {
                refresh = setInterval(function () { if (!document.hidden && !state.focus) { load(true); } }, 60000);
            }
        }

        function controls(d) {
            var intraday = d.mode === "intraday";
            root.querySelectorAll("[data-range]").forEach(function (b) {
                b.setAttribute("aria-pressed", String(!state.from && b.dataset.range === state.range));
            });
            var bw = $("qc-bars-wrap");
            if (bw) { bw.hidden = !intraday; }
            if (intraday && $("qc-bars")) { $("qc-bars").value = d.interval; }
            var mw = $("qc-markers-wrap");
            if (mw) { mw.hidden = intraday; }
            root.querySelectorAll(".qc-sma50, .qc-sma200").forEach(function (n) { n.hidden = intraday; });
            var f = $("qc-dates");
            if (f && d.from) {
                f.elements.from.value = String(d.from).slice(0, 10);
                f.elements.to.value = String(d.to).slice(0, 10);
            }
            text($("chart-asof"), d.source + (d.delayMinutes ? " · delayed about " + d.delayMinutes + " min" : "")
                + (d.lastBar ? " · last bar " + d.lastBar + (intraday ? " (" + String(d.zone || "").split("/").pop().replace("_", " ") + " time)" : "") : "")
                + (intraday ? (d.live ? " · refreshing every minute" : " · market closed or quiet") : ""));
        }

        // ---------- drawing ----------
        function destroy() {
            if (chart) { chart.remove(); chart = null; }
            if (mini) { mini.remove(); mini = null; }
            el.replaceChildren();
            $("qc-overview").replaceChildren();
            main = null;
            markersPlugin = null;
            focusLines = [];
        }

        function opt(id) { var n = $(id); return n ? n.checked : false; }

        function render(keepRange) {
            var d = state.data;
            if (!d) { return; }
            var saved = keepRange && chart ? chart.timeScale().getVisibleLogicalRange() : null;
            destroy();
            if (!d.candles || d.candles.length === 0) { text(el, "No bars for " + d.symbol + " in this period."); return; }
            var c = d.colors;
            var intraday = d.mode === "intraday";
            index = {};
            d.candles.forEach(function (b, i) { index[b.time] = i; });

            chart = LWC.createChart(el, {
                autoSize: true,
                layout: { background: { type: "solid", color: "#ffffff" }, textColor: c.text,
                          fontFamily: getComputedStyle(document.body).fontFamily, panes: { separatorColor: c.grid } },
                grid: { vertLines: { color: "#eef2f3" }, horzLines: { color: "#eef2f3" } },
                rightPriceScale: { borderColor: c.grid, mode: opt("qc-log") ? LWC.PriceScaleMode.Logarithmic : LWC.PriceScaleMode.Normal },
                timeScale: { borderColor: c.grid, timeVisible: intraday, secondsVisible: false, rightOffset: 2 },
                crosshair: { mode: LWC.CrosshairMode.Normal },
                handleScroll: { mouseWheel: true, pressedMouseMove: false, horzTouchDrag: true, vertTouchDrag: false },
                handleScale: { mouseWheel: true, pinch: true, axisPressedMouseMove: true, axisDoubleClickReset: true }
            });

            var closes = d.candles.map(function (b) { return { time: b.time, value: b.close }; });
            if (state.type === "line") {
                main = chart.addSeries(LWC.LineSeries, { color: c.line, lineWidth: 2 });
                main.setData(closes);
            } else if (state.type === "area") {
                main = chart.addSeries(LWC.AreaSeries, { lineColor: c.line, topColor: c.line + "55", bottomColor: c.line + "05", lineWidth: 2 });
                main.setData(closes);
            } else {
                main = chart.addSeries(LWC.CandlestickSeries, { upColor: c.up, downColor: c.down, borderUpColor: c.up,
                    borderDownColor: c.down, wickUpColor: c.up, wickDownColor: c.down });
                main.setData(d.candles);
            }
            if (!intraday && opt("qc-sma50") && d.sma50.length) {
                chart.addSeries(LWC.LineSeries, { color: c.sma50, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
                    crosshairMarkerVisible: false, title: "50d" }).setData(d.sma50);
            }
            if (!intraday && opt("qc-sma200") && d.sma200.length) {
                chart.addSeries(LWC.LineSeries, { color: c.sma200, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
                    crosshairMarkerVisible: false, title: "200d" }).setData(d.sma200);
            }
            if (intraday && d.vwap.length) {
                chart.addSeries(LWC.LineSeries, { color: c.vwap, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
                    crosshairMarkerVisible: false, title: "VWAP" }).setData(d.vwap);
            }
            if (intraday && d.prevClose) {
                main.createPriceLine({ price: d.prevClose, color: "#7b878a", lineWidth: 1, lineStyle: LWC.LineStyle.Dotted,
                                       axisLabelVisible: true, title: "prev close" });
            }
            d.alertLines.forEach(function (l) {
                main.createPriceLine({ price: l.price, color: l.color, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed,
                                       axisLabelVisible: true, title: l.title });
            });
            if (opt("qc-volume")) {
                chart.addSeries(LWC.HistogramSeries, { priceFormat: { type: "volume" }, lastValueVisible: false,
                    priceLineVisible: false }, 1).setData(d.volume);
                var panes = chart.panes();
                if (panes.length > 1) { panes[1].setHeight(90); }
            }
            markersPlugin = LWC.createSeriesMarkers(main, []);
            applyFilter();

            chart.subscribeCrosshairMove(readout);
            chart.timeScale().subscribeVisibleLogicalRangeChange(syncWindow);
            if (saved) {
                chart.timeScale().setVisibleLogicalRange(saved);
            } else {
                chart.timeScale().fitContent();
                // the chart takes its final width a moment later (autoSize); fit again then
                var thisChart = chart;
                setTimeout(function () { if (chart === thisChart) { chart.timeScale().fitContent(); } }, 120);
            }
            overview();
            readout(null);
            el.dataset.rendered = d.mode;
            el.dataset.bars = String(d.candles.length);
            el.dataset.type = state.type;
        }

        function applyFilter() {
            var d = state.data;
            if (!markersPlugin || !d) { return; }
            if (d.mode !== "daily") { markersPlugin.setMarkers([]); shown = []; text($("marker-count"), ""); return; }
            var f = $("marker-filter");
            var min = RANK[f ? f.value : "watch"];
            shown = d.markers.filter(function (m) { return RANK[m.severity] >= min; });
            markersPlugin.setMarkers(shown.map(function (m) {
                return { time: m.time, position: m.position, color: m.color, shape: m.shape, text: m.text,
                         size: m.severity === "info" ? 0.6 : 1 };
            }));
            text($("marker-count"), "· " + shown.length + " of " + d.markers.length + " signals shown");
            el.dataset.markers = String(shown.length);
        }

        // ---------- readout ----------
        function readout(p) {
            var box = $("qc-readout");
            var d = state.data;
            if (!box || !d || !d.candles.length) { return; }
            var key = p && p.time !== undefined ? timeKey(p.time) : d.candles[d.candles.length - 1].time;
            var i = index[key];
            if (i === undefined) { return; }
            var b = d.candles[i];
            var prev = i > 0 ? d.candles[i - 1].close : (d.prevClose || null);
            var chg = prev ? b.close - prev : null;
            var pct = prev ? (b.close / prev - 1) * 100 : null;
            var vol = d.volume[i] ? d.volume[i].value : null;
            box.replaceChildren();
            box.appendChild(make("strong", null, timeLabel(b.time)));
            [["O", fmt(b.open, 2)], ["H", fmt(b.high, 2)], ["L", fmt(b.low, 2)], ["C", fmt(b.close, 2)]].forEach(function (x) {
                var s = make("span", null, x[0] + " ");
                s.appendChild(make("b", null, x[1]));
                box.appendChild(s);
            });
            if (chg !== null) {
                box.appendChild(make("span", chg < 0 ? "cockpit-neg" : "cockpit-pos",
                    (chg >= 0 ? "+" : "") + fmt(chg, 2) + " (" + (pct >= 0 ? "+" : "") + fmt(pct, 2) + "%)"));
            }
            box.appendChild(make("span", null, "Vol " + fmtVol(vol)));
            function lineValue(arr, label, cls) {
                for (var k = 0; k < arr.length; k++) {
                    if (arr[k].time === key) { box.appendChild(make("span", cls, label + " " + fmt(arr[k].value, 2))); return; }
                }
            }
            if (d.mode === "daily") {
                if (opt("qc-sma50")) { lineValue(d.sma50, "50d", "qc-r50"); }
                if (opt("qc-sma200")) { lineValue(d.sma200, "200d", "qc-r200"); }
            } else {
                lineValue(d.vwap, "VWAP", "qc-rvwap");
            }
            shown.filter(function (m) { return m.time === key; }).forEach(function (m) {
                box.appendChild(make("span", "qc-rsig " + m.severity, m.rule));
            });
        }

        // ---------- zoom and pan ----------
        function zoom(kind) {
            if (!chart) { return; }
            var ts = chart.timeScale();
            if (kind === "reset") { ts.fitContent(); if (mini) { mini.timeScale().fitContent(); } return; }
            var r = ts.getVisibleLogicalRange();
            if (!r) { return; }
            var width = r.to - r.from;
            var mid = (r.from + r.to) / 2;
            if (kind === "in" || kind === "out") {
                var half = Math.max(3, width * (kind === "in" ? 0.35 : 0.7));
                ts.setVisibleLogicalRange({ from: mid - half, to: mid + half });
            } else {
                var shift = width * 0.3 * (kind === "left" ? -1 : 1);
                ts.setVisibleLogicalRange({ from: r.from + shift, to: r.to + shift });
            }
        }

        // Drag across the chart: a shaded band follows the mouse; on release the chart zooms to it.
        (function dragZoom() {
            var band = $("qc-select");
            var start = null;
            function x(ev) { return ev.clientX - el.getBoundingClientRect().left; }
            function plotWidth() { return chart ? el.clientWidth - chart.priceScale("right").width() : el.clientWidth; }
            el.addEventListener("mousedown", function (ev) {
                if (ev.button !== 0 || !chart || x(ev) > plotWidth()) { return; }
                start = x(ev);
                band.style.left = start + "px";
                band.style.width = "0px";
                band.hidden = false;
                ev.preventDefault();
            });
            window.addEventListener("mousemove", function (ev) {
                if (start === null) { return; }
                var now = Math.max(0, Math.min(plotWidth(), x(ev)));
                band.style.left = Math.min(start, now) + "px";
                band.style.width = Math.abs(now - start) + "px";
            });
            window.addEventListener("mouseup", function (ev) {
                if (start === null) { return; }
                var end = Math.max(0, Math.min(plotWidth(), x(ev)));
                band.hidden = true;
                var a = Math.min(start, end), b = Math.max(start, end);
                start = null;
                if (b - a < 8 || !chart) { return; }
                var ts = chart.timeScale();
                var from = ts.coordinateToLogical(a), to = ts.coordinateToLogical(b);
                if (from !== null && to !== null && to - from >= 2) { ts.setVisibleLogicalRange({ from: from, to: to }); }
            });
        })();

        // ---------- overview strip ----------
        function overview() {
            var box = $("qc-overview");
            var d = state.data;
            var c = d.colors;
            mini = LWC.createChart(box, {
                autoSize: true,
                layout: { background: { type: "solid", color: "#f7f9fa" }, textColor: c.text, attributionLogo: false },
                grid: { vertLines: { visible: false }, horzLines: { visible: false } },
                rightPriceScale: { visible: false }, leftPriceScale: { visible: false },
                timeScale: { visible: false, borderVisible: false },
                crosshair: { vertLine: { visible: false }, horzLine: { visible: false } },
                handleScroll: false, handleScale: false
            });
            mini.addSeries(LWC.AreaSeries, { lineColor: c.line, topColor: c.line + "40", bottomColor: c.line + "05", lineWidth: 1,
                priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false })
                .setData(d.candles.map(function (b) { return { time: b.time, value: b.close }; }));
            mini.timeScale().fitContent();
            var thisMini = mini;
            setTimeout(function () { if (mini === thisMini) { mini.timeScale().fitContent(); syncWindow(); } }, 150);
        }

        function syncWindow() {
            var w = $("qc-window");
            if (!chart || !mini || !w) { return; }
            var r = chart.timeScale().getVisibleLogicalRange();
            if (!r) { w.hidden = true; return; }
            var n = state.data.candles.length;
            var a = mini.timeScale().logicalToCoordinate(Math.max(0, r.from));
            var b = mini.timeScale().logicalToCoordinate(Math.min(n - 1, r.to));
            if (a === null || b === null) { w.hidden = true; return; }
            var full = $("qc-overview").clientWidth;
            a = Math.max(0, Math.min(full, a));
            b = Math.max(0, Math.min(full, b));
            w.hidden = false;
            w.style.left = a + "px";
            w.style.width = Math.max(6, b - a) + "px";
        }

        (function overviewDrag() {
            var w = $("qc-window");
            var box = $("qc-overview");
            if (!w || !box) { return; }
            var mode = null, startX = 0, startRange = null;
            function logicalAt(px) { return mini ? mini.timeScale().coordinateToLogical(px) : null; }
            w.addEventListener("mousedown", function (ev) {
                if (!chart || !mini) { return; }
                mode = ev.target.classList.contains("left") ? "left" : ev.target.classList.contains("right") ? "right" : "move";
                startX = ev.clientX;
                startRange = chart.timeScale().getVisibleLogicalRange();
                ev.preventDefault();
                ev.stopPropagation();
            });
            box.addEventListener("mousedown", function (ev) {
                // click on the strip outside the window: centre the window there
                if (!chart || !mini || ev.target !== box && !box.contains(ev.target)) { return; }
                var r = chart.timeScale().getVisibleLogicalRange();
                var at = logicalAt(ev.clientX - box.getBoundingClientRect().left);
                if (!r || at === null) { return; }
                var half = (r.to - r.from) / 2;
                chart.timeScale().setVisibleLogicalRange({ from: at - half, to: at + half });
            });
            window.addEventListener("mousemove", function (ev) {
                if (!mode || !startRange) { return; }
                var l0 = logicalAt(startX - box.getBoundingClientRect().left);
                var l1 = logicalAt(ev.clientX - box.getBoundingClientRect().left);
                if (l0 === null || l1 === null) { return; }
                var dl = l1 - l0;
                var r = { from: startRange.from, to: startRange.to };
                if (mode === "move") { r.from += dl; r.to += dl; }
                if (mode === "left") { r.from = Math.min(r.to - 3, r.from + dl); }
                if (mode === "right") { r.to = Math.max(r.from + 3, r.to + dl); }
                chart.timeScale().setVisibleLogicalRange(r);
            });
            window.addEventListener("mouseup", function () { mode = null; startRange = null; });
        })();

        // ---------- signals: "why it fired" ----------
        function clearFocus() {
            state.focus = null;
            focusLines.forEach(function (l) { if (main) { main.removePriceLine(l); } });
            focusLines = [];
            var box = $("signal-explain");
            if (box) { box.hidden = true; box.replaceChildren(); }
            document.querySelectorAll(".cockpit-signal-row.active").forEach(function (r) { r.classList.remove("active"); });
            delete el.dataset.focus;
        }

        function focusSignal(time, rule) {
            var d = state.data;
            var m = d && d.mode === "daily" ? d.markers.filter(function (x) { return x.time === time && x.id === rule; })[0] : null;
            if (!m) { return false; }
            clearFocus();
            state.focus = time + " " + rule;
            var ex = m.explain;
            var used = {};
            ex.days.forEach(function (t) { used[t] = true; });
            if (state.type === "candle") {
                main.setData(d.candles.map(function (b) {
                    return used[b.time] ? b : { time: b.time, open: b.open, high: b.high, low: b.low, close: b.close,
                                                color: FADE, borderColor: FADE, wickColor: FADE };
                }));
            }
            focusLines = ex.lines.map(function (l) {
                return main.createPriceLine({ price: l.price, color: d.colors.text, lineWidth: 1, lineStyle: LWC.LineStyle.Dotted,
                                              axisLabelVisible: true, title: l.title });
            });
            shown = [m];
            markersPlugin.setMarkers([{ time: m.time, position: m.position, color: m.color, shape: m.shape, text: m.text, size: 1.5 }]);
            text($("marker-count"), "· showing this signal only");
            var idx = index[time];
            var first = idx;
            ex.days.forEach(function (t) { if (index[t] !== undefined && index[t] < first) { first = index[t]; } });
            if (idx !== undefined) {
                chart.timeScale().setVisibleLogicalRange({ from: Math.max(0, Math.min(idx - 25, first - 3)), to: idx + 12 });
            }
            var box = $("signal-explain");
            if (box) {
                box.appendChild(make("h3", "cockpit-subhead", "Why " + m.rule + " fired on " + m.time));
                box.appendChild(make("p", "cockpit-explain-why", ex.why));
                if (ex.definition) { box.appendChild(make("p", "muted small", "Rule: " + ex.definition)); }
                box.appendChild(make("p", "muted small", "On the chart: the " + ex.days.length + " highlighted day"
                    + (ex.days.length === 1 ? " is" : "s are") + " what the rule looked at"
                    + (ex.lines.length ? "; dotted lines are the levels it compared against (" +
                       ex.lines.map(function (l) { return l.title; }).join(", ") + ")" : "") + "."));
                var table = make("table", "cockpit-explain-facts");
                var body = make("tbody");
                ex.facts.forEach(function (f) {
                    var tr = make("tr");
                    tr.appendChild(make("th", null, f[0]));
                    tr.appendChild(make("td", "num", f[1]));
                    body.appendChild(tr);
                });
                table.appendChild(body);
                box.appendChild(table);
                box.appendChild(make("p", "muted small", "A signal describes what price and volume did; it is not a prediction."));
                var back = make("button", "secondary-button", "Show the whole chart");
                back.type = "button";
                back.addEventListener("click", function () { clearFocus(); render(false); });
                box.appendChild(back);
                box.hidden = false;
            }
            el.dataset.focus = state.focus;
            el.scrollIntoView({ behavior: "smooth", block: "center" });
            return true;
        }

        // ---------- wiring ----------
        root.querySelectorAll("[data-range]").forEach(function (b) {
            b.addEventListener("click", function () {
                clearFocus();
                state.range = b.dataset.range;
                state.from = null;
                state.to = null;
                state.bars = null;
                load(false);
            });
        });
        var dates = $("qc-dates");
        if (dates) {
            dates.addEventListener("submit", function (ev) {
                ev.preventDefault();
                clearFocus();
                state.from = dates.elements.from.value;
                state.to = dates.elements.to.value || null;
                state.bars = null;
                load(false);
            });
        }
        root.querySelectorAll("[data-type]").forEach(function (b) {
            b.addEventListener("click", function () {
                clearFocus();
                state.type = b.dataset.type;
                root.querySelectorAll("[data-type]").forEach(function (x) { x.setAttribute("aria-pressed", String(x === b)); });
                render(true);
            });
        });
        ["qc-sma50", "qc-sma200", "qc-volume", "qc-log"].forEach(function (id) {
            var n = $(id);
            if (n) { n.addEventListener("change", function () { clearFocus(); render(true); }); }
        });
        var bars = $("qc-bars");
        if (bars) { bars.addEventListener("change", function () { state.bars = bars.value; load(false); }); }
        var filter = $("marker-filter");
        if (filter) { filter.addEventListener("change", function () { if (state.focus) { clearFocus(); render(true); } else { applyFilter(); } }); }
        root.querySelectorAll("[data-zoom]").forEach(function (b) {
            b.addEventListener("click", function () { zoom(b.dataset.zoom); });
        });
        el.addEventListener("keydown", function (ev) {
            var k = { "+": "in", "=": "in", "-": "out", "ArrowLeft": "left", "ArrowRight": "right", "0": "reset" }[ev.key];
            if (k) { ev.preventDefault(); zoom(k); }
        });
        el.tabIndex = 0;

        // Signal rows: show the signal on the daily chart (loading 1Y daily first if needed).
        document.querySelectorAll(".cockpit-signal-row").forEach(function (row) {
            function go() {
                if (focusSignal(row.dataset.time, row.dataset.rule)) { row.classList.add("active"); return; }
                state.range = "1Y";
                state.from = null;
                state.to = null;
                state.bars = null;
                state.type = "candle";
                root.querySelectorAll("[data-type]").forEach(function (x) { x.setAttribute("aria-pressed", String(x.dataset.type === "candle")); });
                load(false).then(function () {
                    if (focusSignal(row.dataset.time, row.dataset.rule)) { row.classList.add("active"); }
                });
            }
            row.addEventListener("click", go);
            row.addEventListener("keydown", function (ev) { if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); go(); } });
        });

        // for checks from the browser console: the visible range of bars
        root.visibleRange = function () { return chart ? chart.timeScale().getVisibleLogicalRange() : null; };
        if (!LWC) { text(el, "Chart library not loaded."); return; }
        load(false);
    }

    document.addEventListener("DOMContentLoaded", function () {
        var root = $("qc");
        if (root) { QuoteChart(root); }
    });
})();
