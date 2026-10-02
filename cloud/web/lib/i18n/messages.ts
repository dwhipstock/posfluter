// Portal UI strings, French and English inline — one object, compile-checked keys.
// French is first-class: it is the default and the source of truth for shared
// domain terms, which mirror the POS client (client/lib/i18n.dart) so the
// portal and the till say the same words for the same things.
//
// French is Québec French (fr-CA), vous throughout. Write plain spaces around
// French punctuation — "Magasin : {store}", « bas », "5 %" — and `m` turns them
// into no-break spaces, so a line never wraps before a colon or a quote mark.
//
// Interpolation: values may contain {placeholders}; pass vars to t().
// Money ($, grouping, cents) lives in lib/format.ts and follows the locale.

// Each client offers some of these (its brand pack's `locales`, e.g. fr/en for
// a Québec pub, en/es for a California shop). Spanish (US) lives in
// messages.es.ts, keyed by the same MsgKey — a missing Spanish key is a
// compile error. German lives in messages.de.ts and Afrikaans in
// messages.af.ts, the same way.
export type Locale = "fr" | "en" | "es" | "de" | "af";

type Msg = { fr: string; en: string };

const NBSP = " ";

/** French typography: a no-break space before : ; ! ? » % ¢ $ and after «. */
export function frTypography(s: string): string {
  return s
    .replace(/ ([:;!?»%¢$])/g, `${NBSP}$1`)
    .replace(/« /g, `«${NBSP}`);
}

const m = (fr: string, en: string): Msg => ({ fr: frTypography(fr), en });

export const messages = {
  // ── common ────────────────────────────────────────────────────────────
  brand_tagline: m("Portail du propriétaire", "Owner portal"),
  brand_sub: m("Gestion", "Manager"),
  save: m("Enregistrer", "Save"),
  cancel: m("Annuler", "Cancel"),
  close: m("Fermer", "Close"),
  delete: m("Supprimer", "Delete"),
  keep_it: m("Conserver", "Keep it"),
  add: m("Ajouter", "Add"),
  retry: m("Réessayer", "Retry"),
  to: m("au", "to"),
  prev: m("Précédent", "Prev"),
  next: m("Suivant", "Next"),
  couldnt_load: m("Chargement impossible", "Couldn’t load this"),
  something_wrong: m("Une erreur s’est produite", "Something went wrong"),
  switch_language: m("Changer de langue", "Switch language"),
  login_welcome: m("Bon retour", "Welcome back"),
  login_welcome_body: m(
    "Connectez-vous pour voir vos ventes, votre stock et votre équipe.",
    "Sign in to see your sales, stock and team."
  ),
  // the portal's /staff-app page when the store can't be reached (no app chrome)
  staff_offline_title: m("Personnel", "Staff"),
  staff_offline_body: m(
    "Impossible de joindre l’établissement pour l’instant. Connectez-vous au Wi-Fi de l’établissement, puis réessayez.",
    "Can't reach the store right now. Connect to the store Wi-Fi and try again."
  ),
  staff_offline_hint: m(
    "Ou ouvrez l’appli du personnel directement sur le réseau de l’établissement :",
    "Or open the staff app directly on the store network:"
  ),

  // ── export (PDF / Excel, every report) ────────────────────────────────
  export: m("Exporter", "Export"),
  export_pdf: m("PDF", "PDF"),
  export_excel: m("Excel (.xlsx)", "Excel (.xlsx)"),
  export_kpi_sheet: m("Résumé", "Summary"),
  export_generated: m("Généré le {when}", "Generated {when}"),
  // report-specific labels only the exports need
  svc_charge: m("Frais de service", "Service charge"),
  summary_by_day: m("Ventes par jour", "Daily sales"),
  tables_by_table: m("Par table", "By table"),
  col_shift: m("Quart", "Shift"),
  col_status: m("Statut", "Status"),

  // ── nav ───────────────────────────────────────────────────────────────
  // ── store picker (every page: all stores combined, or one store) ─────
  store_all: m("Tous les établissements", "All stores"),
  store_label: m("Établissement", "Store"),
  col_store: m("Établissement", "Store"),
  store_breakdown_title: m("Par établissement", "By store"),
  store_breakdown_sub: m(
    "Chaque établissement selon ses propres journées d’affaires",
    "Each store over its own business days"
  ),
  store_pick_hint: m(
    "Choisissez un établissement dans l’en-tête pour jumeler un terminal.",
    "Pick a store in the header to pair a terminal."
  ),

  nav_dashboard: m("Tableau de bord", "Dashboard"),
  nav_reports: m("Rapports", "Reports"),
  nav_menu: m("Menu", "Menu"),
  nav_account: m("Compte", "Account"),

  // ── login ─────────────────────────────────────────────────────────────
  login_email: m("Courriel", "Email"),
  login_password: m("Mot de passe", "Password"),
  login_signin: m("Se connecter", "Sign in"),
  login_2fa_title: m("Code de vérification", "Two-factor code"),
  login_2fa_body: m(
    "Entrez le code à 6 chiffres de votre application d’authentification.",
    "Enter the 6-digit code from your authenticator app."
  ),
  login_setup_title: m("Activer la validation en deux étapes", "Set up two-factor"),
  login_setup_body: m(
    "Balayez le code avec Google Authenticator (ou toute autre application TOTP), puis entrez le code à 6 chiffres pour confirmer.",
    "Scan with Google Authenticator (or any TOTP app), then enter the 6-digit code to confirm."
  ),
  login_back: m("Retour à la connexion", "Back to sign in"),
  login_copy_secret: m("Copier la clé secrète", "Copy secret"),
  login_copy_failed: m(
    "Copie impossible — maintenez le doigt sur le code pour le copier.",
    "Couldn’t copy — long-press the code instead"
  ),

  // ── two-factor: backup codes + recovery ───────────────────────────────
  login_use_backup: m("Téléphone perdu ? Utilisez un code de secours", "Lost your phone? Use a backup code"),
  login_use_authenticator: m("Utiliser plutôt l’application d’authentification", "Use your authenticator instead"),
  login_backup_title: m("Code de secours", "Backup code"),
  login_backup_body: m(
    "Entrez l’un des codes de secours conservés lors de la configuration. Chaque code ne sert qu’une fois.",
    "Enter one of the backup codes you saved during setup. Each works once."
  ),
  login_verify: m("Vérifier", "Verify"),
  login_backup_save_title: m("Conservez vos codes de secours", "Save your backup codes"),
  login_backup_save_body: m(
    "Si vous perdez ou réinitialisez votre téléphone, l’un de ces codes vous permettra de vous connecter. Gardez-les en lieu sûr.",
    "If you lose or wipe your phone, use one of these to sign in. Keep them somewhere safe."
  ),
  login_backup_warn: m("Ces codes ne seront plus affichés.", "These codes won’t be shown again."),
  login_backup_copy: m("Tout copier", "Copy all"),
  login_backup_copied: m("Copié", "Copied"),
  login_backup_download: m("Télécharger", "Download"),
  login_backup_continue: m("C’est noté — continuer", "I’ve saved them — continue"),

  // ── auth error copy (mapped from the server's error `code`) ────────────
  err_bad_credentials: m("Courriel ou mot de passe incorrect", "Wrong email or password"),
  err_bad_totp: m("Code incorrect. Réessayez.", "That code isn’t right. Try again."),
  err_bad_pending_token: m(
    "Votre connexion a expiré. Veuillez recommencer.",
    "Your sign-in timed out. Please start again."
  ),
  err_rate_limited: m(
    "Trop de tentatives. Attendez un moment, puis réessayez.",
    "Too many attempts. Wait a moment and try again."
  ),
  err_demo_mode_off: m("La connexion démo est désactivée.", "Demo sign-in is turned off."),
  // header badge while the portal runs with PORTAL_DEMO_MODE=on
  demo_badge: m("Mode démo", "Demo mode"),
  // devices page, for a manager (e.g. the demo login)
  devices_owner_only: m(
    "Seul le propriétaire peut jumeler, révoquer ou retirer des terminaux.",
    "Only the owner can pair, revoke or remove terminals."
  ),
  // shown on /login after the session ends on its own
  login_signed_out_idle: m(
    "Déconnecté après {n} minutes d’inactivité",
    "Signed out after {n} minutes of inactivity"
  ),
  login_signed_out_expired: m(
    "Votre session a expiré. Veuillez vous reconnecter.",
    "Your session has expired. Please sign in again."
  ),

  // ── date range ────────────────────────────────────────────────────────
  preset_today: m("Aujourd’hui", "Today"),
  preset_yesterday: m("Hier", "Yesterday"),
  preset_7d: m("7 jours", "7 days"),
  preset_30d: m("30 jours", "30 days"),
  preset_month: m("Ce mois-ci", "This month"),
  preset_custom: m("Personnalisé", "Custom"),
  // header captions (a touch more descriptive than the chips)
  range_today: m("Aujourd’hui", "Today"),
  range_yesterday: m("Hier", "Yesterday"),
  range_7d: m("7 derniers jours", "Last 7 days"),
  range_30d: m("30 derniers jours", "Last 30 days"),
  range_month: m("Ce mois-ci", "This month"),

  // ── tenders (shared with POS) ─────────────────────────────────────────
  tender_CASH: m("Comptant", "Cash"),
  tender_CARD: m("Carte", "Card"),
  tender_BANK_TRANSFER: m("Virement bancaire", "Bank transfer"),
  tender_STRIPE: m("Carte (Stripe)", "Card (Stripe)"),
  tender_TERMINAL: m("Carte (terminal)", "Card (terminal)"),

  // ── dashboard ─────────────────────────────────────────────────────────
  dash_title: m("Tableau de bord", "Dashboard"),
  kpi_gross: m("Ventes brutes", "Gross"),
  kpi_net: m("Net (avant taxes)", "Net (before tax)"),
  kpi_tax: m("Taxes", "Sales tax"),
  kpi_checks: m("Additions", "Checks"),
  kpi_avg_check: m("Addition moyenne", "Avg check"),
  dash_daily_trend: m("Tendance quotidienne", "Daily trend"),
  dash_payment_mix: m("Répartition des paiements", "Payment mix"),
  dash_sales_by_hour: m("Ventes par heure", "Sales by hour"),
  dash_top_items: m("Meilleurs vendeurs", "Top items"),
  dash_all_items: m("Tout voir", "All items"),
  series_gross: m("Ventes brutes", "Gross"),
  series_net: m("Net", "Net"),
  empty_no_sales_range: m("Aucune vente pour cette période", "No sales in this range yet"),
  empty_no_payments: m("Aucun paiement pour l’instant", "No payments yet"),
  empty_no_payments_hint: m("Les additions réglées apparaîtront ici.", "Settled checks will show up here."),
  empty_quiet: m("C’est calme pour l’instant", "Quiet so far"),
  empty_quiet_hint: m("Les ventes par heure s’afficheront à mesure que les additions sont fermées.", "Hourly sales will appear as checks close."),
  empty_nothing_sold: m("Rien de vendu pour l’instant", "Nothing sold yet"),
  empty_nothing_sold_hint: m("Les meilleurs vendeurs seront classés ici.", "Top sellers will rank here."),

  // ── reports index ─────────────────────────────────────────────────────
  reports_title: m("Rapports", "Reports"),
  reports_sub: m("Choisissez un rapport — tous utilisent la même période.", "Pick a report — every one shares the same date range."),
  report_tax_title: m("Taxes", "Sales tax"),
  report_tax_desc: m("Détail quotidien des taxes perçues", "Daily breakdown of recorded sales tax"),
  report_payments_title: m("Paiements", "Payments"),
  report_payments_desc: m("Répartition comptant, carte et virement", "Cash, card and bank transfer mix"),
  report_items_title: m("Articles", "Items"),
  report_items_desc: m("Ce qui se vend, en quantité et en ventes", "What sells, by quantity and revenue"),
  report_tables_title: m("Tables et zones", "Tables & zones"),
  report_tables_desc: m("Où se font les ventes", "Where the revenue happens"),
  report_hourly_title: m("Par heure", "Hourly"),
  report_hourly_desc: m("Ventes selon l’heure de la journée", "Sales by hour of day"),
  report_exceptions_title: m("Exceptions", "Exceptions"),
  report_exceptions_desc: m("Annulations et droits de bouchon", "Voids and corkage"),
  report_shifts_title: m("Quarts de travail", "Shifts"),
  report_shifts_desc: m("Rapports Z avec écarts de caisse", "Z-reports with cash over/short"),
  report_journal_title: m("Journal", "Journal"),
  report_journal_desc: m("Toutes les additions, avec recherche", "Every check, searchable"),
  report_refunds_title: m("Remboursements", "Refunds"),
  report_refunds_desc: m("Montants remboursés par motif, taxes en sus", "Refund amounts by reason, net of tax"),
  report_cash_title: m("Entrées et sorties de caisse", "Cash movements"),
  report_cash_desc: m("Argent ajouté ou retiré hors vente, avec le motif", "Non-sale cash in/out, with reasons"),
  report_fuel_title: m("Carburant et magasin", "Fuel & in-store"),
  report_fuel_desc: m("Ventes et marge du magasin, gallons et marge par carburant", "In-store sales and margin, fuel gallons and margin by grade"),

  // ── tax report ────────────────────────────────────────────────────────
  tax_title: m("Rapport des taxes", "Sales tax report"),
  tax_rate_badge: m("{label} {rate} %", "{label} {rate}%"),
  tax_note: m(
    "TPS et TVQ ajoutées aux prix avant taxes, telles que perçues à chaque vente — net = brut − taxes.",
    "GST and QST added on top of pre-tax prices, as charged on each sale — net = gross − tax."
  ),
  tax_note_generic: m(
    "Taxes ajoutées aux prix avant taxes, telles que perçues à chaque vente — net = brut − taxes.",
    "Taxes added on top of pre-tax prices, as charged on each sale — net = gross − tax."
  ),
  tax_no_breakdown: m(
    "Les ventes synchronisées sans détail des taxes comptent pour 0 en TPS et en TVQ.",
    "Sales synced without a tax breakdown count as 0 GST and QST."
  ),
  col_date: m("Date", "Date"),
  col_gross: m("Brut", "Gross"),
  col_net: m("Net", "Net"),
  col_tax: m("Taxes", "Tax"),
  col_gst: m("TPS", "GST"),
  col_qst: m("TVQ", "QST"),
  col_checks: m("Additions", "Checks"),
  col_total: m("Total", "Total"),
  tax_empty: m("Aucune vente pour cette période", "No sales in this range"),
  tax_empty_hint: m("Chaque jour avec des ventes aura sa ligne ici.", "Days with sales get a row here."),

  // ── payments report ───────────────────────────────────────────────────
  payments_title: m("Paiements", "Payments"),
  col_tender: m("Mode de paiement", "Tender"),
  col_payments: m("Paiements", "Payments"),
  col_amount: m("Montant", "Amount"),
  col_share: m("Part", "Share"),
  col_total_short: m("Total", "Total"),
  payments_empty: m("Aucun paiement pour cette période", "No payments in this range"),

  // ── items report ──────────────────────────────────────────────────────
  items_title: m("Articles", "Items"),
  items_tab_revenue: m("Ventes", "Revenue"),
  items_tab_qty: m("Quantité", "Quantity"),
  cat_all: m("Tout", "All"),
  col_item: m("Article", "Item"),
  col_category: m("Catégorie", "Category"),
  col_qty: m("Qté", "Qty"),
  col_revenue: m("Ventes", "Revenue"),
  items_empty: m("Rien de vendu pour cette période", "Nothing sold in this range"),
  items_empty_hint: m("Les articles apparaissent dès que les additions sont fermées.", "Items appear once checks close."),

  // ── hourly report ─────────────────────────────────────────────────────
  hourly_title: m("Par heure", "Hourly"),
  hourly_chart: m("Ventes par heure", "Sales by hour"),
  col_hour: m("Heure", "Hour"),
  hourly_empty: m("Aucune vente pour cette période", "No sales in this range"),

  // ── tables report ─────────────────────────────────────────────────────
  tables_title: m("Tables et zones", "Tables & zones"),
  tables_by_zone: m("Ventes par zone", "Revenue by zone"),
  col_table: m("Table", "Table"),
  col_zone: m("Zone", "Zone"),
  tables_zone_empty: m("Aucune donnée par zone pour cette période", "No zone data in this range"),
  tables_table_empty: m("Aucune donnée par table pour cette période", "No table data in this range"),
  tables_table_empty_hint: m("Les additions fermées sont comptées à leur table.", "Closed checks land on their table."),

  // ── exceptions report ─────────────────────────────────────────────────
  exceptions_title: m("Exceptions", "Exceptions"),
  exc_voids: m("Annulations", "Voids"),
  exc_void_amount: m("Montant annulé", "Void amount"),
  exc_corkage: m("Droit de bouchon", "Corkage"),
  col_check: m("Addition", "Check"),
  col_voided: m("Annulée le", "Voided"),
  col_reason: m("Motif", "Reason"),
  col_by: m("Par", "By"),
  exc_empty: m("Aucune annulation pour cette période", "No voids in this range"),
  exc_empty_hint: m("Des quarts sans accroc — rien n’a été annulé.", "Clean shifts — nothing was voided."),

  // ── Refunds report ────────────────────────────────────────────────────
  refunds_title: m("Remboursements", "Refunds"),
  refunds_note: m(
    "Les remboursements sont déduits des ventes et des taxes dans le résumé et le rapport des taxes.",
    "Refunds are netted out of sales and tax in the summary and tax reports."
  ),
  ref_count: m("Remboursements", "Refunds"),
  ref_amount: m("Total remboursé", "Total refunded"),
  ref_tax: m("Taxes remboursées", "Tax reversed"),
  refunds_by_reason: m("Par motif", "By reason"),
  refunds_by_tender: m("Par mode de paiement", "By tender"),
  refunds_list: m("Remboursements", "Refunds"),
  col_time: m("Heure", "Time"),
  refunds_empty: m("Aucun remboursement pour cette période", "No refunds in this range"),
  refunds_empty_hint: m("Aucune addition n’a été remboursée.", "No bills were refunded."),

  // ── Fuel report (a gas station) ───────────────────────────────────────
  fuel_title: m("Carburant et magasin", "Fuel & in-store"),
  fuel_note: m(
    "Le carburant est ce que les pompes ont distribué, taxes comprises. La monnaie rendue sur un prépaiement est un remboursement (voir Remboursements). Les ventes en magasin sont avant taxes et après promotions. La marge ne compte que ce dont le coût est connu.",
    "Fuel is what the pumps dispensed, taxes included. Unused prepay handed back is a refund (see Refunds). In-store sales are before tax and after promotions. Margin counts only what has a known cost."
  ),
  fuel_section: m("Carburant", "Fuel"),
  instore_section: m("Magasin", "In-store"),
  instore_margin: m("Marge du magasin", "In-store margin"),
  fuel_margin: m("Marge sur le carburant", "Fuel margin"),
  col_cost: m("Coût", "Cost"),
  col_margin: m("Marge", "Margin"),
  col_margin_pct: m("Marge %", "Margin %"),
  col_per_gal: m("¢/gal", "¢/gal"),
  fuel_cents_per_gal: m("{v} ¢/gal", "{v}¢/gal"),
  margin_amount: m("{amount} de marge", "{amount} margin"),
  fuel_sales_amount: m("{amount} de ventes de carburant", "{amount} in fuel sales"),
  instore_promotions: m("{amount} de promotions déduites", "{amount} in promotions taken off"),
  margin_uncosted_lines: m(
    "{n} articles vendus sans coût connu — hors de la marge",
    "{n} items sold without a known cost — left out of the margin"
  ),
  margin_uncosted_fills: m(
    "{n} pleins sans coût connu — hors de la marge",
    "{n} fill-ups without a known cost — left out of the margin"
  ),
  instore_empty: m("Aucune vente en magasin pour cette période", "No in-store sales in this range"),
  dash_fuel_details: m("Carburant et magasin", "Fuel & in-store"),
  instore_categories: m("Catégories du magasin", "In-store categories"),
  instore_by_category: m("Ventes par catégorie", "Sales by category"),
  instore_by_category_sub: m("Ventes nettes du magasin, avant taxes · marge où le coût est connu", "In-store net sales, before tax · margin where cost is known"),
  // a gas station's category ids (the store's own); names shown in the reader's language
  store_cat_drinks: m("Boissons", "Drinks"),
  store_cat_beer: m("Bière", "Beer"),
  store_cat_snacks: m("Collations", "Snacks"),
  store_cat_candy: m("Bonbons", "Candy"),
  store_cat_hot_food: m("Repas chauds", "Hot food"),
  store_cat_grocery: m("Épicerie", "Grocery"),
  store_cat_automotive: m("Automobile", "Automotive"),
  store_cat_health: m("Santé et beauté", "Health & beauty"),
  store_cat_general: m("Articles divers", "General merchandise"),
  store_cat_tobacco: m("Tabac et vapotage", "Tobacco & Vape"),
  store_cat_ice: m("Glace", "Ice"),
  store_cat_fuel: m("Carburant", "Fuel"),
  fuel_gallons: m("Gallons", "Gallons"),
  fuel_sales: m("Ventes de carburant", "Fuel sales"),
  fuel_fills: m("Pleins", "Fill-ups"),
  fuel_in_store: m("Ventes en magasin", "In-store sales"),
  fuel_in_store_sub: m("{n} ventes · avant taxes, après promotions", "{n} sales · before tax, after promotions"),
  fuel_by_grade: m("Par carburant", "By grade"),
  fuel_by_store: m("Carburant par magasin", "Fuel by store"),
  fuel_total: m("Total carburant", "Fuel total"),
  fuel_prepay: m("{n} prépaiements · {paid} payé d’avance, {back} rendu", "{n} prepaid · {paid} paid up front, {back} handed back"),
  col_grade: m("Carburant", "Grade"),
  // the store's grade codes (CONTRACT §2, Fuel); another code shows the store's own name
  fuel_grade_REG: m("Ordinaire", "Regular"),
  fuel_grade_MID: m("Intermédiaire", "Mid-Grade"),
  fuel_grade_PRE: m("Super", "Premium"),
  fuel_grade_DSL: m("Diesel", "Diesel"),
  fuel_empty: m("Aucun carburant vendu pour cette période", "No fuel sold in this range"),
  fuel_empty_hint: m("Les ventes de carburant s’affichent ici dès qu’une pompe est réglée.", "Fuel sales show here once a pump sale is settled."),

  // ── Cash movements report ─────────────────────────────────────────────
  cash_title: m("Entrées et sorties de caisse", "Cash movements"),
  cash_note: m(
    "Argent ajouté ou retiré hors vente — ajout de monnaie, décaissements, fournisseurs payés comptant.",
    "Non-sale cash in/out — float top-ups, pay-outs, suppliers paid in cash."
  ),
  cash_paid_in: m("Entrées", "Paid in"),
  cash_paid_out: m("Sorties", "Paid out"),
  cash_net: m("Net", "Net"),
  col_direction: m("Sens", "Direction"),
  cash_in_label: m("Entrée", "In"),
  cash_out_label: m("Sortie", "Out"),
  cash_empty: m("Aucune entrée ni sortie de caisse pour cette période", "No cash movements in this range"),
  cash_empty_hint: m("Aucun argent n’a été ajouté ni retiré.", "No cash was paid in or out."),

  // ── shifts report ─────────────────────────────────────────────────────
  shifts_title: m("Quarts de travail", "Shifts"),
  shift_n: m("Quart n° {id}", "Shift #{id}"),
  badge_live: m("EN COURS", "LIVE"),
  badge_closed: m("FERMÉ", "CLOSED"),
  shift_now: m("maintenant", "now"),
  shift_revenue: m("Ventes", "Revenue"),
  shift_checks: m("Additions", "Checks"),
  shift_avg: m("Moyenne", "Avg"),
  shift_over_short: m("Écart de caisse", "Over/short"),
  shifts_empty: m("Aucun quart pour cette période", "No shifts in this range"),
  shifts_empty_hint: m("Les rapports Z apparaissent à la fermeture des quarts.", "Z-reports appear when shifts close."),
  shift_opened: m("Ouvert", "Opened"),
  shift_closed: m("Fermé", "Closed"),
  shift_still_open: m("Toujours ouvert", "Still open"),
  shift_avg_check: m("Addition moyenne", "Avg check"),
  shift_corkage: m("Droit de bouchon", "Corkage"),
  shift_tenders: m("Modes de paiement", "Tenders"),
  shift_no_tenders: m("Aucun paiement enregistré.", "No tenders recorded."),
  shift_cash_drawer: m("Tiroir-caisse", "Cash drawer"),
  shift_opening_float: m("Fonds de caisse", "Opening float"),
  shift_expected_cash: m("Comptant attendu", "Expected cash"),
  shift_counted: m("Compté", "Counted"),

  // ── journal report ────────────────────────────────────────────────────
  journal_title: m("Journal", "Journal"),
  journal_search: m("Chercher un n° d’addition, une table ou un montant exact", "Search check #, table, or exact amount"),
  col_closed: m("Fermée le", "Closed"),
  badge_void: m("ANNULÉE", "VOID"),
  journal_open_item: m("Article libre", "Open item"),
  journal_tax_included: m("Taxes :", "Tax:"),
  journal_pagination: m("{from}–{to} sur {total}", "{from}–{to} of {total}"),
  journal_empty_search: m("Aucune addition ne correspond à votre recherche", "No checks match your search"),
  journal_empty_search_hint: m(
    "Essayez un numéro d’addition, le nom d’une table ou un montant exact.",
    "Try a check number, table label, or exact amount."
  ),
  journal_empty: m("Aucune addition pour cette période", "No checks in this range"),

  // ── account ───────────────────────────────────────────────────────────
  account_title: m("Compte", "Account"),
  account_signed_in: m("Connecté", "Signed in"),
  account_name: m("Nom", "Name"),
  account_email: m("Courriel", "Email"),
  account_venue: m("Établissement", "Venue"),
  account_not_signed_in: m("Non connecté.", "Not signed in."),
  account_sign_out: m("Se déconnecter", "Sign out"),
  account_footer: m("Portail infonuagique {brand} · v{version}", "{brand} cloud portal · v{version}"),
  account_display: m("Affichage", "Display"),
  account_language: m("Langue", "Language"),
  account_devices: m("Appareils", "Devices"),
  account_devices_hint: m("Jumeler et gérer les terminaux de caisse", "Pair and manage POS terminals"),

  // ── devices + pairing ─────────────────────────────────────────────────
  nav_devices: m("Appareils", "Devices"),
  devices_title: m("Appareils", "Devices"),
  devices_sub: m("Jumelez des terminaux de caisse et gérez les appareils connectés", "Pair POS terminals and manage connected devices"),
  devices_venue: m("Établissement", "Venue"),
  devices_no_venues: m("Aucun établissement sur ce compte pour l’instant", "No venues on this account yet"),
  devices_pair_title: m("Jumeler un terminal", "Pair a terminal"),
  devices_pair_sub: m(
    "Générez un code, puis entrez-le sur le terminal de caisse dans les 15 minutes.",
    "Generate a code, then enter it on the POS terminal within 15 minutes."
  ),
  devices_label: m("Nom (facultatif)", "Label (optional)"),
  devices_label_ph: m("p. ex. Tablette du bar", "e.g. Bar tablet"),
  devices_pair_button: m("Générer un code de jumelage", "Generate pairing code"),
  devices_pair_again: m("Générer un nouveau code", "Generate a new code"),
  devices_code_hint: m(
    "Entrez ce code sur le terminal de caisse — il ne s’affiche qu’une fois et ne sert qu’une fois.",
    "Enter this code on the POS terminal — it’s shown once and works once."
  ),
  devices_code_expires: m("Le code expire dans {time}", "Code expires in {time}"),
  devices_code_expired: m("Code expiré — générez-en un nouveau.", "Code expired — generate a new one."),
  devices_list_title: m("Appareils jumelés", "Paired devices"),
  devices_none: m("Aucun appareil jumelé pour l’instant", "No devices paired yet"),
  devices_none_hint: m(
    "Générez un code de jumelage ci-dessus pour connecter votre premier terminal.",
    "Generate a pairing code above to connect your first terminal."
  ),
  devices_status_active: m("Actif", "Active"),
  devices_status_revoking: m("Révocation…", "Revoking…"),
  devices_status_revoked: m("Révoqué", "Revoked"),
  devices_paired_on: m("Jumelé le {date}", "Paired {date}"),
  devices_last_seen: m("Vu {when}", "Last seen {when}"),
  devices_seen_never: m("Jamais connecté", "Never connected"),
  devices_seen_just_now: m("à l’instant", "just now"),
  devices_seen_min: m("il y a {n} min", "{n} min ago"),
  devices_seen_hr: m("il y a {n} h", "{n} hr ago"),
  devices_seen_day: m("il y a {n} jours", "{n} days ago"),
  devices_revoke: m("Révoquer", "Revoke"),
  devices_remove: m("Retirer", "Remove"),
  devices_revoke_q: m("Révoquer {name} ?", "Revoke {name}?"),
  devices_revoke_confirm: m(
    "Le terminal sera coupé de l’établissement et ne pourra plus utiliser la caisse tant qu’il ne sera pas jumelé de nouveau.",
    "The terminal will be cut off from the store and can’t use the POS until it’s paired again."
  ),
  devices_revoke_sent: m(
    "Révocation demandée — l’établissement la confirme d’ici quelques secondes.",
    "Revoke requested — the store confirms within a few seconds."
  ),
  // store POS (heartbeat) — each store's own POS, synced with its store key
  devices_stores_title: m("Caisses des établissements", "Store POS"),
  devices_stores_sub: m(
    "La caisse de chaque établissement se synchronise avec sa clé et donne signe de vie toutes les quelques secondes.",
    "Each store’s POS syncs with its store key and checks in every few seconds."
  ),
  devices_pos_online: m("En ligne", "Online"),
  devices_pos_stale: m("En retard", "Delayed"),
  devices_pos_offline: m("Hors ligne", "Offline"),
  devices_pos_never: m("Aucun signal reçu pour l’instant", "No check-in received yet"),
  devices_pos_never_hint: m(
    "Cet établissement n’a pas encore joint le nuage. Vérifiez la connexion Internet de la caisse et sa clé d’établissement.",
    "This store hasn’t reached the cloud yet. Check the POS’s internet connection and its store key."
  ),
  devices_pos_seen_sec: m("il y a {n} s", "{n}s ago"),
  devices_pos_lan: m("Adresse sur le réseau local", "LAN address"),
  devices_pos_public: m("Adresse publique", "Public address"),
  devices_staff_app: m("Application du personnel sur téléphone", "Staff phone app"),
  devices_staff_app_hint: m(
    "Le personnel balaie ce code (sur le Wi-Fi de l’établissement) pour ouvrir l’application de commande.",
    "Staff scan this (on the store’s Wi-Fi) to open the ordering app on their phone."
  ),
  devices_pos_install: m("Installation", "Install"),
  devices_pos_version: m("Version", "Version"),
  devices_pos_contract: m("contrat v{n}", "contract v{n}"),
  devices_pos_none_reported: m("Non signalé", "Not reported"),
  devices_pos_devices: m("Terminaux de cet établissement", "Terminals on this store"),
  devices_pos_devices_none: m(
    "Aucun terminal supplémentaire — la caisse de l’établissement suffit.",
    "No extra terminals — the store’s own POS is all that’s needed."
  ),
  devices_pos_empty: m("Aucun établissement dans cette vue.", "No stores in this view."),
  devices_extra_title: m("Jumeler un terminal supplémentaire", "Pair an extra terminal"),
  devices_extra_optional: m("Facultatif", "Optional"),
  devices_extra_sub: m(
    "Seulement pour ajouter un terminal à un établissement. La caisse de chaque établissement est déjà connectée — rien à jumeler.",
    "Only to add another terminal to a store. Each store’s POS is already connected — nothing to pair."
  ),
  devices_extra_show: m("Jumeler un terminal", "Pair a terminal"),
  devices_extra_hide: m("Masquer", "Hide"),
  devices_extra_store: m("Établissement", "Store"),

  // ── menu (two-way: edited here or on each store's tablet) ─────────────
  menu_title: m("Menu", "Menu"),
  menu_sub: m(
    "Modifiez le menu ici ou sur la tablette de chaque établissement ; les changements arrivent à sa prochaine synchronisation.",
    "Edit the menu here or on each store’s tablet; changes reach the store the next time it syncs."
  ),
  menu_off: m("Retiré du menu", "Off menu"),
  menu_ai_badge: m("IA", "AI"),
  menu_ai_generated: m("Photo générée par IA", "AI-generated photo"),
  menu_ai_enhanced: m("Photo réelle retouchée par IA", "Real photo, AI-enhanced"),
  menu_empty: m("Aucun menu synchronisé pour l’instant", "No menu synced yet"),
  menu_empty_hint: m(
    "Il apparaîtra dès que la tablette de l’établissement se connectera.",
    "It appears as soon as the store’s tablet connects."
  ),
  menu_cat_empty: m("Aucun article dans cette catégorie pour l’instant.", "No items here yet."),
  menu_search: m("Chercher un produit, une marque ou un code-barres", "Search a product, brand or barcode"),
  menu_no_match: m("Aucun produit ne correspond", "No products match"),
  menu_no_match_hint: m("Modifiez la recherche ou réinitialisez les filtres.", "Change the search or reset the filters."),
  menu_col_brand: m("Marque", "Brand"),
  menu_col_category: m("Catégorie", "Category"),
  menu_col_price: m("Prix", "Price"),
  menu_col_active: m("En vente", "On sale"),

  // ── product lists: search, 2-way filter, paging (Products, Stock) ─────
  catalog_category: m("Catégorie", "Category"),
  catalog_all_categories: m("Toutes les catégories", "All categories"),
  catalog_subcategory: m("Sous-catégorie", "Subcategory"),
  catalog_all_subcategories: m("Toutes les sous-catégories", "All subcategories"),
  catalog_size: m("Format", "Size"),
  catalog_all_sizes: m("Tous les formats", "All sizes"),
  catalog_reset: m("Réinitialiser", "Reset"),
  catalog_range: m("{from}–{to} sur {total}", "{from}–{to} of {total}"),
  catalog_products: m("{n} produits", "{n} products"),

  // ── staff + grants (read-only: each store's tablet owns its staff) ─────
  nav_staff: m("Personnel", "Staff"),
  staff_title: m("Personnel", "Staff"),
  staff_sub: m(
    "Le personnel et les autorisations se gèrent sur la tablette de chaque établissement.",
    "Staff and permissions are managed on each store’s tablet."
  ),
  staff_none: m("Aucun membre du personnel synchronisé pour l’instant", "No staff synced yet"),
  role_manager: m("Gérant", "Manager"),
  role_server: m("Serveur", "Server"),
  staff_active: m("Actif", "Active"),
  staff_inactive: m("Inactif", "Inactive"),
  staff_overrides_n: m("{n} exception(s)", "{n} overrides"),

  // grant matrix
  grants_roles_title: m("Autorisations par rôle", "Role permissions"),
  grants_roles_sub: m(
    "Valeurs par défaut de chaque rôle, telles que réglées sur la tablette de l’établissement.",
    "Defaults per role, as set on the store’s tablet."
  ),
  grants_overrides_title: m("Autorisations de cette personne", "This person’s permissions"),
  grants_overrides_sub: m("Remplacer la valeur par défaut du rôle pour cette personne", "Override the role default for this person"),
  col_permission: m("Autorisation", "Permission"),
  grant_default: m("Selon le rôle", "Role default"),
  grant_default_on: m("Selon le rôle (permis)", "Role default (allowed)"),
  grant_default_off: m("Selon le rôle (refusé)", "Role default (denied)"),
  grant_allow: m("Permettre", "Allow"),
  grant_deny: m("Refuser", "Deny"),
  grant_overridden: m("Exception", "Overridden"),

  // the fixed permission vocabulary (mirrors the store / CONTRACT §7)
  perm_void: m("Annuler une addition", "Void a check"),
  perm_refund: m("Rembourser", "Refund"),
  perm_discount_comp: m("Rabais / offert par la maison", "Discount / comp"),
  perm_cash_movement: m("Entrée / sortie de caisse", "Cash in / out"),
  perm_open_shift: m("Ouvrir un quart", "Open shift"),
  perm_close_shift: m("Fermer un quart", "Close shift"),
  perm_price_override: m("Modifier un prix / article libre", "Price override / open item"),
  perm_zone_open_close: m("Ouvrir / fermer une zone", "Open / close a zone"),
  perm_edit_menu: m("Modifier le menu (86)", "Edit menu (86)"),
  perm_manage_staff: m("Gérer le personnel", "Manage staff"),

  // ── scope: one store or all stores (every page, every export) ──────────
  scope_single: m("Établissement : {store}", "Store: {store}"),
  scope_all_n: m("Tous les établissements ({n})", "All stores ({n})"),
  scope_show_all: m("Afficher tous les établissements", "Show all stores"),
  export_csv: m("CSV", "CSV"),
  store_sales_title: m("Ventes par établissement", "Sales by store"),
  chart_per_store_gross: m("Ventes brutes, une courbe par établissement", "Gross, one line per store"),
  chart_per_store_stacked: m("Empilé par établissement", "Stacked by store"),
  dash_today_by_store: m("Aujourd’hui, par établissement", "Today, by store"),
  dash_by_store_range: m("{range}, par établissement", "{range}, by store"),
  dash_combined_today: m("Tous les établissements, aujourd’hui", "All stores, today"),
  dash_combined_range: m("Tous les établissements · {range}", "All stores · {range}"),
  dash_online_n: m("{n} sur {total} en ligne", "{n} of {total} online"),
  items_by_store: m("Articles par établissement", "Items by store"),
  payments_by_store: m("Paiements par établissement", "Payments by store"),
  refunds_by_store: m("Remboursements par établissement", "Refunds by store"),
  cash_by_store: m("Entrées et sorties de caisse par établissement", "Cash in / out by store"),
  cash_movements_n: m("Mouvements", "Movements"),
  exc_by_store: m("Annulations par établissement", "Voids by store"),
  shifts_by_store: m("Quarts par établissement", "Shifts by store"),
  shifts_n: m("Quarts", "Shifts"),
  journal_closed_n: m("Additions fermées", "Closed checks"),
  journal_by_store_sub: m(
    "Toutes les additions de la période et de la recherche, pas seulement cette page",
    "Every check in the range and search, not just this page"
  ),
  tax_of_store: m("Taxes · {store}", "Tax · {store}"),
  staff_count_n: m("{n} employés", "{n} staff"),

  // ── currencies: stores in more than one country ─────────────────────
  col_currency: m("Devise", "Currency"),
  fx_mixed_title: m("Plusieurs devises", "More than one currency"),
  fx_note: m(
    "Chaque établissement est affiché dans sa propre devise, au montant exact. Le total combiné est converti en {cur} au taux fixe {rates} : il est approximatif.",
    "Each store is shown in its own currency, exactly. The combined total is converted to {cur} at the fixed rate {rates}, so it is approximate."
  ),
  fx_no_rate: m(
    "Aucun taux de change n’est configuré : pas de total combiné, seulement les totaux exacts par devise.",
    "No exchange rate is configured: no combined total, only the exact totals per currency."
  ),
  fx_by_currency: m("Par devise (exact)", "By currency (exact)"),
  fx_converted_total: m("≈ Total converti en {cur}", "≈ Converted total in {cur}"),
  fx_converted_sub: m("taux fixe {rates}, approximatif", "fixed rate {rates}, approximate"),
  fx_chart_converted: m("≈ en {cur} au taux fixe {rates}", "≈ in {cur} at fixed rate {rates}"),
  fx_stores_in: m("{n} établissement(s)", "{n} store(s)"),
  // cash payments round to the nearest 5¢; only the cash handed over changes
  cash_rounding: m("Arrondi du comptant", "Cash rounding"),
  cash_rounding_note: m(
    "Les paiements comptant sont arrondis aux 5 ¢ près. Les ventes et les taxes restent exactes ; l’arrondi est compté à part, dans la devise de chaque établissement.",
    "Cash payments round to the nearest 5¢. Sales and tax stay exact; the rounding is counted separately, in each store’s own currency."
  ),
  retail_badge: m("Boutique", "Bottle shop"),
  tax_col_rate: m("{label} {rate} %", "{label} {rate}%"),
  tax_by_code: m("Par taxe", "By tax"),
  tax_col_tax: m("Taxe", "Tax"),
  // who the store pays each tax to (NC sales tax → NCDOR, Wake food tax → Wake County)
  tax_col_remit: m("Versée à", "Remit to"),
  // ── stock (retail stores) ───────────────────────────────────────────────
  nav_stock: m("Inventaire", "Stock"),
  stock_title: m("Inventaire", "Stock"),
  stock_sub: m(
    "En main = dernier décompte + reçu − vendu ± ajustements + retours, d’après les ventes et les décomptes synchronisés.",
    "On hand = last count + received − sold ± adjustments + returns, from the synced sales and counts."
  ),
  stock_kpi_products: m("Produits", "Products"),
  stock_kpi_on_hand: m("Unités en main", "Units on hand"),
  stock_kpi_low: m("Stock bas", "Low stock"),
  stock_search: m("Chercher un produit ou un code-barres", "Search a product or barcode"),
  stock_low_only: m("Stock bas seulement", "Low stock only"),
  stock_col_product: m("Produit", "Product"),
  stock_col_barcode: m("Code-barres", "Barcode"),
  stock_col_on_hand: m("En main", "On hand"),
  stock_col_reorder: m("Seuil", "Reorder at"),
  stock_col_received: m("Reçu", "Received"),
  stock_col_sold: m("Vendu", "Sold"),
  stock_col_adjusted: m("Ajusté", "Adjusted"),
  stock_col_low: m("Bas", "Low"),
  stock_low: m("Bas", "Low"),
  stock_yes: m("oui", "yes"),
  stock_receive: m("Recevoir", "Receive"),
  stock_adjust: m("Ajuster", "Adjust"),
  stock_set_reorder: m("Seuil de réapprovisionnement", "Reorder level"),
  stock_receive_title: m("Recevoir une livraison · {name}", "Receive a delivery · {name}"),
  stock_adjust_title: m("Ajuster le stock · {name}", "Adjust stock · {name}"),
  stock_reorder_title: m("Seuil de réapprovisionnement · {name}", "Reorder level · {name}"),
  stock_qty: m("Quantité", "Quantity"),
  stock_qty_adjust_hint: m(
    "Négatif pour retirer (casse, perte), positif pour ajouter (retour, recomptage).",
    "Negative to take off (breakage, shrink), positive to add (a return, a recount)."
  ),
  stock_note: m("Note (facultative)", "Note (optional)"),
  stock_reorder_hint: m(
    "Le produit est « bas » à ce niveau ou en dessous. Vide = aucun seuil.",
    "The product shows as low at or below this. Blank = no level."
  ),
  stock_save: m("Enregistrer", "Save"),
  stock_saved: m("Stock mis à jour", "Stock updated"),
  stock_pick_store: m(
    "Choisissez un établissement pour enregistrer une livraison ou un ajustement.",
    "Pick a store to record a delivery or an adjustment."
  ),
  stock_not_retail: m("Pas d’inventaire ici", "No stock here"),
  stock_not_retail_hint: m(
    "Les restaurants ne suivent pas l’inventaire ; seules les boutiques le font.",
    "Restaurants don't track stock; only retail stores do."
  ),
  stock_empty: m("Aucun produit", "No products"),
  stock_empty_hint: m(
    "Les produits de l’établissement apparaissent ici après sa première synchronisation.",
    "The store's products show here after its first sync."
  ),
  stock_history: m("Mouvements récents", "Recent movements"),
  stock_kind_RECEIVED: m("Livraison", "Delivery"),
  stock_kind_ADJUSTMENT: m("Ajustement", "Adjustment"),
  stock_kind_COUNT: m("Décompte", "Count"),
  stock_tab_on_hand: m("En main", "On hand"),
  stock_tab_reorder: m("À commander", "Reorder"),
  stock_tab_counts: m("Décomptes", "Counts"),
  stock_tab_deliveries: m("Livraisons", "Deliveries"),
  stock_col_returned: m("Retours", "Returned"),
  stock_col_counted: m("Compté", "Counted"),
  stock_counted_on: m(
    "{qty} compté(s) le {when} · chiffres depuis le décompte",
    "Counted {qty} on {when} · figures since the count"
  ),
  stock_from_store: m("établissement", "store"),
  stock_nav_low: m("{n} en stock bas", "{n} low on stock"),
  // reorder suggestions
  reorder_title: m("Suggestions de commande", "Reorder suggestions"),
  reorder_sub: m(
    "Ventes moyennes par jour × jours à couvrir (délai + réserve), moins l’en-main.",
    "Average daily sales × days to cover (lead time + buffer), less on hand."
  ),
  reorder_days: m("Ventes des derniers", "Sales over the last"),
  reorder_days_unit: m("jours", "days"),
  reorder_cover: m("Jours à couvrir", "Days to cover"),
  reorder_col_sold: m("Vendu ({n} j)", "Sold ({n} d)"),
  reorder_col_avg: m("Moy. par jour", "Avg / day"),
  reorder_col_target: m("Cible", "Target"),
  reorder_col_suggested: m("À commander", "Order"),
  reorder_kpi_products: m("Produits à commander", "Products to order"),
  reorder_kpi_units: m("Unités suggérées", "Units suggested"),
  reorder_only: m("À commander seulement", "Only products to order"),
  reorder_none: m("Rien à commander", "Nothing to order"),
  reorder_none_hint: m(
    "L’en-main couvre les ventes récentes pour la période choisie.",
    "On hand covers the recent sales for the days chosen."
  ),
  reorder_as_of: m("{days} jours de ventes · {cover} jours à couvrir", "{days} days of sales · {cover} days to cover"),
  // counts and deliveries from the store
  counts_title: m("Décomptes de l’établissement", "Counts from the store"),
  counts_sub: m(
    "Comptés sur place (appli d’inventaire ou comptoir). Un décompte fixe l’en-main au moment du comptage ; l’écart est calculé par rapport à la quantité attendue à ce moment-là.",
    "Counted in the store (Stock app or the counter). A count sets on hand as of when it was counted; the variance is against what was expected then."
  ),
  counts_empty: m("Aucun décompte pour l’instant", "No counts yet"),
  counts_empty_hint: m(
    "Les décomptes faits sur place apparaissent ici après la synchronisation.",
    "Counts done in the store show here after it syncs."
  ),
  counts_by: m("par {who}", "by {who}"),
  counts_approved: m("approuvé par {who}", "approved by {who}"),
  counts_products: m("{n} produits", "{n} products"),
  counts_variances: m("{n} écarts", "{n} variances"),
  counts_no_variance: m("aucun écart", "no variance"),
  counts_col_expected: m("Attendu", "Expected"),
  counts_col_variance: m("Écart", "Variance"),
  deliveries_title: m("Livraisons reçues", "Deliveries received at the store"),
  deliveries_empty: m("Aucune livraison pour l’instant", "No deliveries yet"),
  deliveries_empty_hint: m(
    "Les livraisons balayées sur place apparaissent ici après la synchronisation.",
    "Deliveries scanned in at the store show here after it syncs."
  ),
  deliveries_col_supplier: m("Fournisseur", "Supplier"),
  deliveries_col_reference: m("Référence", "Reference"),
  deliveries_col_by: m("Reçu par", "Received by"),
  deliveries_col_units: m("Unités", "Units"),
  deliveries_col_when: m("Reçu le", "Received"),
  stock_as_of: m("En date d’aujourd’hui", "As of now"),
  stock_negative_note: m(
    "L’établissement vend même sans stock : un chiffre négatif signifie qu’une livraison n’a pas été saisie.",
    "The store sells regardless of stock: a negative figure means a delivery wasn't recorded."
  ),
  // ── menu editing (two-way menu sync) ──
  menu_add_item: m("Ajouter un article", "Add item"),
  menu_categories: m("Catégories", "Categories"),
  menu_new_item: m("Nouvel article", "New item"),
  menu_edit_item: m("Modifier l’article", "Edit item"),
  menu_name_en: m("Nom (anglais)", "Name (English)"),
  menu_name_fr: m("Nom (français)", "Name (French)"),
  menu_name_in: m("Nom ({lang})", "Name ({lang})"),
  menu_desc_en: m("Description (anglais)", "Description (English)"),
  menu_desc_fr: m("Description (français)", "Description (French)"),
  menu_available: m("Disponible", "Available"),
  menu_available_hint: m("Désactivé = en rupture (86) : il disparaît du menu jusqu’à sa réactivation.", "Off = 86’d: it leaves the menu until you turn it back on."),
  menu_alcohol: m("Contient de l’alcool", "Contains alcohol"),
  menu_sizes: m("Formats et prix", "Sizes and prices"),
  menu_size_en: m("Format (anglais)", "Size (English)"),
  menu_size_fr: m("Format (français)", "Size (French)"),
  menu_size_price: m("Prix", "Price"),
  menu_add_size: m("Ajouter un format", "Add a size"),
  menu_remove_size: m("Retirer ce format", "Remove this size"),
  menu_default_size: m("Régulier", "Regular"),
  menu_delete_item: m("Supprimer l’article", "Delete item"),
  menu_delete_item_q: m("Supprimer {name} ?", "Delete {name}?"),
  menu_delete_item_body: m("Il quitte le menu. Les ventes passées le conservent, et les commandes ouvertes ne changent pas.", "It leaves the menu. Past sales keep it, and open orders don’t change."),
  menu_scope_all: m("S’applique à tous les établissements qui l’offrent.", "Applies to every store that carries it."),
  menu_scope_one: m("S’applique à {store} seulement.", "Applies to {store} only."),
  menu_saved: m("Enregistré — l’établissement le reçoit à sa prochaine synchronisation.", "Saved — the store gets it the next time it syncs."),
  menu_deleted: m("Supprimé du menu", "Deleted from the menu"),
  menu_err_name_required: m("Entrez un nom en anglais.", "Enter a name in English."),
  menu_err_category_required: m("Choisissez une catégorie.", "Pick a category."),
  menu_err_size_required: m("Un article a besoin d’au moins un format.", "An item needs at least one size."),
  menu_err_size_label_required: m("Chaque format a besoin d’un nom.", "Every size needs a name."),
  menu_err_price_invalid: m("Entrez un prix comme 12.50 (0 ou plus).", "Enter a price like 12.50 (0 or more)."),
  menu_skip_store_not_upgraded: m("{store} : l’application doit d’abord être mise à jour", "{store}: its app needs an update first"),
  menu_skip_not_found: m("{store} : n’est pas à son menu", "{store}: not on its menu"),
  menu_skip_category_not_found: m("{store} : n’a pas cette catégorie", "{store}: doesn’t have that category"),
  menu_skip_last_variant: m("{store} : un article a besoin d’au moins un format", "{store}: an item needs at least one size"),
  menu_skip_category_not_empty: m("{store} : la catégorie contient encore des articles", "{store}: the category still has items"),
  menu_skip_other: m("{store} : non modifié ({reason})", "{store}: not changed ({reason})"),
  menu_not_everywhere: m("Enregistré, sauf : {list}", "Saved, except: {list}"),
  menu_pending: m("{store} : {n} modification(s) en attente que l’établissement soit en ligne", "{store}: {n} change(s) waiting for the store to come online"),
  menu_apply_failed: m("{store} : {n} modification(s) du menu pas encore appliquée(s) par l’établissement (nouvel essai à chaque synchronisation)", "{store}: {n} menu change(s) the store could not apply yet (it retries every sync)"),
  menu_store_outdated: m("{store} : l’application de cet établissement doit être mise à jour avant de recevoir des modifications du menu d’ici", "{store}: this store’s app needs an update before it can take menu changes from here"),
  menu_cat_title: m("Catégories du menu", "Menu categories"),
  menu_cat_add: m("Ajouter une catégorie", "Add category"),
  menu_cat_new: m("Nouvelle catégorie", "New category"),
  menu_cat_up: m("Monter", "Move up"),
  menu_cat_down: m("Descendre", "Move down"),
  menu_cat_rename: m("Renommer", "Rename"),
  menu_cat_delete_q: m("Supprimer la catégorie {name} ?", "Delete the {name} category?"),
  menu_cat_delete_body: m("Seule une catégorie vide peut être supprimée.", "Only an empty category can be deleted."),
  menu_cat_not_empty: m("Cette catégorie contient encore des articles : déplacez-les ou supprimez-les d’abord.", "This category still has items: move or delete them first."),
  menu_cat_saved: m("Catégories enregistrées", "Categories saved"),
  menu_lang_es: m("espagnol", "Spanish"),
  menu_lang_de: m("allemand", "German"),
  menu_lang_af: m("afrikaans", "Afrikaans"),
  menu_lang_fr: m("français", "French"),
  menu_lang_en: m("anglais", "English"),

  // ── menu: the AI assistant ("Ask AI", /v1/menu-ai) ───────────────────
  ai_ask: m("Demander à l’IA", "Ask AI"),
  ai_banner: m("Modifiez le menu en écrivant ou en parlant. Vous vérifiez chaque changement avant qu’il soit enregistré.", "Change the menu by typing or speaking. You check every change before it’s saved."),
  ai_not_setup: m("L’assistant IA n’est pas configuré sur ce portail.", "The AI assistant isn’t set up on this portal."),
  ai_title: m("Assistant menu IA", "AI menu assistant"),
  ai_pick_store: m("Choisissez l’établissement dont le menu change :", "Pick the store whose menu to change:"),
  ai_placeholder: m("Par exemple : ajoute une salade César à 14 $ dans Entrées", "For example: add a Caesar salad for $14 under Starters"),
  ai_example_1: m("Monte toutes les bières de 50 ¢", "Raise every beer by 50¢"),
  ai_example_2: m("Retire les ailes du menu pour aujourd’hui (86)", "86 the wings for today"),
  ai_example_3: m("Traduis les desserts en espagnol", "Translate the desserts into Spanish"),
  ai_send: m("Envoyer", "Send"),
  ai_mic: m("Parler", "Speak"),
  ai_mic_stop: m("Arrêter", "Stop"),
  ai_recording: m("J’écoute… encore {s} s", "Listening… {s} s left"),
  ai_mic_denied: m("Le navigateur n’a pas permis le micro. Autorisez-le dans les réglages du site, ou écrivez plutôt.", "The browser didn’t allow the microphone. Allow it in the site settings, or type instead."),
  ai_mic_unsupported: m("Ce navigateur ne peut pas enregistrer ici. Écrivez plutôt.", "This browser can’t record here. Type instead."),
  ai_thinking: m("L’IA prépare les changements…", "The AI is preparing the changes…"),
  ai_heard: m("Entendu : « {text} »", "Heard: “{text}”"),
  ai_changes: m("Changements proposés", "Proposed changes"),
  ai_review_hint: m("Rien n’est enregistré avant que vous appliquiez.", "Nothing is saved until you apply."),
  ai_apply: m("Appliquer ({n})", "Apply selected ({n})"),
  ai_discard: m("Annuler", "Discard"),
  ai_kind_add_item: m("Nouvel article", "New item"),
  ai_kind_add_category: m("Nouvelle catégorie", "New category"),
  ai_kind_update_item: m("Modification", "Change"),
  ai_kind_remove_item: m("Retrait", "Remove"),
  ai_kind_rename_category: m("Catégorie renommée", "Rename category"),
  ai_kind_reorder_categories: m("Nouvel ordre", "New order"),
  ai_kind_set_name: m("Traduction", "Translation"),
  ai_field_order: m("Ordre des catégories", "Category order"),
  ai_on: m("En vente", "On sale"),
  ai_off: m("Retiré (86)", "Off (86)"),
  ai_with_category: m("avec sa nouvelle catégorie", "with its new category"),
  ai_skipped: m("{n} suggestion(s) écartée(s) : pas sûres ou absentes de ce menu", "{n} suggestion(s) left out: not safe, or not on this menu"),
  ai_bulk_title: m("Appliquer {n} changement(s) ?", "Apply {n} change(s)?"),
  ai_bulk_many: m("Cela fait beaucoup de changements d’un coup.", "That’s a lot of changes at once."),
  ai_bulk_removals: m("Des articles seront retirés du menu.", "Some items will be removed from the menu."),
  ai_bulk_prices: m("Certains prix changent de moitié ou plus.", "Some prices change by half or more."),
  ai_bulk_undo_hint: m("Vous pourrez tout annuler juste après.", "You can undo it right after."),
  ai_bulk_confirm: m("Oui, appliquer", "Yes, apply"),
  ai_applied: m("{n} changement(s) appliqué(s). L’établissement les reçoit à sa prochaine synchronisation.", "{n} change(s) applied. The store gets them the next time it syncs."),
  ai_undo: m("Annuler ces changements", "Undo"),
  ai_undone: m("Annulé : le menu est revenu comme avant.", "Undone: the menu is back as it was."),
  ai_undo_partial: m("Annulé, sauf {n} changement(s) modifié(s) depuis.", "Undone, except {n} change(s) edited since."),
  ai_again: m("Demander autre chose", "Ask something else"),
  ai_err_too_many: m("Trop de demandes à l’IA. Réessayez dans quelques minutes.", "Too many AI requests. Try again in a few minutes."),
  ai_err_daily: m("La limite quotidienne de l’assistant IA est atteinte pour cet établissement.", "The AI assistant’s daily limit is reached for this store."),
  ai_err_busy: m("L’IA n’a pas répondu. Réessayez dans un instant.", "The AI didn’t answer. Try again in a moment."),
  ai_err_expired: m("Cette proposition a expiré. Redemandez.", "That suggestion expired. Ask again."),
  ai_err_audio: m("L’enregistrement n’a pas fonctionné. Réessayez ou écrivez plutôt.", "The recording didn’t work. Try again, or type instead."),
  ai_example_photo: m("Génère une photo du thé glacé", "Generate a picture for the iced tea"),
  ai_photo_title: m("Photo IA", "AI photo"),
  ai_photo_generate: m("Générer une photo", "Generate photo"),
  ai_photo_enhance: m("Améliorer la photo", "Enhance photo"),
  ai_photo_kind_generate: m("Nouvelle photo", "New photo"),
  ai_photo_kind_enhance: m("Photo améliorée", "Enhanced photo"),
  ai_photo_waiting: m("En attente…", "Waiting…"),
  ai_photo_making: m("Création de l’image… jusqu’à une minute.", "Making the picture… up to a minute."),
  ai_photo_progress: m("Image {i} sur {n}…", "Picture {i} of {n}…"),
  ai_photo_accept: m("Utiliser cette photo", "Use this photo"),
  ai_photo_retry: m("Réessayer", "Try again"),
  ai_photo_discard: m("Écarter", "Discard"),
  ai_photo_accepted: m("Photo enregistrée. L’établissement la reçoit à sa prochaine synchronisation.", "Photo saved. The store gets it the next time it syncs."),
  ai_photo_undo: m("Annuler", "Undo"),
  ai_photo_undone: m("Annulé : la photo d’avant est revenue.", "Undone: the previous photo is back."),
  ai_photo_discarded: m("Écartée.", "Discarded."),
  ai_photo_replaces: m("Remplace la photo actuelle.", "Replaces the current photo."),
  ai_photo_bulk_title: m("Créer {n} photos ?", "Make {n} photos?"),
  ai_photo_bulk_body: m("Une à la fois, environ 10 à 30 secondes chacune. Vous vérifiez chaque image avant qu’elle soit utilisée.", "One at a time, about 10 to 30 seconds each. You check every picture before it’s used."),
  ai_photo_bulk_go: m("Créer {n} photos", "Make {n} photos"),
  ai_photo_accept_all: m("Tout utiliser ({n})", "Use all ({n})"),
  ai_photo_section: m("Photos à créer", "Photos to make"),
  ai_photo_hint: m("Images IA dans le style maison : sans personnes, logos ni texte.", "AI pictures in the house style: no people, logos or text."),
  ai_photo_pick_store: m("Choisissez un établissement pour créer une photo IA.", "Pick one store to make an AI photo."),
  ai_photo_alt: m("Image IA : {name}", "AI picture: {name}"),
  ai_photo_off: m("Les photos IA ne sont pas configurées sur ce portail.", "AI photos aren’t set up on this portal."),
  ai_photo_err_refused: m("Le service d’images a refusé celle-ci. Essayez une autre description.", "The image service wouldn’t make this one. Try a different description."),
  ai_photo_err_name: m("Le nom de cet article ne peut pas servir pour une photo IA.", "This item’s name can’t be used for an AI photo."),
  ai_photo_err_daily: m("La limite quotidienne de photos IA est atteinte pour cet établissement.", "Today’s AI photo limit is reached for this store."),
  ai_photo_err_changed: m("La photo a été changée depuis : rien n’a été annulé.", "The photo was changed since, so nothing was undone."),
  ai_photo_err_expired: m("Cette image a expiré. Créez-en une nouvelle.", "That picture expired. Make a new one."),
  ai_photo_err_none: m("Cet article n’a pas encore de photo à améliorer.", "This item has no photo to enhance yet."),

  // ── exports: the manager's data downloads (/exports) ─────────────────
  nav_exports: m("Exportations", "Exports"),
  exports_title: m("Exporter vos données", "Export your data"),
  exports_sub: m("Téléchargez vos données en tableur, pour la période et l’établissement choisis.", "Download your data as spreadsheets, for the dates and store you pick."),
  exports_scope_note: m("Chaque fichier couvre {scope}. Les montants sont des nombres simples avec une colonne de devise ; les heures sont celles de l’établissement, avec son fuseau horaire.", "Each file covers {scope}. Money is a plain number with a currency column; times are the store’s local time, with its time zone."),
  exports_current_state: m("Tel qu’il est aujourd’hui (la période ne s’applique pas).", "As it is now (the dates don’t apply)."),
  exports_ds_sales: m("Ventes", "Sales"),
  exports_ds_sales_desc: m("Une ligne par addition : totaux, chaque taxe et à qui elle est versée, paiements, rabais, annulations.", "One row per check: totals, each tax and who it’s paid to, payments, discounts, voids."),
  exports_ds_lines: m("Lignes de vente", "Sale lines"),
  exports_ds_lines_desc: m("Chaque article vendu : article, format, quantité, prix unitaire, total de la ligne.", "Every item sold: item, variant, quantity, unit price, line total."),
  exports_ds_refunds: m("Remboursements", "Refunds"),
  exports_ds_refunds_desc: m("Chaque remboursement, avec la taxe remboursée.", "Every refund, with the tax it reversed."),
  exports_ds_tenders: m("Paiements", "Payments"),
  exports_ds_tenders_desc: m("Chaque paiement reçu, par type.", "Every payment taken, by type."),
  exports_ds_shifts: m("Quarts de caisse", "Shifts"),
  exports_ds_shifts_desc: m("Fond de caisse, ventes, argent compté, écart.", "Till float, sales, counted cash, over / short."),
  exports_ds_cash: m("Entrées et sorties de caisse", "Cash in / out"),
  exports_ds_cash_desc: m("L’argent ajouté à la caisse ou retiré.", "Cash paid into or out of the till."),
  exports_ds_menu: m("Menu", "Menu"),
  exports_ds_menu_desc: m("Articles avec catégorie, formats et prix.", "Items with their category, variants and prices."),
  exports_ds_staff: m("Personnel", "Staff"),
  exports_ds_staff_desc: m("Noms et rôles seulement, jamais de NIP.", "Names and roles only, never PINs."),
  exports_ds_tax: m("Sommaire des taxes", "Tax summary"),
  exports_ds_tax_desc: m("Taxes par autorité, par jour et au total : les chiffres du rapport de taxes.", "Tax by authority, per day and in total: the tax report’s figures."),
  exports_all_title: m("Toutes mes données", "All my data"),
  exports_all_desc: m("Tous les fichiers ci-dessus en CSV, toutes les dates et tous les établissements, avec un fichier README qui décrit chaque colonne.", "Every file above as CSV, every date and every store, with a README describing each column."),
  exports_all_limit: m("Réservé au propriétaire · jusqu’à {n} fois par heure", "Owner only · up to {n} times an hour"),
  exports_all_button: m("Télécharger toutes mes données (.zip)", "Download all my data (.zip)"),
  exports_preparing: m("Préparation…", "Preparing…"),
  exports_done: m("Téléchargé : {file}", "Downloaded {file}"),
  exports_forbidden: m("Seuls les propriétaires et les gérants peuvent exporter des données.", "Only owners and managers can export data."),
  exports_too_large: m("Trop de données pour un seul fichier : choisissez moins de jours ou un seul établissement.", "Too much data for one file: pick fewer days or one store."),
  exports_rate_limited: m("Limite atteinte pour l’instant. Réessayez dans {minutes} min.", "That’s the limit for now. Try again in {minutes} min."),
  exports_failed: m("Le téléchargement n’a pas fonctionné. Réessayez.", "The download didn’t work. Try again."),
} as const;

export type MsgKey = keyof typeof messages;
