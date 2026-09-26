// Shapes from cloud/API.md. All money integer cents; all timestamps ISO-8601
// instants carrying the venue's offset (the leading wall clock is venue-local).

export interface Me {
  email: string;
  displayName: string;
  /** The tenant's first store; prefer tenantName for the group. */
  venueName: string;
  tenantName: string;
}

/** ISO 4217 code: "CAD", "USD". An older API omits it on rows → CAD. */
export type Currency = string;

/** One fixed conversion rate from config: 1 [from] = [rate] [to]. */
export interface FxRate {
  from: Currency;
  to: Currency;
  /** Decimal string, e.g. "1.37". */
  rate: string;
}

/**
 * How a report's money reads. One currency in scope → `currency` is it and
 * every figure is exact. Several (All stores across countries) → combined
 * figures are converted into `reportingCurrency` at the fixed `rates` and
 * `approximate` is true; per-store rows stay exact in their own currency.
 * `convertible` false = a rate is missing, so combined figures are not real.
 */
export interface MoneyScope {
  currency: Currency;
  approximate: boolean;
  reportingCurrency: Currency;
  currencies: Currency[];
  rates: FxRate[];
  convertible: boolean;
}

/** One tax code's net amount in one currency (sales less refunds). */
export interface TaxCodeRow {
  code: string;
  labelFr: string;
  labelEn: string;
  ratePercent: string;
  currency: Currency;
  amountCents: number;
}

/** Exact headline figures of the stores selling in one currency. */
export interface CurrencySummaryRow {
  currency: Currency;
  venueIds: string[];
  grossCents: number;
  netCents: number;
  taxCents: number;
  checkCount: number;
  avgCheckCents: number;
  voidCount: number;
  voidAmountCents: number;
  refundCount: number;
  refundAmountCents: number;
  /** grossCents in the reporting currency at the fixed rate; null = no rate. */
  grossReportingCents?: number | null;
  /** Net cash rounding (cash tenders' 5¢ adjustments less cash refunds'), signed cents in this row's currency. Absent from older APIs. */
  cashRoundingCents?: number;
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
  /** GST / QST inside taxCents, as charged (0 for sales without a breakdown). */
  gstCents: number;
  qstCents: number;
  currency?: Currency;
  /** Every tax code the store charged (sales less refunds). */
  taxes?: TaxCodeRow[];
  /** Net cash rounding (cash tenders' 5¢ adjustments less cash refunds'), signed cents in this row's currency. Absent from older APIs. */
  cashRoundingCents?: number;
}

export interface ByVenueReport {
  venues: VenueSummaryRow[];
  grossCents: number;
  checkCount: number;
  byCurrency?: CurrencySummaryRow[];
  money?: MoneyScope;
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
  gstCents: number;
  qstCents: number;
  currency?: Currency;
}

export interface DayRow {
  date: string;
  grossCents: number;
  netCents: number;
  taxCents: number;
  checkCount: number;
  /** The same day per in-scope store (zeros included). */
  byVenue: VenueDayRow[];
  gstCents: number;
  qstCents: number;
}

/** One store's totals in the items / categories / hourly / tables reports. */
export interface VenueTotalRow {
  venueId: string;
  venueName: string;
  grossCents: number;
  checkCount: number;
  qty: number;
  currency?: Currency;
}

/** One store's share of an item or category (stores that sold none are left out). */
export interface VenueQtyRow {
  venueId: string;
  qty: number;
  revenueCents: number;
  currency?: Currency;
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
  /** Exact totals per currency (one row unless the scope spans countries). */
  byCurrency?: CurrencySummaryRow[];
  /** Net cash rounding, signed cents; null when the scope mixes currencies (never summed across them). Absent from older APIs. */
  cashRoundingCents?: number | null;
  money?: MoneyScope;
}

/** One tax the in-range sales were charged, as the store labelled it. */
export interface TaxRate {
  code: string;
  labelFr: string;
  labelEn: string;
  /** Decimal string, e.g. "9.975". */
  ratePercent: string;
  currency?: Currency;
}

export interface TaxReport {
  rates: TaxRate[];
  rows: DayRow[];
  totals: {
    grossCents: number;
    netCents: number;
    taxCents: number;
    gstCents: number;
    qstCents: number;
    checkCount: number;
    /** Net cash rounding, signed cents; null when the scope mixes currencies (never summed across them). Absent from older APIs. */
    cashRoundingCents?: number | null;
  };
  byVenue: VenueSummaryRow[];
  byTax?: TaxCodeRow[];
  byCurrency?: CurrencySummaryRow[];
  money?: MoneyScope;
}

export type TenderType = "CASH" | "CARD" | "BANK_TRANSFER" | "STRIPE";

export interface PaymentRow {
  type: TenderType;
  amountCents: number;
  count: number;
}

export interface VenuePayments {
  venueId: string;
  venueName: string;
  totalCents: number;
  rows: PaymentRow[];
  currency?: Currency;
  /** Net cash rounding (cash tenders' 5¢ adjustments less cash refunds'), signed cents in this row's currency. Absent from older APIs. */
  cashRoundingCents?: number;
}

export interface CurrencyPayments {
  currency: Currency;
  totalCents: number;
  rows: PaymentRow[];
  /** Net cash rounding (cash tenders' 5¢ adjustments less cash refunds'), signed cents in this row's currency. Absent from older APIs. */
  cashRoundingCents?: number;
}

export interface PaymentsReport {
  rows: PaymentRow[];
  totalCents: number;
  byVenue: VenuePayments[];
  /** The payment mix per currency, exact. */
  byCurrency?: CurrencyPayments[];
  /** Net cash rounding, signed cents; null when the scope mixes currencies (never summed across them). Absent from older APIs. */
  cashRoundingCents?: number | null;
  money?: MoneyScope;
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
  /** An item is one row per currency. */
  currency?: Currency;
}

export interface ItemsReport {
  rows: ItemReportRow[];
  byVenue: VenueTotalRow[];
  money?: MoneyScope;
}

export interface HourlyRow {
  hour: number;
  grossCents: number;
  checkCount: number;
  byVenue: { venueId: string; grossCents: number; checkCount: number; currency?: Currency }[];
}

export interface HourlyReport {
  rows: HourlyRow[];
  byVenue: VenueTotalRow[];
  money?: MoneyScope;
}

export interface ZoneRow {
  zoneId: string;
  zoneNameFr: string;
  zoneNameEn: string;
  grossCents: number;
  checkCount: number;
  venueId: string;
  currency?: Currency;
}

export interface ZoneTableRow {
  zoneId: string;
  zoneNameEn: string;
  tableId: string;
  tableLabel: string;
  grossCents: number;
  checkCount: number;
  venueId: string;
  currency?: Currency;
}

export interface TablesReport {
  byZone: ZoneRow[];
  byTable: ZoneTableRow[];
  byVenue: VenueTotalRow[];
  money?: MoneyScope;
}

export interface VoidRow {
  checkId: number;
  voidedAt: string;
  tableLabel: string;
  amountCents: number;
  reason: string;
  voidedBy: string;
  venueId: string;
  currency?: Currency;
}

export interface ExceptionsReport {
  voids: VoidRow[];
  voidCount: number;
  voidAmountCents: number;
  corkageCents: number;
  byVenue: {
    venueId: string;
    venueName: string;
    voidCount: number;
    voidAmountCents: number;
    corkageCents: number;
    currency?: Currency;
  }[];
  money?: MoneyScope;
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
  currency?: Currency;
  /** Cash handed back less the gross refunded (the 5¢ rounding), signed cents. */
  roundingAdjustmentCents?: number;
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
    currency?: Currency;
    /** Net rounding of this store's cash refunds, signed cents. */
    roundingAdjustmentCents?: number;
  }[];
  money?: MoneyScope;
}

export interface CashMovementRow {
  movementId: number;
  createdAt: string | null;
  direction: "IN" | "OUT";
  amountCents: number;
  reason: string | null;
  user: string | null;
  venueId: string;
  currency?: Currency;
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
    currency?: Currency;
  }[];
  money?: MoneyScope;
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
  currency?: Currency;
  /** Net cash rounding of the shift, signed cents in its store's currency. */
  cashRoundingCents?: number | null;
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
    currency?: Currency;
  }[];
  money?: MoneyScope;
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
  currency?: Currency;
}

export interface JournalReport {
  total: number;
  rows: JournalRow[];
  /** Per store over the whole filtered range (not just the page). */
  byVenue: {
    venueId: string;
    venueName: string;
    closedCount: number;
    voidCount: number;
    closedCents: number;
    currency?: Currency;
  }[];
  money?: MoneyScope;
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
  /** ISO codes and the store kind; an older API omits them → CAD / CA / restaurant. */
  currency?: Currency;
  country?: string;
  kind?: "restaurant" | "retail" | string;
}

export interface VenuesResponse {
  venues: Venue[];
  /** The currency "All stores" converts into, approximately. */
  reportingCurrency?: Currency;
  /** Fixed rates into the reporting currency (only those configured). */
  rates?: FxRate[];
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

// ── stock (retail stores; cloud-owned, GET /v1/stock) ──────────────────
export interface StockRow {
  venueId: string;
  itemId: string;
  name: string;
  categoryId: string;
  barcode?: string | null;
  active: boolean;
  /** Since the last count (all time when never counted). */
  received: number;
  sold: number;
  adjusted: number;
  /** Units back from by-line refunds. */
  returned?: number;
  /** The last count at the store, if any. */
  countedQty?: number | null;
  countedAt?: string | null;
  onHand: number;
  reorderLevel?: number | null;
  low: boolean;
}

export interface VenueStockRow {
  venueId: string;
  venueName: string;
  products: number;
  onHand: number;
  lowCount: number;
}

export interface StockResponse {
  rows: StockRow[];
  byVenue: VenueStockRow[];
  totalOnHand: number;
  lowCount: number;
  /** False when no store in scope is retail. */
  retail: boolean;
}

export interface StockMovement {
  id: number;
  venueId: string;
  itemId: string;
  kind: "RECEIVED" | "ADJUSTMENT" | "COUNT" | string;
  qty: number;
  note: string;
  createdBy: string;
  createdAt: string;
  /** portal | store */
  source?: string;
}

export interface StockMovementsResponse {
  movements: StockMovement[];
}

// counts and deliveries recorded at the store (GET /v1/stock/counts, /receipts)
export interface StockCountLine {
  itemId: string;
  name: string;
  counted: number;
  /** The cloud's figure at the count time; null = no history. */
  expected?: number | null;
  variance?: number | null;
  countedAt: string;
}

export interface StockCount {
  venueId: string;
  venueName: string;
  countId: string;
  name: string;
  submittedBy?: string | null;
  approvedBy?: string | null;
  startedAt?: string | null;
  submittedAt: string;
  products: number;
  units: number;
  varianceLines: number;
  varianceUnits: number;
  lines: StockCountLine[];
}

export interface StockCountsResponse {
  counts: StockCount[];
  retail: boolean;
}

export interface StockReceipt {
  venueId: string;
  venueName: string;
  receiptId: string;
  supplier: string;
  reference: string;
  receivedBy?: string | null;
  receivedAt: string;
  units: number;
  lines: { itemId: string; name: string; qty: number }[];
}

export interface StockReceiptsResponse {
  receipts: StockReceipt[];
  retail: boolean;
}

// GET /v1/stock/reorder-suggestions
export interface ReorderRow {
  venueId: string;
  venueName: string;
  itemId: string;
  name: string;
  categoryId: string;
  barcode?: string | null;
  onHand: number;
  soldInWindow: number;
  avgDaily: number;
  target: number;
  suggested: number;
  reorderLevel?: number | null;
  low: boolean;
}

export interface ReorderResponse {
  rows: ReorderRow[];
  days: number;
  coverDays: number;
  toOrder: number;
  units: number;
  retail: boolean;
}

export interface LowCountResponse {
  lowCount: number;
  retail: boolean;
}
