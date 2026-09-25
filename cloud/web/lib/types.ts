// Shapes from cloud/API.md. All money integer cents; all timestamps ISO-8601
// instants carrying the venue's offset (the leading wall clock is venue-local).

export interface Me {
  email: string;
  displayName: string;
  /** The tenant's first store; prefer tenantName for the group. */
  venueName: string;
  tenantName: string;
}

/** One store's headline figures (the per-store comparison in "All stores"). */
export interface VenueSummaryRow {
  venueId: string;
  venueName: string;
  grossCents: number;
  netCents: number;
  taxCents: number;
  checkCount: number;
  avgCheckCents: number;
  voidCount: number;
  refundAmountCents: number;
}

export interface ByVenueReport {
  venues: VenueSummaryRow[];
  grossCents: number;
  checkCount: number;
}

export type LoginResponse =
  | { stage: "authenticated" }
  | { stage: "totp"; pendingToken: string }
  | { stage: "totp_setup"; pendingToken: string; secret: string; otpauthUri: string };

// /auth/totp/confirm — the one-time backup codes, shown once right after enrollment.
export interface ConfirmResponse {
  ok: boolean;
  backupCodes: string[];
}

export interface VenueDayRow {
  venueId: string;
  grossCents: number;
  netCents: number;
  taxCents: number;
  checkCount: number;
}

export interface DayRow {
  date: string;
  grossCents: number;
  netCents: number;
  taxCents: number;
  checkCount: number;
  /** The same day per in-scope store (zeros included). */
  byVenue: VenueDayRow[];
}

/** One store's totals in the items / categories / hourly / tables reports. */
export interface VenueTotalRow {
  venueId: string;
  venueName: string;
  grossCents: number;
  checkCount: number;
  qty: number;
}

/** One store's share of an item or category (stores that sold none are left out). */
export interface VenueQtyRow {
  venueId: string;
  qty: number;
  revenueCents: number;
}

export interface Summary {
  grossCents: number;
  netCents: number;
  taxCents: number;
  checkCount: number;
  avgCheckCents: number;
  voidCount: number;
  voidAmountCents: number;
  refundCount: number;
  refundAmountCents: number;
  corkageCents: number;
  serviceChargeCents: number;
  byDay: DayRow[];
  /** One row per in-scope store. */
  byVenue: VenueSummaryRow[];
}

export interface TaxReport {
  ratePercent: number;
  rows: DayRow[];
  totals: { grossCents: number; netCents: number; taxCents: number; checkCount: number };
  byVenue: VenueSummaryRow[];
}

export type TenderType = "CASH" | "CARD" | "BANK_TRANSFER" | "STRIPE";

export interface PaymentRow {
  type: TenderType;
  amountCents: number;
  count: number;
}

export interface PaymentsReport {
  rows: PaymentRow[];
  totalCents: number;
  byVenue: { venueId: string; venueName: string; totalCents: number; rows: PaymentRow[] }[];
}

export interface ItemReportRow {
  itemId: string | null;
  nameFr: string;
  nameEn: string;
  categoryId: string | null;
  categoryNameFr: string | null;
  categoryNameEn: string | null;
  qty: number;
  revenueCents: number;
  byVenue: VenueQtyRow[];
}

export interface ItemsReport {
  rows: ItemReportRow[];
  byVenue: VenueTotalRow[];
}

export interface HourlyRow {
  hour: number;
  grossCents: number;
  checkCount: number;
  byVenue: { venueId: string; grossCents: number; checkCount: number }[];
}

export interface HourlyReport {
  rows: HourlyRow[];
  byVenue: VenueTotalRow[];
}

export interface ZoneRow {
  zoneId: string;
  zoneNameFr: string;
  zoneNameEn: string;
  grossCents: number;
  checkCount: number;
  venueId: string;
}

export interface ZoneTableRow {
  zoneId: string;
  zoneNameEn: string;
  tableId: string;
  tableLabel: string;
  grossCents: number;
  checkCount: number;
  venueId: string;
}

export interface TablesReport {
  byZone: ZoneRow[];
  byTable: ZoneTableRow[];
  byVenue: VenueTotalRow[];
}

export interface VoidRow {
  checkId: number;
  voidedAt: string;
  tableLabel: string;
  amountCents: number;
  reason: string;
  voidedBy: string;
  venueId: string;
}

export interface ExceptionsReport {
  voids: VoidRow[];
  voidCount: number;
  voidAmountCents: number;
  corkageCents: number;
  byVenue: { venueId: string; venueName: string; voidCount: number; voidAmountCents: number; corkageCents: number }[];
}

export interface RefundReasonRow {
  reason: string;
  count: number;
  grossCents: number;
  netCents: number;
  taxCents: number;
}

export interface RefundListRow {
  refundId: number;
  checkId: number | null;
  createdAt: string | null;
  tableLabel: string | null;
  tenderType: TenderType | null;
  reason: string | null;
  grossCents: number;
  netCents: number;
  taxCents: number;
  venueId: string;
}

export interface RefundsReport {
  count: number;
  grossCents: number;
  netCents: number;
  taxCents: number;
  byReason: RefundReasonRow[];
  byTender: { type: TenderType; amountCents: number; count: number }[];
  rows: RefundListRow[];
  byVenue: {
    venueId: string;
    venueName: string;
    count: number;
    grossCents: number;
    netCents: number;
    taxCents: number;
  }[];
}

export interface CashMovementRow {
  movementId: number;
  createdAt: string | null;
  direction: "IN" | "OUT";
  amountCents: number;
  reason: string | null;
  user: string | null;
  venueId: string;
}

export interface CashMovementsReport {
  paidInCents: number;
  paidOutCents: number;
  netCents: number;
  inCount: number;
  outCount: number;
  rows: CashMovementRow[];
  byVenue: {
    venueId: string;
    venueName: string;
    paidInCents: number;
    paidOutCents: number;
    netCents: number;
    inCount: number;
    outCount: number;
  }[];
}

export interface TenderBreakdownRow {
  type: TenderType;
  amountCents: number;
  count: number;
}

export interface Shift {
  shiftId: number;
  status: "OPEN" | "CLOSED";
  openedAt: string;
  closedAt: string | null;
  openedBy: string;
  closedBy: string | null;
  openingFloatCents: number;
  revenueCents: number;
  transactionCount: number;
  avgCheckCents: number;
  corkageCents: number;
  tenderBreakdown: TenderBreakdownRow[];
  expectedCashCents: number | null;
  closingCountCents: number | null;
  overShortCents: number | null;
  venueId: string;
}

export interface ShiftsReport {
  rows: Shift[];
  byVenue: {
    venueId: string;
    venueName: string;
    shiftCount: number;
    openCount: number;
    revenueCents: number;
    transactionCount: number;
    overShortCents: number;
  }[];
}

export interface JournalLine {
  nameFr: string | null;
  nameEn: string | null;
  qty: number;
  unitPriceCents: number;
  lineTotalCents: number;
}

export interface JournalRow {
  checkId: number;
  status: "CLOSED" | "VOID";
  closedAt: string;
  tableLabel: string;
  zoneNameEn: string;
  grandTotalCents: number;
  taxIncludedCents: number;
  tenderTypes: TenderType[];
  lines: JournalLine[];
  venueId: string;
}

export interface JournalReport {
  total: number;
  rows: JournalRow[];
  /** Per store over the whole filtered range (not just the page). */
  byVenue: { venueId: string; venueName: string; closedCount: number; voidCount: number; closedCents: number }[];
}

export interface MenuVariant {
  id: string;
  labelFr: string;
  labelEn: string;
  priceCents: number;
  sortOrder: number;
}

export interface MenuItem {
  id: string;
  nameFr: string;
  nameEn: string;
  categoryId: string;
  abbrev: string;
  isAlcohol: boolean;
  active: boolean;
  photoVersion: number | null;
  variants: MenuVariant[];
  /** The store this row comes from (a combined view lists every store's items). */
  venueId: string;
}

export interface MenuCategory {
  id: string;
  nameFr: string;
  nameEn: string;
  sortOrder: number;
}

export interface MenuResponse {
  categories: MenuCategory[];
  items: MenuItem[];
}

// --- staff + grants (CONTRACT §7) — read-only, pushed up by each store ---

export type StaffRole = "MANAGER" | "SERVER";

export interface StaffMember {
  id: string;
  name: string;
  role: StaffRole;
  active: boolean;
  /** Per-staff overrides set on the store; absent perms inherit the role default. */
  overrides: Record<string, boolean>;
  /** The store this member works at (ids repeat across stores). */
  venueId: string;
}

export interface StaffListResponse {
  staff: StaffMember[];
  /** role → permission → granted, for the selected store. */
  roleGrants: Record<string, Record<string, boolean>>;
  /** Every in-scope store's matrix (the combined view shows one per store). */
  venueGrants: { venueId: string; roleGrants: Record<string, Record<string, boolean>> }[];
  /** The fixed permission vocabulary, in display order. */
  permissions: string[];
}

// --- venues + device pairing ---

export interface Venue {
  id: string;
  name: string;
  timezone: string;
  subdomain: string;
  storeUrl: string | null;
  storeOnline: boolean;
  storeSeenAt: string | null;
}

export interface VenuesResponse {
  venues: Venue[];
}

// POST /v1/venues/{id}/pairing-codes — single-use, 15-minute TTL, shown once.
export interface PairingCodeResponse {
  code: string;
  expiresAt: string;
  url: string | null;
}

export interface PairedDevice {
  deviceId: string;
  name: string;
  pairedAt: string | null;
  lastSeenAt: string | null;
  revoked: boolean;
  /** Set as soon as the owner revokes; `revoked` flips true once the store confirms. */
  revokeRequestedAt: string | null;
}

export interface DevicesResponse {
  devices: PairedDevice[];
}

/** Heartbeat liveness: online < 60 s, stale up to 10 min, offline beyond or never. */
export type StoreLinkStatus = "online" | "stale" | "offline";

// GET /v1/devices (?venue=) — each in-scope store's POS, from its heartbeat.
export interface StorePos {
  venueId: string;
  venueName: string;
  status: StoreLinkStatus;
  lastSeenAt: string | null;
  /** Seconds since the last heartbeat when the server answered; null = never. */
  secondsSinceSeen: number | null;
  lanUrl: string | null;
  publicUrl: string | null;
  installId: string | null;
  appVersion: string | null;
  contractVersion: number | null;
  /** The store's device registry, as its heartbeat mirrors it. */
  devices: PairedDevice[];
}

export interface StorePosResponse {
  stores: StorePos[];
  onlineSeconds: number;
  staleMinutes: number;
}
