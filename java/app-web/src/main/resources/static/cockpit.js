// Stock Research Cockpit: the Performance chart and the symbol pickers (the Stock Detail chart is quote-chart.js). htmx handles the forms.
(function () {
    "use strict";


    function text(el, s) { el.textContent = s; }

    // Performance page: return compared with the S&P/TSX and the S&P 500 (in %, same period).
    var benchChart = null;
    function drawBench(d) {
        var el = document.getElementById("perf-bench");
        var LWC = window.LightweightCharts;
        if (!el || !LWC || !d.returnPct) { return; }
        if (benchChart) { benchChart.remove(); benchChart = null; }
        el.replaceChildren();
        benchChart = LWC.createChart(el, {
            autoSize: true,
            layout: { background: { type: "solid", color: "#ffffff" }, textColor: "#12212e",
                      fontFamily: getComputedStyle(document.body).fontFamily },
            grid: { vertLines: { color: "#eef2f3" }, horzLines: { color: "#eef2f3" } },
            rightPriceScale: { borderColor: "#d9e1e4" }, timeScale: { borderColor: "#d9e1e4" },
            localization: { priceFormatter: function (v) { return v.toFixed(2) + " %"; } }
        });
        benchChart.addSeries(LWC.LineSeries, { color: "#167b73", lineWidth: 2, title: "Your portfolio" }).setData(d.returnPct);
        var colors = ["#3268a8", "#e0a43b"];
        (d.benchmarks || []).forEach(function (b, i) {
            benchChart.addSeries(LWC.LineSeries, { color: colors[i % 2], lineWidth: 1, title: b.name }).setData(b.points);
        });
        benchChart.timeScale().fitContent();
    }

    // Performance page: holdings value and money put in, per period.
    function drawPerformance(el) {
        var LWC = window.LightweightCharts;
        if (!LWC) { text(el, "Chart library not loaded."); return; }
        var chart = null;
        var pick = document.getElementById("perf-range");
        var summary = document.getElementById("perf-summary");
        function load() {
            fetch("/api/performance/" + encodeURIComponent(pick ? pick.value : "max"), { headers: { "Accept": "application/json" } })
                .then(function (r) { return r.json(); })
                .then(function (d) {
                    if (d.error) { text(el, "Chart unavailable: " + d.error); return; }
                    if (chart) { chart.remove(); }
                    el.replaceChildren();
                    if (!d.value || d.value.length === 0) { text(el, "No holdings in this period."); return; }
                    chart = LWC.createChart(el, {
                        autoSize: true,
                        layout: { background: { type: "solid", color: "#ffffff" }, textColor: d.colors.text,
                                  fontFamily: getComputedStyle(document.body).fontFamily },
                        grid: { vertLines: { color: "#eef2f3" }, horzLines: { color: "#eef2f3" } },
                        rightPriceScale: { borderColor: d.colors.grid }, timeScale: { borderColor: d.colors.grid }
                    });
                    chart.addSeries(LWC.AreaSeries, { lineColor: d.colors.value, topColor: d.colors.value + "44",
                        bottomColor: d.colors.value + "05", lineWidth: 2, title: "Value" }).setData(d.value);
                    chart.addSeries(LWC.LineSeries, { color: d.colors.invested, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed,
                        title: "Money put in", priceLineVisible: false }).setData(d.invested);
                    chart.timeScale().fitContent();
                    drawBench(d);
                    if (summary) {
                        text(summary, d.from + " to " + d.to + ": price return " + (d.priceReturnPct === null ? "–" : d.priceReturnPct + " %")
                            + ", gain " + Number(d.gain).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
                    }
                    el.dataset.rendered = "true";
                })
                .catch(function (e) { text(el, "Chart unavailable: " + e.message); });
        }
        if (pick) { pick.addEventListener("change", load); }
        load();
    }

    // Markets page: Fear & Greed history (0-100) with the band edges as lines.
    function drawSentiment(el) {
        var LWC = window.LightweightCharts;
        if (!LWC) { return; }
        var points = JSON.parse(el.dataset.points || "[]");
        if (!points.length) { text(el, "No history."); return; }
        var chart = LWC.createChart(el, {
            autoSize: true,
            layout: { background: { type: "solid", color: "#ffffff" }, textColor: "#12212e",
                      fontFamily: getComputedStyle(document.body).fontFamily },
            grid: { vertLines: { color: "#eef2f3" }, horzLines: { color: "#eef2f3" } },
            rightPriceScale: { borderColor: "#d9e1e4" }, timeScale: { borderColor: "#d9e1e4" },
            handleScroll: false, handleScale: false
        });
        var line = chart.addSeries(LWC.AreaSeries, { lineColor: "#1f5f8b", topColor: "#1f5f8b33", bottomColor: "#1f5f8b05",
            lineWidth: 2, autoscaleInfoProvider: function () { return { priceRange: { minValue: 0, maxValue: 100 } }; } });
        line.setData(points);
        [[25, "extreme fear"], [45, "fear"], [55, "greed"], [75, "extreme greed"]].forEach(function (b) {
            line.createPriceLine({ price: b[0], color: "#b8b8b8", lineWidth: 1, lineStyle: LWC.LineStyle.Dotted,
                                   axisLabelVisible: false, title: b[1] });
        });
        chart.timeScale().fitContent();
    }

    document.addEventListener("DOMContentLoaded", function () {
        document.querySelectorAll(".mk-history").forEach(drawSentiment);
        var perf = document.getElementById("perf-chart");
        if (perf) { drawPerformance(perf); }
        // Stock Detail: choosing a symbol in the drop-down opens it
        var pick = document.getElementById("symbol-select");
        if (pick) {
            pick.addEventListener("change", function () {
                var form = document.getElementById("symbol-picker");
                if (form.elements.other) { form.elements.other.value = ""; }
                form.submit();
            });
        }
        // Search box: picking one of the suggested stocks opens it
        var other = document.getElementById("symbol-other");
        if (other) {
            other.addEventListener("input", function (ev) {
                var list = document.getElementById("symbol-suggestions");
                var v = other.value.trim().toUpperCase();
                for (var i = 0; list && i < list.options.length; i++) {
                    if (list.options[i].value === v && (ev.inputType === undefined || ev.inputType === "insertReplacementText")) {
                        document.getElementById("symbol-picker").submit();
                        return;
                    }
                }
            });
        }
    });
})();
