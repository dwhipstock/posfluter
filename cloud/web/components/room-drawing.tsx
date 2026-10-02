"use client";

// A read-only drawing of one room, the way the point of sale draws it
// (client/lib/widgets/floor_plan.dart): the 1000 × 1000 plan, the room's
// fixtures quiet underneath (muted slab, icon and caption; pillars a solid
// block), the tables on top (round, square, rectangle or bar high-top) with
// their label upright and the seat count under it. In a proposal, what is
// added, changed or removed is outlined. No editing here, on purpose.
import type { LucideIcon } from "lucide-react";
import {
  Beer,
  Cake,
  Coffee,
  CookingPot,
  DoorOpen,
  Dices,
  Flame,
  Flower2,
  Footprints,
  Gamepad2,
  Landmark,
  Lock,
  MicVocal,
  Music,
  Package,
  Piano,
  Refrigerator,
  Shirt,
  ShoppingBag,
  Speaker,
  Star,
  Toilet,
  Tv,
  UserRound,
  Wine,
  AppWindow,
  ArrowUpDown,
} from "lucide-react";
import { useI18n, useT } from "@/lib/i18n/context";
import { CANVAS, objectCaption, roomName, type Highlights } from "@/lib/rooms";
import type { FloorObject, FloorTable, RoomDto } from "@/lib/types";
import { cn } from "@/lib/utils";

/** CUSTOM objects' fixed icons (the store's FLOOR_OBJECT_ICONS keys); unknown → the star. */
const CUSTOM_ICONS: Record<string, LucideIcon> = {
  music: Music,
  speaker: Speaker,
  tv: Tv,
  piano: Piano,
  plant: Flower2,
  coat: Shirt,
  stairs: Footprints,
  elevator: ArrowUpDown,
  fireplace: Flame,
  games: Gamepad2,
  casino: Dices,
  atm: Landmark,
  window: AppWindow,
  door: DoorOpen,
  wine: Wine,
  coffee: Coffee,
  cake: Cake,
  fridge: Refrigerator,
  storage: Package,
  star: Star,
};

/** The built-in types' look (null: no icon, e.g. pool table, bar, pillar). */
function typeIcon(o: FloorObject): LucideIcon | null {
  switch (o.type) {
    case "ENTRANCE":
      return DoorOpen;
    case "HOST_STAND":
      return UserRound;
    case "KITCHEN":
      return CookingPot;
    case "RESTROOMS":
      return Toilet;
    case "STAGE":
      return MicVocal;
    case "CARRY_OUT":
      return ShoppingBag;
    case "BAR_FRONT":
      return Beer;
    case "CUSTOM":
      return (o.icon && CUSTOM_ICONS[o.icon]) || Star;
    default:
      return null;
  }
}

type Mark = "added" | "changed" | "removed" | null;

function markOf(id: string, h?: Highlights | null, showRemoved = false): Mark {
  if (!h) return null;
  if (h.added.has(id)) return "added";
  if (h.changed.has(id)) return "changed";
  if (showRemoved && h.removed.has(id)) return "removed";
  return null;
}

const MARK_CLASS: Record<Exclude<Mark, null>, string> = {
  added: "fill-emerald-50 stroke-emerald-600",
  changed: "fill-amber-50 stroke-amber-600",
  removed: "fill-red-50 stroke-red-600",
};

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const centre = (s: { x: number; y: number; width: number; height: number }) => ({ cx: s.x + s.width / 2, cy: s.y + s.height / 2 });

/**
 * [highlights]: outline what a proposal adds and changes; [showRemoved]: also
 * outline (dashed, red) what it removes — the "before" drawing.
 */
export function RoomDrawing({
  room,
  highlights,
  showRemoved = false,
  className,
}: {
  room: RoomDto;
  highlights?: Highlights | null;
  showRemoved?: boolean;
  className?: string;
}) {
  const t = useT();
  const { locale } = useI18n();
  const name = roomName(locale, room);
  return (
    <svg
      viewBox={`0 0 ${CANVAS} ${CANVAS}`}
      role="img"
      aria-label={t("rooms_drawing_label", { room: name, n: room.tables.length })}
      className={cn("block aspect-square h-auto w-full max-w-full select-none", className)}
      preserveAspectRatio="xMidYMid meet"
    >
      <rect x={1} y={1} width={CANVAS - 2} height={CANVAS - 2} rx={16} className="fill-surface stroke-neutral-200" strokeWidth={1} vectorEffect="non-scaling-stroke" />
      {room.objects.map((o) => (
        <ObjectShape key={o.id} o={o} mark={markOf(o.id, highlights, showRemoved)} caption={objectCaption(locale, o, t)} />
      ))}
      {room.tables.map((tb) => (
        <TableShape
          key={tb.id}
          tb={tb}
          mark={markOf(tb.id, highlights, showRemoved)}
          lockedLabel={t("rooms_locked_legend")}
          seatsLabel={t("rooms_seats_short", { n: tb.seats })}
        />
      ))}
    </svg>
  );
}

function ObjectShape({ o, mark, caption }: { o: FloorObject; mark: Mark; caption: string | null }) {
  const { cx, cy } = centre(o);
  const pillar = o.type === "PILLAR";
  const round = pillar || o.shape === "ROUND";
  const Icon = typeIcon(o);
  const small = Math.min(o.width, o.height);
  const iconSize = clamp(small * 0.42, 22, 64);
  const font = clamp(small * 0.24, 18, 30);
  // the icon beside the caption on a long slab, above it on a squarer one
  const wide = o.width > o.height * 1.8;
  const showCaption = !!caption && small >= 36;
  const shapeClass = mark ? MARK_CLASS[mark] : pillar ? "fill-neutral-300 stroke-neutral-300" : "fill-neutral-100 stroke-neutral-300";
  const strokeW = mark ? 2 : 1;
  const dash = mark === "removed" ? "6 4" : undefined;
  const words = showCaption ? caption!.slice(0, 24) : null;
  let iconX = cx - iconSize / 2;
  let iconY = cy - iconSize / 2;
  let textX = cx;
  let textY = cy;
  if (Icon && words) {
    if (wide) {
      const w = iconSize + 8 + words.length * font * 0.55;
      iconX = cx - w / 2;
      textX = iconX + iconSize + 8 + (words.length * font * 0.55) / 2;
      textY = cy;
    } else {
      iconY = cy - (iconSize + font + 4) / 2;
      textY = iconY + iconSize + 4 + font / 2;
    }
  }
  return (
    <g>
      <g transform={o.rotation ? `rotate(${o.rotation} ${cx} ${cy})` : undefined}>
        {round ? (
          <ellipse cx={cx} cy={cy} rx={o.width / 2} ry={o.height / 2} className={shapeClass} strokeWidth={strokeW} strokeDasharray={dash} vectorEffect="non-scaling-stroke" />
        ) : (
          <rect x={o.x} y={o.y} width={o.width} height={o.height} rx={8} className={shapeClass} strokeWidth={strokeW} strokeDasharray={dash} vectorEffect="non-scaling-stroke" />
        )}
      </g>
      {/* upright, like the till's counter-rotated caption */}
      {Icon && small >= 30 && <Icon x={iconX} y={iconY} width={iconSize} height={iconSize} className="text-neutral-600" strokeWidth={2} aria-hidden />}
      {words && (
        <text x={textX} y={textY} fontSize={font} textAnchor="middle" dominantBaseline="central" className="fill-neutral-700 font-medium">
          {words}
        </text>
      )}
    </g>
  );
}

function TableShape({ tb, mark, lockedLabel, seatsLabel }: { tb: FloorTable; mark: Mark; lockedLabel: string; seatsLabel: string }) {
  const { cx, cy } = centre(tb);
  const small = Math.min(tb.width, tb.height);
  const font = clamp(small * 0.3, 20, 40);
  const seatFont = clamp(font * 0.62, 14, 24);
  const showSeats = small >= 70;
  const bar = tb.shape === "BAR";
  const shapeClass = mark ? MARK_CLASS[mark] : bar ? "fill-neutral-100 stroke-neutral-500" : "fill-surface stroke-neutral-500";
  const strokeW = mark ? 2.5 : 1.25;
  const dash = mark === "removed" ? "6 4" : undefined;
  const rx = bar ? 6 : 14;
  const textClass = mark === "removed" ? "fill-red-800" : "fill-ink";
  const labelY = showSeats ? cy - seatFont * 0.45 : cy;
  return (
    <g>
      <g transform={tb.rotation ? `rotate(${tb.rotation} ${cx} ${cy})` : undefined}>
        {tb.shape === "ROUND" ? (
          <ellipse cx={cx} cy={cy} rx={tb.width / 2} ry={tb.height / 2} className={shapeClass} strokeWidth={strokeW} strokeDasharray={dash} vectorEffect="non-scaling-stroke" />
        ) : (
          <rect x={tb.x} y={tb.y} width={tb.width} height={tb.height} rx={rx} className={shapeClass} strokeWidth={strokeW} strokeDasharray={dash} vectorEffect="non-scaling-stroke" />
        )}
      </g>
      <text x={cx} y={labelY} fontSize={font} textAnchor="middle" dominantBaseline="central" className={cn(textClass, "font-semibold")}>
        {tb.label}
      </text>
      {showSeats && (
        <text x={cx} y={labelY + font * 0.5 + seatFont * 0.6} fontSize={seatFont} textAnchor="middle" dominantBaseline="central" className="fill-neutral-600">
          {seatsLabel}
        </text>
      )}
      {tb.locked && (
        <g>
          <title>{lockedLabel}</title>
          <circle cx={tb.x + tb.width - 4} cy={tb.y + 4} r={17} className="fill-amber-100 stroke-amber-700" strokeWidth={1} vectorEffect="non-scaling-stroke" />
          <Lock x={tb.x + tb.width - 15} y={tb.y - 7} width={22} height={22} className="text-amber-800" strokeWidth={2.5} aria-hidden />
        </g>
      )}
    </g>
  );
}

/** The legend under a proposal's drawings. */
export function DrawingLegend({ removed = true }: { removed?: boolean }) {
  const t = useT();
  const item = (cls: string, label: string, dashed = false) => (
    <span className="inline-flex items-center gap-1.5">
      <span className={cn("inline-block h-3 w-3 rounded-sm border-2", cls, dashed && "border-dashed")} aria-hidden />
      {label}
    </span>
  );
  return (
    <p className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-neutral-700">
      {item("border-emerald-600 bg-emerald-50", t("rooms_legend_added"))}
      {item("border-amber-600 bg-amber-50", t("rooms_legend_changed"))}
      {removed && item("border-red-600 bg-red-50", t("rooms_legend_removed"), true)}
    </p>
  );
}
