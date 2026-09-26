"use client";

// Small SVG charts in the shared palette (lib/theme.ts). Replaces Tremor: full
// control over colours and contrast, one series per store, no chart runtime.
// Marks follow one spec: thin bars with 4px rounded ends on the baseline side
// away, 2px surface gaps between stacked segments, 2px lines, a recessive grid,
// AA axis text, a hover tooltip on every chart, and a legend whenever there is
// more than one series (identity is never colour alone: the tooltip and the
// legend name every series).

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useChartTheme } from "@/lib/brand/context";
import { cn } from "@/lib/utils";

export interface Series {
  key: string;
  label: string;
  color: string;
}

export interface Datum {
  label: string;
  values: Record<string, number>;
}

type Fmt = (n: number) => string;

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const ro = new ResizeObserver(([e]) => setWidth(Math.round(e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

/** 0 → a "nice" max with ~4 ticks. */
function niceTicks(max: number, count = 4): number[] {
  if (max <= 0) return [0, 1];
  const raw = max / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? raw;
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(v);
  return ticks;
}

/** Bar with its free end rounded (r px), the baseline end square. */
function barPath(x: number, y: number, w: number, h: number, r: number): string {
  if (h <= 0 || w <= 0) return "";
  const rr = Math.min(r, w / 2, h);
  return `M${x},${y + h}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + w - rr}Q${x + w},${y} ${x + w},${y + rr}V${y + h}Z`;
}

export function Legend({ series, className }: { series: Series[]; className?: string }) {
  if (series.length < 2) return null;
  return (
    <ul className={cn("flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-neutral-600", className)}>
      {series.map((s) => (
        <li key={s.key} className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ backgroundColor: s.color }} />
          {s.label}
        </li>
      ))}
    </ul>
  );
}

function Tooltip({
  x,
  width,
  title,
  rows,
  total,
  format,
}: {
  x: number;
  width: number;
  title: string;
  rows: { s: Series; v: number }[];
  total?: number;
  format: Fmt;
}) {
  const left = Math.min(Math.max(x, 90), Math.max(90, width - 90));
  return (
    <div
      className="pointer-events-none absolute top-0 z-10 w-max min-w-[9rem] -translate-x-1/2 rounded-lg border border-neutral-200 bg-surface px-3 py-2 text-xs shadow-raised"
      style={{ left }}
      role="presentation"
    >
      <div className="mb-1 font-semibold text-ink">{title}</div>
      {rows.map(({ s, v }) => (
        <div key={s.key} className="flex items-center gap-2 py-0.5">
          <span className="h-2 w-2 shrink-0 rounded-sm" style={{ backgroundColor: s.color }} />
          <span className="text-neutral-600">{s.label}</span>
          <span className="ml-auto pl-3 font-medium tabular-nums text-ink">{format(v)}</span>
        </div>
      ))}
      {total !== undefined && rows.length > 1 && (
        <div className="mt-1 flex border-t border-neutral-200 pt-1 font-semibold text-ink">
          <span>Σ</span>
          <span className="ml-auto tabular-nums">{format(total)}</span>
        </div>
      )}
    </div>
  );
}

const PAD = { top: 12, right: 8, bottom: 26 };

function YAxis({ ticks, y, width, left, format }: { ticks: number[]; y: (v: number) => number; width: number; left: number; format: Fmt }) {
  const CHART = useChartTheme();
  return (
    <g>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={left} x2={width - PAD.right} y1={y(t)} y2={y(t)} stroke={CHART.grid} strokeWidth={1} />
          <text x={left - 8} y={y(t)} dy="0.32em" textAnchor="end" fontSize={11} fill={CHART.axis}>
            {format(t)}
          </text>
        </g>
      ))}
    </g>
  );
}

function XLabels({ labels, x, bottom, bandWidth }: { labels: string[]; x: (i: number) => number; bottom: number; bandWidth: number }) {
  const CHART = useChartTheme();
  const every = Math.max(1, Math.ceil(46 / Math.max(1, bandWidth)));
  return (
    <g>
      {labels.map((l, i) =>
        i % every === 0 ? (
          <text key={i} x={x(i)} y={bottom + 17} textAnchor="middle" fontSize={11} fill={CHART.axis}>
            {l}
          </text>
        ) : null
      )}
    </g>
  );
}

/**
 * Vertical bars over categories. One series → plain bars; several (one per
 * store) → stacked (default) or grouped side by side.
 */
export function BarChart({
  data,
  series,
  format,
  axisFormat = format,
  height = 224,
  stacked = true,
  ariaLabel,
  className,
}: {
  data: Datum[];
  series: Series[];
  format: Fmt;
  axisFormat?: Fmt;
  height?: number;
  stacked?: boolean;
  ariaLabel: string;
  className?: string;
}) {
  const CHART = useChartTheme();
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const totals = data.map((d) => series.reduce((s, se) => s + Math.max(0, d.values[se.key] ?? 0), 0));
  const max = stacked
    ? Math.max(0, ...totals)
    : Math.max(0, ...data.flatMap((d) => series.map((s) => d.values[s.key] ?? 0)));
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const left = 12 + Math.max(...ticks.map((t) => axisFormat(t).length)) * 6.4;
  const plotW = Math.max(0, width - left - PAD.right);
  const bottom = height - PAD.bottom;
  const y = (v: number) => PAD.top + (1 - v / top) * (bottom - PAD.top);
  const band = data.length ? plotW / data.length : 0;
  const barW = Math.max(2, Math.min(band * 0.62, stacked || series.length === 1 ? 36 : 18 * series.length));
  const cx = (i: number) => left + band * i + band / 2;

  return (
    <div className={cn("space-y-3", className)}>
      <Legend series={series} />
      <div ref={ref} className="relative" style={{ height }} onMouseLeave={() => setHover(null)}>
        {width > 0 && (
          <svg width={width} height={height} role="img" aria-label={ariaLabel}>
            <YAxis ticks={ticks} y={y} width={width} left={left} format={axisFormat} />
            {data.map((d, i) => {
              const x0 = cx(i) - barW / 2;
              if (stacked || series.length === 1) {
                let acc = 0;
                const segs = series.map((s) => {
                  const v = Math.max(0, d.values[s.key] ?? 0);
                  const y1 = y(acc + v);
                  const y0 = y(acc);
                  acc += v;
                  return { s, v, y1, y0 };
                });
                const lastWithValue = [...segs].reverse().find((g) => g.v > 0)?.s.key;
                return (
                  <g key={i} opacity={hover === null || hover === i ? 1 : 0.55}>
                    {segs.map(({ s, v, y1, y0 }) => {
                      if (v <= 0) return null;
                      // 2px surface gap between stacked segments
                      const h = Math.max(1, y0 - y1 - (s.key === segs[0].s.key ? 0 : 2));
                      return s.key === lastWithValue ? (
                        <path key={s.key} d={barPath(x0, y1, barW, h, 4)} fill={s.color} />
                      ) : (
                        <rect key={s.key} x={x0} y={y1} width={barW} height={h} fill={s.color} />
                      );
                    })}
                  </g>
                );
              }
              const sub = barW / series.length;
              return (
                <g key={i} opacity={hover === null || hover === i ? 1 : 0.55}>
                  {series.map((s, j) => {
                    const v = Math.max(0, d.values[s.key] ?? 0);
                    return (
                      <path key={s.key} d={barPath(x0 + j * sub + 1, y(v), sub - 2, bottom - y(v), 4)} fill={s.color} />
                    );
                  })}
                </g>
              );
            })}
            <line x1={left} x2={width - PAD.right} y1={bottom} y2={bottom} stroke={CHART.axis} strokeOpacity={0.5} />
            <XLabels labels={data.map((d) => d.label)} x={cx} bottom={bottom} bandWidth={band} />
            {/* hit targets: the whole band, bigger than the bar */}
            {data.map((_, i) => (
              <rect
                key={i}
                x={left + band * i}
                y={PAD.top}
                width={band}
                height={bottom - PAD.top}
                fill="transparent"
                onMouseEnter={() => setHover(i)}
                onTouchStart={() => setHover(i)}
              />
            ))}
          </svg>
        )}
        {hover !== null && data[hover] && (
          <Tooltip
            x={cx(hover)}
            width={width}
            title={data[hover].label}
            rows={series.map((s) => ({ s, v: data[hover].values[s.key] ?? 0 }))}
            total={totals[hover]}
            format={format}
          />
        )}
      </div>
    </div>
  );
}

/** Lines over an ordered axis (days): one 2px line per series, crosshair on hover. */
export function LineChart({
  data,
  series,
  format,
  axisFormat = format,
  height = 240,
  ariaLabel,
  className,
}: {
  data: Datum[];
  series: Series[];
  format: Fmt;
  axisFormat?: Fmt;
  height?: number;
  ariaLabel: string;
  className?: string;
}) {
  const CHART = useChartTheme();
  const gid = useId().replace(/:/g, "");
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(0, ...data.flatMap((d) => series.map((s) => d.values[s.key] ?? 0)));
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const left = 12 + Math.max(...ticks.map((t) => axisFormat(t).length)) * 6.4;
  const plotW = Math.max(0, width - left - PAD.right - 44); // room for the first and last day labels
  const bottom = height - PAD.bottom;
  const y = (v: number) => PAD.top + (1 - v / top) * (bottom - PAD.top);
  const step = data.length > 1 ? plotW / (data.length - 1) : 0;
  const x = (i: number) => left + 22 + (data.length > 1 ? step * i : plotW / 2);
  const paths = useMemo(
    () =>
      series.map((s) => ({
        s,
        d: data.map((d, i) => `${i ? "L" : "M"}${x(i)},${y(d.values[s.key] ?? 0)}`).join(""),
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, series, width, height]
  );
  const single = series.length === 1;

  return (
    <div className={cn("space-y-3", className)}>
      <Legend series={series} />
      <div ref={ref} className="relative" style={{ height }} onMouseLeave={() => setHover(null)}>
        {width > 0 && (
          <svg width={width} height={height} role="img" aria-label={ariaLabel}>
            <defs>
              <linearGradient id={`fill-${gid}`} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor={series[0]?.color} stopOpacity={0.18} />
                <stop offset="100%" stopColor={series[0]?.color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <YAxis ticks={ticks} y={y} width={width} left={left} format={axisFormat} />
            {single && paths[0] && data.length > 1 && (
              <path d={`${paths[0].d}L${x(data.length - 1)},${bottom}L${x(0)},${bottom}Z`} fill={`url(#fill-${gid})`} />
            )}
            {paths.map(({ s, d }) => (
              <path key={s.key} d={d} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
            ))}
            {hover !== null && (
              <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={bottom} stroke={CHART.axis} strokeOpacity={0.4} strokeDasharray="3 3" />
            )}
            {series.map((s) =>
              data.map((d, i) =>
                hover === i || data.length === 1 ? (
                  <circle
                    key={`${s.key}-${i}`}
                    cx={x(i)}
                    cy={y(d.values[s.key] ?? 0)}
                    r={4}
                    fill={s.color}
                    stroke={CHART.surface}
                    strokeWidth={2}
                  />
                ) : null
              )
            )}
            <XLabels labels={data.map((d) => d.label)} x={x} bottom={bottom} bandWidth={step || plotW} />
            {data.map((_, i) => (
              <rect
                key={i}
                x={x(i) - Math.max(step, 12) / 2}
                y={PAD.top}
                width={Math.max(step, 12)}
                height={bottom - PAD.top}
                fill="transparent"
                onMouseEnter={() => setHover(i)}
                onTouchStart={() => setHover(i)}
              />
            ))}
          </svg>
        )}
        {hover !== null && data[hover] && (
          <Tooltip
            x={x(hover)}
            width={width}
            title={data[hover].label}
            rows={series.map((s) => ({ s, v: data[hover].values[s.key] ?? 0 }))}
            format={format}
          />
        )}
      </div>
    </div>
  );
}

/** Part-to-whole ring (tender mix): 2px surface gaps between arcs, total in the middle. */
export function Donut({
  items,
  format,
  center,
  size = 176,
  ariaLabel,
}: {
  items: { key: string; label: string; value: number; color: string }[];
  format: Fmt;
  center: string;
  size?: number;
  ariaLabel: string;
}) {
  const CHART = useChartTheme();
  const [hover, setHover] = useState<string | null>(null);
  const total = items.reduce((s, i) => s + Math.max(0, i.value), 0);
  const r = size / 2 - 4;
  const inner = r - 22;
  let a0 = -Math.PI / 2;
  const arcs = items
    .filter((i) => i.value > 0)
    .map((i) => {
      const a = (Math.max(0, i.value) / (total || 1)) * Math.PI * 2;
      const start = a0;
      a0 += a;
      return { ...i, start, end: a0 };
    });
  const pt = (rad: number, ang: number) => [size / 2 + rad * Math.cos(ang), size / 2 + rad * Math.sin(ang)];
  const arcPath = (s: number, e: number) => {
    const full = e - s >= Math.PI * 2 - 1e-6;
    if (full) e = s + Math.PI * 2 - 1e-4;
    const large = e - s > Math.PI ? 1 : 0;
    const [x0, y0] = pt(r, s);
    const [x1, y1] = pt(r, e);
    const [x2, y2] = pt(inner, e);
    const [x3, y3] = pt(inner, s);
    return `M${x0},${y0}A${r},${r} 0 ${large} 1 ${x1},${y1}L${x2},${y2}A${inner},${inner} 0 ${large} 0 ${x3},${y3}Z`;
  };
  const shown = hover ? arcs.find((a) => a.key === hover) : null;
  return (
    <svg width={size} height={size} role="img" aria-label={ariaLabel} className="shrink-0" onMouseLeave={() => setHover(null)}>
      {arcs.map((a) => (
        <path
          key={a.key}
          d={arcPath(a.start, a.end)}
          fill={a.color}
          stroke={CHART.surface}
          strokeWidth={arcs.length > 1 ? 2 : 0}
          opacity={hover === null || hover === a.key ? 1 : 0.5}
          onMouseEnter={() => setHover(a.key)}
        >
          <title>{`${a.label}: ${format(a.value)}`}</title>
        </path>
      ))}
      <text x={size / 2} y={size / 2 - 7} textAnchor="middle" fontSize={11} fill={CHART.axis}>
        {shown ? shown.label : ""}
      </text>
      <text x={size / 2} y={size / 2 + (shown ? 10 : 5)} textAnchor="middle" fontSize={15} fontWeight={600} fill={CHART.text}>
        {shown ? format(shown.value) : center}
      </text>
    </svg>
  );
}
