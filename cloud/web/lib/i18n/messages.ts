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
// compile error.
export type Locale = "fr" | "en" | "es";

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

  // ── menu (read-only: each store's tablet owns its menu) ───────────────
  menu_title: m("Menu", "Menu"),
  menu_sub: m(
    "Le menu se modifie sur la tablette de chaque établissement ; il apparaît ici après la synchronisation.",
    "Menus are edited on each store’s tablet and appear here once it syncs."
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
} as const;

export type MsgKey = keyof typeof messages;
