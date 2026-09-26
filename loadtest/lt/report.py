"""docs/load-test-report.md (and its charts) from the results in .loadtest/results/.

Every number in the report comes from the JSON the scenarios wrote on this
run, stamped with the commit and the machine. Only the "what we fixed" section
is written by hand: its before/after figures were measured once, on the commit
before each fix.
"""
from __future__ import annotations

import datetime
import os
import platform
import subprocess

from .common import REPO, load_result

DOCS = os.path.join(REPO, "docs")
CHARTS = os.path.join(DOCS, "load-test")
SERIES = {"restaurant": "#2a78d6", "retail": "#eb6834", "gas": "#1baf7a"}  # validated: dataviz palette slots 1-3
INK, INK2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
LABEL = {"restaurant": "Restaurant (Copper Lantern)", "retail": "Bottle shop (Sage & Poppy, 5,000 products)",
         "gas": "Gas station (Pronghorn, pumps + shop)"}
SHORT = {"restaurant": "restaurant", "retail": "bottle shop", "gas": "gas station"}

# Measured by hand on the commit before each fix (same Mac, same driver).
FIXES = [
    ("Sales failed with \"database is locked\" as soon as two devices sold at once",
     "Most POS actions read and then write. SQLite cannot turn a stale read into a write, so whenever "
     "another device had written in between, the action failed at once with SQLITE_BUSY (a 500). The "
     "store now runs one transaction at a time, first come first served, on one long-lived connection, "
     "and starts each transaction with the write lock.",
     "Restaurant flow: 10 devices lost 35% of their sales, 25 devices lost 87% (273 sales/min got through).",
     "No failed sales at 1, 10 or 25 devices; 25 devices ring about 2,300 sales/min.",
     "`server/.../db/OneWriterDataSource.kt`, test `OneWriterDataSourceTest`"),
    ("Every scan read the whole 5,000-product catalog",
     "To decide whether a line shows its size, every check view, receipt, closed-sale event and kitchen "
     "ticket loaded every size of every product. It now counts sizes for the items on that check only.",
     "Bottle shop, 1 device: 720 sales/min; a scan took 7.3 ms.",
     "2,700 sales/min; a scan takes 0.9 ms.",
     "`server/.../restaurant/VariantCounts.kt`, test `VariantCountsTest`"),
    ("A sign-in or manager approval froze the store for up to a second",
     "A PIN is checked with bcrypt against each staff member in turn (about 30 ms each here, more on a "
     "tablet). That ran inside the database transaction, so with transactions taking turns every other "
     "device waited for the whole scan. The PIN is now checked after the transaction. A manager approval "
     "also checked the PIN against every staff member, even after a match; it now checks only the staff "
     "who can approve.",
     "With 27 staff, a manager-PIN approval did 27 bcrypt checks (~0.8 s) while holding the database.",
     "An approval does 1-2 checks; other devices keep selling during a sign-in.",
     "`server/.../base/AuthService.kt`, test `PinCheckOutsideTransactionTest`"),
    ("The portal's \"All stores\" dashboard crashed the cloud API with 200 stores",
     "Every dashboard call read every sale in range into the API (512 MB) and added them up there. It "
     "now asks Postgres for the sums, grouped by store and business day (or hour, tender type, item), "
     "and does the same per-currency arithmetic on those; a new index covers sales by store and closing "
     "time. The reports that still add up single sales (categories, fuel, tables, exceptions) now answer "
     "\"too many sales, pick fewer days or one store\" above 150,000 sales instead of crashing.",
     "200 stores, one busy day (111,000 sales): the API ran out of memory and restarted, for every range.",
     "Same database: today 7 s, 30 days 30 s, a year 72 s; one store, any range, under 0.4 s; no crash.",
     "`cloud/api/.../reports/Reports.kt`, `cloud/migrations/025_report_indexes.sql`, test `ReportScaleTest`"),
]
FINDINGS = [
    ("All-stores reports over long ranges are slow on a small database",
     "With 200 stores × a year (7.3 million sales, ~11 GB) on a 2-CPU / 2 GB Postgres, the all-stores "
     "dashboard reads gigabytes for a year (see section 4). The next step, if clients this size are "
     "expected, is a daily roll-up table per store kept up to date on ingest, and a bigger database "
     "box; the fuel/margin, categories and tables reports should move to summed queries too (today "
     "they refuse more than 150,000 sales in scope, so the gas station fuel card on an all-stores "
     "dashboard shows that message for long ranges)."),
    ("Leave a Mac awake during a load test",
     "A Mac left alone goes to sleep and freezes every process for minutes, which looks exactly like "
     "a server stall (it did, on the first run). `scripts/load-test.sh` now runs under `caffeinate`."),
    ("The sync outbox is most of the store's database",
     "Every event a store sends to its portal stays in `sync_outbox` after the portal has it. In these "
     "runs it is 65-85% of the file. It is harmless for years on a tablet, but backups and copies grow "
     "with it. Pruning acknowledged events older than, say, 90 days would cut the file by about two "
     "thirds; it also removes the ability to replay history from the tablet into a rebuilt portal, so "
     "it is a decision, not a fix. Not changed."),
    ("A PIN sign-in gets slower with every staff member",
     "PIN-only sign-in has to try the PIN against each staff member's bcrypt hash: ~30 ms per person on "
     "this Mac, several times that on a tablet. With 27 staff the slowest sign-in is ~0.7 s here. Fine for "
     "a store's own staff; a very large store would want a staff picker before the PIN. Not changed."),
    ("A year's sales report on the tablet takes a while",
     "The store's own date-range report (X-report layout) over a whole year reads every sale; see the "
     "history table below. It is a rare, manager-only screen. Not changed."),
]


def sh(*cmd: str) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=20, cwd=REPO).stdout.strip()
    except Exception:
        return ""


def machine() -> dict:
    mac = platform.system() == "Darwin"
    cpu = sh("sysctl", "-n", "machdep.cpu.brand_string") if mac else platform.processor()
    mem = int(sh("sysctl", "-n", "hw.memsize") or 0) / 2 ** 30 if mac else 0
    java = sh("java", "-version") or subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
    return {
        "cpu": cpu or platform.machine(), "cores": os.cpu_count(), "memory_gb": round(mem),
        "os": (f"macOS {sh('sw_vers', '-productVersion')}" if mac else platform.platform()),
        "java": (java.splitlines() or [""])[0], "python": platform.python_version(),
        "docker": sh("docker", "info", "--format", "{{.NCPU}} CPUs, {{.MemTotal}} bytes"),
    }


def fmt(n, d=0) -> str:
    if n is None:
        return "–"
    return f"{n:,.{d}f}"


def ms(n) -> str:
    if n is None:
        return "–"
    return f"{n:,.0f} ms" if n >= 10 else f"{n:.1f} ms"


def secs(ms_: float | None) -> str:
    return "–" if ms_ is None else (f"{ms_ / 1000:.1f} s" if ms_ >= 1000 else f"{ms_:.0f} ms")


# ------------------------------------------------------------------ charts

def charts(store: dict, sync: dict | None, soak: dict | None) -> list[str]:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return []
    os.makedirs(CHARTS, exist_ok=True)
    made = []
    plt.rcParams.update({"font.size": 10, "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2,
                         "ytick.color": INK2, "axes.titlecolor": INK, "figure.facecolor": SURFACE,
                         "axes.facecolor": SURFACE, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8})

    def style(ax, title, xlabel, ylabel):
        ax.set_title(title, loc="left", fontsize=11, fontweight="bold")
        ax.set_xlabel(xlabel)
        ax.set_ylabel(ylabel)
        for s in ("top", "right"):
            ax.spines[s].set_visible(False)
        ax.set_axisbelow(True)

    def by_devices(key, title, ylabel, fname, fn):
        fig, ax = plt.subplots(figsize=(7, 3.6), dpi=150)
        for kind, r in store.items():
            xs = [lv["devices"] for lv in r["levels"]]
            ys = [fn(lv) for lv in r["levels"]]
            ax.plot(xs, ys, color=SERIES[kind], linewidth=2, marker="o", markersize=6,
                    markeredgecolor=SURFACE, markeredgewidth=2, label=SHORT[kind])
            ax.annotate(f"{SHORT[kind]}  {ys[-1]:,.0f}", (xs[-1], ys[-1]), xytext=(6, 0),
                        textcoords="offset points", va="center", color=INK, fontsize=9)
        ax.set_xticks(sorted({lv["devices"] for r in store.values() for lv in r["levels"]}))
        ax.set_ylim(bottom=0)
        ax.margins(x=0.18)
        style(ax, title, "devices selling at once, non-stop", ylabel)
        ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.2), ncol=3, fontsize=9)
        fig.tight_layout()
        p = os.path.join(CHARTS, fname)
        fig.savefig(p, facecolor=SURFACE)
        plt.close(fig)
        made.append(os.path.relpath(p, DOCS))

    if store:
        by_devices("sales", "Sales per minute one store completes", "sales / minute", "store-throughput.png",
                   lambda lv: lv["sales_per_min"])
        by_devices("p95", "Response time, 95th percentile (every call)", "milliseconds", "store-p95.png",
                   lambda lv: lv["all"]["p95"])
    if sync:
        fig, ax = plt.subplots(figsize=(7, 3.4), dpi=150)
        mx = [p for p in sync["phases"] if p["phase"] == "max"]
        xs = [str(p["stores"]) for p in mx]
        ys = [p["sales_per_s"] for p in mx]
        bars = ax.bar(xs, ys, color=SERIES["restaurant"], width=0.55, edgecolor=SURFACE, linewidth=2)
        for b, y in zip(bars, ys):
            ax.annotate(f"{y:,.0f}", (b.get_x() + b.get_width() / 2, y), xytext=(0, 4), textcoords="offset points",
                        ha="center", color=INK, fontsize=9)
        style(ax, "Sales per second the portal ingests (stores pushing flat out)", "stores pushing at once",
              "sales / second")
        ax.grid(axis="x", visible=False)
        fig.tight_layout()
        p = os.path.join(CHARTS, "sync-ingest.png")
        fig.savefig(p, facecolor=SURFACE)
        plt.close(fig)
        made.append(os.path.relpath(p, DOCS))
    if soak and soak.get("samples"):
        fig, ax = plt.subplots(figsize=(7, 3.4), dpi=150)
        pts = [(r["minute"], r["heap_after_gc_mb"]) for r in soak["samples"] if r.get("heap_after_gc_mb")]
        rss = [(r["minute"], r["rss_mb"]) for r in soak["samples"]]
        ax.plot([p[0] for p in rss], [p[1] for p in rss], color=SERIES["retail"], linewidth=2, label="resident memory")
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color=SERIES["restaurant"], linewidth=2, marker="o",
                markersize=6, markeredgecolor=SURFACE, markeredgewidth=2, label="heap in use after a full GC")
        ax.set_ylim(bottom=0)
        style(ax, "Store memory over the soak", "minutes", "MB")
        ax.legend(frameon=False, loc="lower right", fontsize=9)
        fig.tight_layout()
        p = os.path.join(CHARTS, "soak-memory.png")
        fig.savefig(p, facecolor=SURFACE)
        plt.close(fig)
        made.append(os.path.relpath(p, DOCS))
    return made


# ------------------------------------------------------------------ the page

def run() -> str:
    store = {k: r for k in ("restaurant", "retail", "gas") if (r := load_result(f"store-{k}"))}
    bigdb = load_result("bigdb") or {}
    sync = load_result("sync")
    soak = load_result("soak")
    m = machine()
    commit = sh("git", "rev-parse", "--short", "HEAD")
    dirty = " (with uncommitted changes)" if sh("git", "status", "--porcelain", "--untracked-files=no") else ""
    made = charts(store, sync, soak)
    L: list[str] = []
    w = L.append
    w("# Load test report")
    w("")
    w(f"Commit `{commit}`{dirty} · generated {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')} · "
      f"{m['cpu']}, {m['cores']} cores, {m['memory_gb']} GB, {m['os']} · regenerate with "
      "`scripts/load-test.sh all`")
    w("")
    if os.environ.get("LT_QUICK"):
        w("> **Quick smoke run** (short durations, small data): the numbers only show the suite works. "
          "Run `scripts/load-test.sh all` for the real report.")
        w("")
    w("Everything ran on this one machine: the store server jar (the same code the tablet embeds), the "
      "forecourt simulator, and a local cloud (Postgres in Docker, limited to 2 CPUs and 2 GB, and the cloud "
      "API with the production 512 MB heap). No hosted server was touched. A tablet has a slower processor "
      "and storage than this Mac, so read the store numbers as what the software can do, not as tablet "
      "numbers; the headroom below is large enough that a tablet several times slower still has plenty.")
    w("")
    w("## Headlines")
    w("")
    for kind, r in store.items():
        top = r["levels"][-1]
        w(f"- **{LABEL[kind]}:** one store completes **{fmt(top['sales_per_min'])} sales a minute with "
          f"{top['devices']} devices** selling non-stop, every call answered at **p95 {ms(top['all']['p95'])}**, "
          f"with {fmt(top['failed_sales'])} failed sales and {fmt(top['errors'])} errors."
          + (" The pumps set the pace here (each fill takes a few seconds even at 20× speed), not the store."
             if kind == "gas" else ""))
    for kind, b in bigdb.items():
        db = b["db"]
        lv = b["load10"]
        small = next((x for x in store.get(kind, {}).get("levels", []) if x["devices"] == 10), None)
        cmp_ = f" (p95 {ms(small['all']['p95'])} on a fresh database)" if small else ""
        w(f"- **A year of sales, {SHORT[kind]}:** {fmt(db['closed_sales'])} sales make a "
          f"**{fmt(db['db_bytes'] / 1e6)} MB** database (~{fmt(db['db_bytes'] / max(1, db['closed_sales']) / 1024, 1)} KB "
          f"a sale). On it, 10 devices still get p95 **{ms(lv['all']['p95'])}**{cmp_}.")
    if sync:
        mx = [p for p in sync["phases"] if p["phase"] == "max"]
        big = mx[-1] if mx else None
        steady = [p for p in sync["phases"] if p["phase"] == "steady"]
        v = sync.get("verify", {})
        if big:
            w(f"- **The portal ingests {fmt(big['sales_per_s'])} sales a second ({fmt(big['events_per_s'])} events) "
              f"from {big['stores']} stores** pushing flat out, with {fmt(big['errors'])} failed pushes.")
        if steady:
            s = steady[-1]
            w(f"- **{s['stores']} stores at a busy real pace** (6 sales a minute each, pushing every 10 s): a push "
              f"takes p50 {ms(s['latency_ms']['p50'])}, p95 {ms(s['latency_ms']['p95'])}; the cloud API used "
              f"{fmt(s['cpu_avg_pct'])}% of one core.")
        if v:
            w(f"- **Nothing lost, nothing counted twice:** {fmt(v['events_in_cloud'])} of {fmt(v['events_sent'])} events "
              f"and {fmt(v['sales_in_cloud'])} of {fmt(v['sales_sent'])} sales arrived, "
              f"{fmt(v['events_resent'])} deliberately re-sent events were all recognised as duplicates — "
              f"**{'all checks pass' if v.get('ok') else 'CHECK FAILED, see below'}**.")
        br = sync.get("backlog_real")
        if br:
            w(f"- **A store back after being offline** with {fmt(br['sales'])} sales ({fmt(br['events'])} events) "
              f"waiting caught up in **{br['drain_s']} s**, with its first acknowledgements lost on the way; "
              f"the portal's sales and totals match the store's exactly: **{'yes' if br.get('ok') else 'NO'}**.")
        po = (sync.get("portal") or {}).get("warm")
        seed = (sync.get("portal") or {}).get("seed")
        if po and seed:
            t = po.get("all stores, today", {}).get("dashboard_ms")
            y = po.get("all stores, a year", {}).get("dashboard_ms")
            o = po.get("one store, a year", {}).get("dashboard_ms")
            w(f"- **Portal over {seed['stores']} stores × {seed['days']} days** ({fmt(seed['sales'])} sales): "
              f"the dashboard loads in **{secs(t)}** for today across all stores, {secs(y)} for a whole year "
              f"across all stores, {secs(o)} for one store's year.")
    if soak:
        w(f"- **Soak, {fmt(soak['minutes'])} minutes at five times demo pace** ({fmt(soak['sales'])} sales, synced): "
          f"heap after GC changed by {soak['heap_slope_mb_per_hour']} MB an hour, p95 went "
          f"{ms(soak['p95_first_10min'])} → {ms(soak['p95_last_10min'])}, threads "
          f"{soak['threads_start_end'][0]} → {soak['threads_start_end'][1]}, open files "
          f"{soak['open_files_start_end'][0]} → {soak['open_files_start_end'][1]}; "
          f"{fmt(soak['failed_sales'])} failed sales, {fmt(soak['sales_in_cloud'])} in the portal. "
          + ("**No leak.**" if abs(soak["heap_slope_mb_per_hour"]) < 5
             and (soak["threads_start_end"][1] or 0) - (soak["threads_start_end"][0] or 0) <= 5
             else "**Memory or threads grew: look into it.**"))
    w("")
    w("For scale: a busy cashier rings about one sale a minute, so 25 devices at a real pace is ~25 sales a "
      "minute. The devices here have no think time at all.")
    w("")
    w("## What we fixed")
    w("")
    w("Found by this load test, fixed with tests, one commit each. Before/after on this Mac, same driver.")
    w("")
    for title, what, before, after, where in FIXES:
        w(f"**{title}.** {what}")
        w("")
        w(f"- Before: {before}")
        w(f"- After: {after}")
        w(f"- {where}")
        w("")
    w("### Found, not changed")
    w("")
    for title, text in FINDINGS:
        w(f"- **{title}.** {text}")
    w("")
    for c in made:
        w(f"![{os.path.splitext(os.path.basename(c))[0]}]({c})")
        w("")
    # ---------------------------------------------------------- details
    w("## Details")
    w("")
    w("### 1. One store, N devices")
    w("")
    w("Each device signs in as its own cashier and sells in a loop, with no pause between sales. "
      "**Restaurant:** open a check, add 3-6 items, send to the kitchen, look at the check, add 1-2 more, "
      "send again, print the bill, then pay (30% split two ways and paid by group; else 60% card, 40% cash) "
      "and close; a kitchen screen polls every 2 s and bumps what it shows. **Bottle shop:** scan 1-8 "
      "barcodes by sales weight (the real long tail), search the catalog on 35% of sales, ID check when "
      "there is alcohol, pay, close. **Gas station:** 50% postpay (authorise the pump, the simulator fills "
      "5-14 gal, pay inside with 0-3 shop items), 20% prepay (pay first, pump, change back), 30% shop only. "
      "The bottle shop and gas station have one register per tablet in the app; the extra devices sell on "
      "extra counter lanes against the same store. Meanwhile someone signs in every 3 s.")
    w("")
    w("| store | devices | sales/min | requests/s | p50 | p95 | p99 | errors | failed sales | CPU avg (1 core = 100%) | memory max | sign-in during load (p50) |")
    w("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for kind, r in store.items():
        for lv in r["levels"]:
            w(f"| {SHORT[kind]} | {lv['devices']} | {fmt(lv['sales_per_min'])} | {fmt(lv['requests_per_s'])} | "
              f"{ms(lv['all']['p50'])} | {ms(lv['all']['p95'])} | {ms(lv['all']['p99'])} | {fmt(lv['errors'])} | "
              f"{fmt(lv['failed_sales'])} | {fmt(lv['cpu_avg_pct'])}% | {fmt(lv['rss_max_mb'])} MB | "
              f"{ms((lv.get('login_during_load') or {}).get('p50'))} |")
    w("")
    for kind, r in store.items():
        top = r["levels"][-1]
        w(f"<details><summary>{SHORT[kind]}: every endpoint at {top['devices']} devices</summary>")
        w("")
        w("| endpoint | calls | p50 | p95 | p99 | errors |")
        w("|---|---:|---:|---:|---:|---:|")
        for name, e in sorted(top["endpoints"].items(), key=lambda kv: -kv[1]["n"]):
            w(f"| `{name}` | {fmt(e['n'])} | {ms(e['p50'])} | {ms(e['p95'])} | {ms(e['p99'])} | {fmt(e['errors'])} |")
        w("")
        w("</details>")
        w("")
    w("The store server works one database transaction at a time (that is what made it reliable), so it "
      "uses about one CPU core however many devices there are; more devices queue a few milliseconds longer.")
    w("")
    if store:
        w("| store | sales in the test | database | per sale | of which the sync outbox |")
        w("|---|---:|---:|---:|---:|")
        for kind, r in store.items():
            d = r["db"]
            w(f"| {SHORT[kind]} | {fmt(d['closed_sales'])} | {fmt(d['db_bytes'] / 1e6, 1)} MB | "
              f"{fmt(d['bytes_per_sale'] / 1024, 1)} KB | {fmt(100 * d.get('outbox_bytes', 0) / max(1, d['db_bytes']))}% |")
        w("")
    if bigdb:
        w("### 2. A year of sales in one store")
        w("")
        w("The history is the store's own rows: the sales from section 1 copied back over 365 days (fresh "
          "ids, dates moved, one closed shift a day), so the size per sale is what the app writes.")
        w("")
        w("| store | sales | database | outbox share | start-up | 10 devices p50 / p95 / p99 | sales/min | errors |")
        w("|---|---:|---:|---:|---:|---|---:|---:|")
        for kind, b in bigdb.items():
            d, lv = b["db"], b["load10"]
            w(f"| {SHORT[kind]} | {fmt(d['closed_sales'])} | {fmt(d['db_bytes'] / 1e6)} MB | "
              f"{fmt(100 * d.get('outbox_bytes', 0) / max(1, d['db_bytes']))}% | {b['startup_s']} s | "
              f"{ms(lv['all']['p50'])} / {ms(lv['all']['p95'])} / {ms(lv['all']['p99'])} | "
              f"{fmt(lv['sales_per_min'])} | {fmt(lv['errors'])} |")
        w("")
        w("History screens on the big database (single calls, p50 of 5):")
        w("")
        kinds = list(bigdb)
        names = []
        for b in bigdb.values():
            for n in b["history"]:
                if n not in names:
                    names.append(n)
        w("| screen | " + " | ".join(SHORT[k] for k in kinds) + " |")
        w("|---|" + "---:|" * len(kinds))
        for n in names:
            cells = []
            for k in kinds:
                h = bigdb[k]["history"].get(n)
                cells.append("–" if h is None else ("error" if "error" in h else ms(h["p50"])))
            w(f"| `{n}` | " + " | ".join(cells) + " |")
        w("")
    if sync:
        w("### 3. Sync, stores → portal")
        w("")
        w("Simulated stores push exactly what a store's outbox pusher sends: batches of up to 200 events, in "
          "order, each store with its own key and install id. Every event is a copy of a real sale from "
          "section 1 (all its events: opened, lines, kitchen, tenders, closed, receipt), with fresh ids and "
          "the current time. The stores are a mix of the three kinds.")
        w("")
        w("| phase | stores | sales/s | events/s | push p50 | p95 | p99 | failed pushes | cloud API CPU | Postgres CPU (of 200%) |")
        w("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for p in sync["phases"]:
            lat = p["latency_ms"]
            w(f"| {'real pace, 6 sales/min each' if p['phase'] == 'steady' else 'flat out'} | {p['stores']} | "
              f"{fmt(p['sales_per_s'], 1)} | {fmt(p['events_per_s'])} | {ms(lat['p50'])} | {ms(lat['p95'])} | "
              f"{ms(lat['p99'])} | {fmt(p['errors'])} | {fmt(p['cpu_avg_pct'])}% | {fmt(p['pg_cpu_avg_pct'])}% |")
        w("")
        bs = sync.get("backlog_sim")
        if bs:
            w(f"A store holding a busy day ({fmt(bs['sales'])} sales, {fmt(bs['events'])} events) sends it in "
              f"{bs.get('drain_s')} s.")
            w("")
        br = sync.get("backlog_real")
        if br:
            w(f"The real store server, cut off from the cloud behind a proxy, sold {fmt(br['sales'])} sales "
              f"({fmt(br['sales_per_min_while_offline'])} a minute, {fmt(br['errors_while_offline'])} errors: "
              f"being offline does not slow it). When the network came back, its own sync caught up "
              f"{fmt(br['events'])} events in {br['drain_s']} s (~{fmt(br['events_per_s'])} events/s), "
              f"{br['acks_dropped']} responses thrown away after the cloud had applied the batch. "
              f"Events missing in the cloud: {br['missing_in_cloud']}; extra: {br['extra_in_cloud']}; closed sales "
              f"{fmt(br['cloud_sales'])} of {fmt(br['sales'])}; totals {fmt(br['cloud_cents'] / 100, 2)} vs "
              f"{fmt(br['store_cents'] / 100, 2)}.")
            w("")
        v = sync.get("verify")
        if v:
            w(f"Checked per store against what it sent: events {fmt(v['events_in_cloud'])}/{fmt(v['events_sent'])}, "
              f"closed sales {fmt(v['sales_in_cloud'])}/{fmt(v['sales_sent'])}, lost {v['lost_events']}, stored twice "
              f"{v['duplicated_events']}, sale totals equal for every store: "
              f"{'yes' if not v['mismatched_stores'] else 'NO: ' + str(v['mismatched_stores'][:3])}. The cloud reported "
              f"{fmt(v['accepted_reported'])} accepted and {fmt(v['duplicates_reported'])} duplicates for "
              f"{fmt(v['events_resent'])} re-sent events (3% of batches sent twice, 2% sent twice at the same moment).")
            w("")
        po = sync.get("portal")
        if po:
            seed = po["seed"]
            w(f"### 4. The portal with {seed['stores']} stores × {seed['days']} days")
            w("")
            w(f"{fmt(seed['sales'])} closed sales and {fmt(seed['lines'])} sale lines: each store's ingested sales "
              f"copied back {seed['days']} days at {seed['per_day']} a day, inside Postgres. Database "
              f"{fmt(po['sizes'].get('(database)', 0) / 2 ** 30, 1)} GB. The dashboard's four calls are made at once, as "
              "the browser does; times are with Postgres warm (second pass).")
            w("")
            names = ["summary", "payments", "hourly", "items", "by-venue", "tax", "categories", "fuel", "shifts"]
            w("| scope, range | dashboard | " + " | ".join(names) + " |")
            w("|---|---:|" + "---:|" * len(names))
            for key, e in po["warm"].items():
                cells = []
                for n in names:
                    c = e["calls"].get(n)
                    cells.append("–" if not c else (secs(c["ms"]) if c["status"] == 200 else f"**{c['status'] or 'timeout'}**"))
                w(f"| {key} | {secs(e['dashboard_ms'])} | " + " | ".join(cells) + " |")
            w("")
    if soak:
        w("### 5. Soak")
        w("")
        w(f"One restaurant store for {fmt(soak['minutes'])} minutes: {soak['devices']} devices each ringing a "
          f"full restaurant sale every 20 s (15 a minute, five times a busy demo), refreshing the floor plan "
          f"every 5 s in between, the kitchen screen polling, sync on to a local cloud.")
        w("")
        w(f"- Sales {fmt(soak['sales'])}, failed {fmt(soak['failed_sales'])}, errors {fmt(soak['errors'])}, in the portal "
          f"{fmt(soak['sales_in_cloud'])}.")
        w(f"- Heap in use after a full GC: slope {soak['heap_slope_mb_per_hour']} MB/hour "
          f"(resident memory {soak['rss_slope_mb_per_hour']} MB/hour after the first quarter).")
        w(f"- p95 first 10 minutes {ms(soak['p95_first_10min'])}, last 10 minutes {ms(soak['p95_last_10min'])}.")
        w(f"- JVM threads {soak['threads_start_end'][0]} → {soak['threads_start_end'][1]}; open files "
          f"{soak['open_files_start_end'][0]} → {soak['open_files_start_end'][1]}.")
        w("")
    w("## The machine")
    w("")
    w(f"- {m['cpu']}, {m['cores']} cores, {m['memory_gb']} GB RAM, {m['os']}")
    w(f"- {m['java']}; Python {m['python']}; Docker VM: {m['docker'] or 'n/a'}")
    w("- Store server: `server/build/libs/pos-server-all.jar`, `-Xmx512m`, its own data directory under `.loadtest/`")
    w("- Cloud: Postgres 17 (the production image) in Docker with 2 CPUs / 2 GB; cloud API jar with `-Xmx512m`")
    w("- The load driver runs on the same machine and takes some of its CPU.")
    w("")
    w("## How to rerun")
    w("")
    w("```sh")
    w("scripts/load-test.sh all        # everything, then this report (about 3½ hours: the soak is 2 h)")
    w("scripts/load-test.sh quick      # a 15-minute smoke run of every scenario (report marked as quick)")
    w("scripts/load-test.sh store      # or: store restaurant | retail | gas")
    w("scripts/load-test.sh bigdb | sync | soak | report | clean")
    w("```")
    w("")
    w("Needs Java 17, Docker, Node 22 (the forecourt simulator) and python3; charts need matplotlib, which "
      "the script installs into `.loadtest/venv`. It builds the store and cloud jars first. It uses only its "
      "own ports (18480-18491, 18181, 55433) and `.loadtest/`: never the demo stores (:8080, :8082, :8084), the "
      "local demo cloud (:8081), the tablet or the hosted box, and it refuses to start if a port is taken. "
      "Knobs: `LT_LEVELS=1,5,10,25`, `LT_SECONDS=60`, `LT_BIGDB_SALES=100000`, `LT_SYNC_SECONDS=60`, "
      "`LT_PORTAL_DAYS=365`, `LT_PORTAL_SALES_PER_DAY=100`, `LT_SOAK_MINUTES=120`; `LT_JFR=1` records a CPU "
      "profile of the store (read it with `python3 loadtest/lt/jfrtop.py`).")
    w("")
    path = os.path.join(DOCS, "load-test-report.md")
    with open(path, "w") as f:
        f.write("\n".join(L))
    print(f"report → {os.path.relpath(path, REPO)} ({len(made)} charts)")
    return path
