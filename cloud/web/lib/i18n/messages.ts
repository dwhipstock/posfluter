// Portal UI strings, both locales inline — one object, compile-checked keys.
// French is first-class: it is the default and the source of truth for shared
// domain terms, which mirror the POS client (client/lib/i18n.dart) so the
// portal and the till say the same words for the same things.
//
// Interpolation: values may contain {placeholders}; pass vars to t().
// Money ($, comma grouping, cents) is script-neutral and lives in lib/format.ts.

export type Locale = "fr" | "en";

type Msg = { fr: string; en: string };

const m = (fr: string, en: string): Msg => ({ fr, en });

export const messages = {
  // ── common ────────────────────────────────────────────────────────────
  brand_tagline: m("Portail des propriétaires de boutique", "Owner portal"),
  save: m("enregistrer", "Save"),
  cancel: m("Annuler", "Cancel"),
  delete: m("supprimer", "Delete"),
  keep_it: m("garde-le", "Keep it"),
  add: m("augmenter", "Add"),
  retry: m("Essayer à nouveau", "Retry"),
  to: m("à", "to"),
  prev: m("précédent", "Prev"),
  next: m("Suivant", "Next"),
  couldnt_load: m("Échec du chargement", "Couldn’t load this"),
  something_wrong: m("Quelque chose s'est mal passé", "Something went wrong"),

  // ── export (PDF / Excel, every report) ────────────────────────────────
  export: m("exporter", "Export"),
  export_pdf: m("PDF", "PDF"),
  export_excel: m("Excel (.xlsx)", "Excel (.xlsx)"),
  export_kpi_sheet: m("résumer", "Summary"),
  export_generated: m("Rapport émis lorsque {when}", "Generated {when}"),
  // report-specific labels only the exports need
  svc_charge: m("Frais de service", "Service charge"),
  summary_by_day: m("Ventes quotidiennes", "Daily sales"),
  tables_by_table: m("selon le tableau", "By table"),
  col_shift: m("Changement", "Shift"),
  col_status: m("statut", "Status"),

  // ── nav ───────────────────────────────────────────────────────────────
  // ── store picker (every page: all stores combined, or one store) ─────
  store_all: m("Tous les magasins", "All stores"),
  store_label: m("Magasin", "Store"),
  col_store: m("Magasin", "Store"),
  store_breakdown_title: m("Par magasin", "By store"),
  store_breakdown_sub: m(
    "Chaque magasin selon ses propres journées d’affaires",
    "Each store over its own business days"
  ),
  store_pick_hint: m(
    "Choisissez un magasin dans l’en-tête pour jumeler un terminal.",
    "Pick a store in the header to pair a terminal."
  ),

  nav_dashboard: m("Tableau de bord", "Dashboard"),
  nav_reports: m("rapport", "Reports"),
  nav_menu: m("menu", "Menu"),
  nav_account: m("compte", "Account"),

  // ── login ─────────────────────────────────────────────────────────────
  login_email: m("E-mail", "Email"),
  login_password: m("mot de passe", "Password"),
  login_signin: m("Se connecter", "Sign in"),
  login_2fa_title: m("Le code de vérification 2 sol", "Two-factor code"),
  login_2fa_body: m(
    "Entrez le code 6 Une fois que l'application a vérifié votre identité",
    "Enter the 6-digit code from your authenticator app."
  ),
  login_setup_title: m("Confirmer les paramètres 2 sol", "Set up two-factor"),
  login_setup_body: m(
    "Scannez aussi Google Authenticator (ou une application TOTP n'importe lequel) et entrez le code 6 chiffres pour confirmer",
    "Scan with Google Authenticator (or any TOTP app), then enter the 6-digit code to confirm."
  ),
  login_back: m("Revenez à la page de connexion.", "Back to sign in"),
  login_copy_secret: m("Copiez le code secret", "Copy secret"),
  login_copy_failed: m(
    "Je ne peux pas copier — Appuyez et maintenez sur le code pour le copier à la place.",
    "Couldn’t copy — long-press the code instead"
  ),

  // ── two-factor: backup codes + recovery ───────────────────────────────
  login_use_backup: m("Vous avez perdu votre téléphone ? Utiliser un code de secours", "Lost your phone? Use a backup code"),
  login_use_authenticator: m("Utilisez plutôt une application d’authentification.", "Use your authenticator instead"),
  login_backup_title: m("Code de sauvegarde", "Backup code"),
  login_backup_body: m(
    "Entrez l'un des codes de sauvegarde que vous avez enregistrés lors de la configuration. — Chaque ensemble ne peut être utilisé qu'une seule fois.",
    "Enter one of the backup codes you saved during setup. Each works once."
  ),
  login_verify: m("confirmer", "Verify"),
  login_backup_save_title: m("Enregistrez votre code de sauvegarde.", "Save your backup codes"),
  login_backup_save_body: m(
    "Si votre téléphone est perdu ou effacé Utilisez un code pour vous connecter. Conservez-le dans un endroit sûr.",
    "If you lose or wipe your phone, use one of these to sign in. Keep them somewhere safe."
  ),
  login_backup_warn: m("Ces codes ne seront plus affichés.", "These codes won’t be shown again."),
  login_backup_copy: m("Copier tout", "Copy all"),
  login_backup_copied: m("Copié", "Copied"),
  login_backup_download: m("télécharger", "Download"),
  login_backup_continue: m("Je l'ai sauvegardé, continuez.", "I’ve saved them — continue"),

  // ── auth error copy (mapped from the server's error `code`) ────────────
  err_bad_credentials: m("Email ou mot de passe incorrect", "Wrong email or password"),
  err_bad_totp: m("Code invalide. Essayer à nouveau.", "That code isn’t right. Try again."),
  err_bad_pending_token: m(
    "Le temps de connexion a expiré. S'il vous plaît, recommencez.",
    "Your sign-in timed out. Please start again."
  ),
  err_rate_limited: m(
    "J'ai essayé trop de fois. Attendez un moment et réessayez.",
    "Too many attempts. Wait a moment and try again."
  ),

  // ── date range ────────────────────────────────────────────────────────
  preset_today: m("aujourd'hui", "Today"),
  preset_yesterday: m("hier", "Yesterday"),
  preset_7d: m("7 jour", "7 days"),
  preset_30d: m("30 jour", "30 days"),
  preset_month: m("Ce mois-ci", "This month"),
  preset_custom: m("personnalisé", "Custom"),
  // header captions (a touch more descriptive than the chips)
  range_today: m("aujourd'hui", "Today"),
  range_yesterday: m("hier", "Yesterday"),
  range_7d: m("7 Dernière date", "Last 7 days"),
  range_30d: m("30 Dernière date", "Last 30 days"),
  range_month: m("Ce mois-ci", "This month"),

  // ── tenders (shared with POS) ─────────────────────────────────────────
  tender_CASH: m("espèces", "Cash"),
  tender_CARD: m("Carte", "Card"),
  tender_BANK_TRANSFER: m("Virement bancaire", "Bank transfer"),
  tender_STRIPE: m("Carte (Stripe)", "Card (Stripe)"),

  // ── dashboard ─────────────────────────────────────────────────────────
  dash_title: m("Tableau de bord", "Dashboard"),
  kpi_gross: m("Ventes totales", "Gross"),
  kpi_net: m("Montant net (hors taxes)", "Net (before tax)"),
  kpi_tax: m("Taxe de vente", "Sales tax"),
  kpi_checks: m("Nombre de factures", "Checks"),
  kpi_avg_check: m("Moyenne par facture", "Avg check"),
  dash_daily_trend: m("Tendances quotidiennes", "Daily trend"),
  dash_payment_mix: m("Proportion de paiement", "Payment mix"),
  dash_sales_by_hour: m("Ventes horaires", "Sales by hour"),
  dash_top_items: m("Menu le plus vendu", "Top items"),
  dash_all_items: m("Voir tout", "All items"),
  series_gross: m("Ventes totales", "Gross"),
  series_net: m("filet", "Net"),
  empty_no_sales_range: m("Il n'y a pas de vente pendant cette période.", "No sales in this range yet"),
  empty_no_payments: m("Pas encore de paiement", "No payments yet"),
  empty_no_payments_hint: m("Les factures clôturées seront affichées ici.", "Settled checks will show up here."),
  empty_quiet: m("Toujours calme", "Quiet so far"),
  empty_quiet_hint: m("Les ventes horaires seront affichées à la clôture de la facture.", "Hourly sales will appear as checks close."),
  empty_nothing_sold: m("Pas encore à vendre", "Nothing sold yet"),
  empty_nothing_sold_hint: m("Les plats de menu les plus vendus sont classés ici.", "Top sellers will rank here."),

  // ── reports index ─────────────────────────────────────────────────────
  reports_title: m("rapport", "Reports"),
  reports_sub: m("Sélectionnez le rapport — Toutes les éditions utilisent la période du même jour.", "Pick a report — every one shares the same date range."),
  report_tax_title: m("Taxe de vente", "Sales tax"),
  report_tax_desc: m("Sommaire quotidien des taxes enregistrées", "Daily breakdown of recorded sales tax"),
  report_payments_title: m("Paiement", "Payments"),
  report_payments_desc: m("Proportion d'espèces, card et virements", "Cash, Card and bank transfer mix"),
  report_items_title: m("menu", "Items"),
  report_items_desc: m("Que puis-je vendre ? Selon quantité et ventes", "What sells, by quantity and revenue"),
  report_tables_title: m("Tableaux et zones", "Tables & zones"),
  report_tables_desc: m("D’où proviennent les ventes ?", "Where the revenue happens"),
  report_hourly_title: m("horaire", "Hourly"),
  report_hourly_desc: m("Ventes par heure", "Sales by hour of day"),
  report_exceptions_title: m("Articles spéciaux", "Exceptions"),
  report_exceptions_desc: m("Facture d'annulation et frais de bouchon", "Voids and corkage"),
  report_shifts_title: m("Changement", "Shifts"),
  report_shifts_desc: m("Z-Report Avec de l'argent manquant/excédentaire", "Z-reports with cash over/short"),
  report_journal_title: m("Enregistrez la facture", "Journal"),
  report_journal_desc: m("Chaque facture peut être recherchée.", "Every check, searchable"),
  report_refunds_title: m("remboursement", "Refunds"),
  report_refunds_desc: m("Montants remboursés par motif, hors taxes", "Refund amounts by reason, net of tax"),
  report_cash_title: m("Entrée/sortie d'argent", "Cash movements"),
  report_cash_desc: m("Encaissements/retraits hors vente avec raisons", "Non-sale cash in/out, with reasons"),

  // ── tax report ────────────────────────────────────────────────────────
  tax_title: m("Rapport des taxes", "Sales tax report"),
  tax_included_badge: m("Taxes incluses {rate} %", "Sales tax {rate}% included"),
  tax_note: m(
    "Taxes extraites des prix TTC au moment de la vente — net = brut − taxes",
    "Decomposed from tax-inclusive prices at sale time — net = gross − sales tax."
  ),
  col_date: m("date", "Date"),
  col_gross: m("Total", "Gross"),
  col_net: m("filet", "Net"),
  col_tax: m("Taxe", "Tax"),
  col_checks: m("facture", "Checks"),
  col_total: m("Total", "Total"),
  tax_empty: m("Il n'y a pas de vente pendant cette période.", "No sales in this range"),
  tax_empty_hint: m("Les dates de vente sont affichées sous forme de lignes ici.", "Days with sales get a row here."),

  // ── payments report ───────────────────────────────────────────────────
  payments_title: m("Paiement", "Payments"),
  col_tender: m("canal", "Tender"),
  col_payments: m("Nombre de fois", "Payments"),
  col_amount: m("Somme", "Amount"),
  col_share: m("proportion", "Share"),
  col_total_short: m("ensemble", "Total"),
  payments_empty: m("Il n'y a aucun paiement pendant cette période.", "No payments in this range"),

  // ── items report ──────────────────────────────────────────────────────
  items_title: m("menu", "Items"),
  items_tab_revenue: m("Ventes", "Revenue"),
  items_tab_qty: m("quantité", "Quantity"),
  cat_all: m("tous", "All"),
  col_item: m("menu", "Item"),
  col_category: m("Catégorie", "Category"),
  col_qty: m("quantité", "Qty"),
  col_revenue: m("Ventes", "Revenue"),
  items_empty: m("Il n'y a pas de vente pendant cette période.", "Nothing sold in this range"),
  items_empty_hint: m("Un menu s'affichera lors de la fermeture de la facture.", "Items appear once checks close."),

  // ── hourly report ─────────────────────────────────────────────────────
  hourly_title: m("horaire", "Hourly"),
  hourly_chart: m("Ventes horaires", "Sales by hour"),
  col_hour: m("heure", "Hour"),
  hourly_empty: m("Il n'y a pas de vente pendant cette période.", "No sales in this range"),

  // ── tables report ─────────────────────────────────────────────────────
  tables_title: m("Tableaux et zones", "Tables & zones"),
  tables_by_zone: m("Ventes par zone", "Revenue by zone"),
  col_table: m("tableau", "Table"),
  col_zone: m("zone", "Zone"),
  tables_zone_empty: m("Il n’y a aucune information de zone pour le moment.", "No zone data in this range"),
  tables_table_empty: m("Il n’y a aucune information sur la table pour le moment.", "No table data in this range"),
  tables_table_empty_hint: m("Les effets clôturés seront comptés sur le tableau des effets.", "Closed checks land on their table."),

  // ── exceptions report ─────────────────────────────────────────────────
  exceptions_title: m("Articles spéciaux", "Exceptions"),
  exc_voids: m("facture annulée", "Voids"),
  exc_void_amount: m("Montant annulé", "Void amount"),
  exc_corkage: m("Frais de bouchon de bouteille", "Corkage"),
  col_check: m("facture", "Check"),
  col_voided: m("Annuler quand", "Voided"),
  col_reason: m("raison", "Reason"),
  col_by: m("par", "By"),
  exc_empty: m("Il n’y a aucune facture annulée pendant cette période.", "No voids in this range"),
  exc_empty_hint: m("changement propre — Pas d'annulation de facture", "Clean shifts — nothing was voided."),

  // ── Refunds report ────────────────────────────────────────────────────
  refunds_title: m("Rapport de remise", "Refunds"),
  refunds_note: m(
    "Les remboursements sont déduits des ventes et des taxes dans le sommaire et le rapport des taxes.",
    "Refunds are netted out of sales and tax in the summary and tax reports."
  ),
  ref_count: m("Nombre de fois", "Refunds"),
  ref_amount: m("Remboursement total", "Total refunded"),
  ref_tax: m("Taxes remboursées", "Tax reversed"),
  refunds_by_reason: m("selon la raison", "By reason"),
  refunds_by_tender: m("selon canal", "By tender"),
  refunds_list: m("Article remboursé", "Refunds"),
  col_time: m("temps", "Time"),
  refunds_empty: m("Il n'y a aucun remboursement pendant cette période.", "No refunds in this range"),
  refunds_empty_hint: m("Aucune facture n'a été remboursée.", "No bills were refunded."),

  // ── Cash movements report ─────────────────────────────────────────────
  cash_title: m("Déclarer les entrées/sorties d'argent", "Cash movements"),
  cash_note: m(
    "Entrées/sorties d'argent hors vente, telles que l'ajout de monnaie, les décaissements, les paiements en espèces aux fournisseurs.",
    "Non-sale cash in/out — float top-ups, pay-outs, suppliers paid in cash."
  ),
  cash_paid_in: m("Argent entrant", "Paid in"),
  cash_paid_out: m("Argent sorti", "Paid out"),
  cash_net: m("filet", "Net"),
  col_direction: m("direction", "Direction"),
  cash_in_label: m("entrer", "In"),
  cash_out_label: m("partir", "Out"),
  cash_empty: m("Il n’y a aucune transaction d’argent entrant/sortant pendant cette période.", "No cash movements in this range"),
  cash_empty_hint: m("Aucun argent ajouté ou retiré.", "No cash was paid in or out."),

  // ── shifts report ─────────────────────────────────────────────────────
  shifts_title: m("Changement", "Shifts"),
  shift_n: m("Changement #{id}", "Shift #{id}"),
  badge_live: m("frais", "LIVE"),
  badge_closed: m("Fermé", "CLOSED"),
  shift_now: m("maintenant", "now"),
  shift_revenue: m("Ventes", "Revenue"),
  shift_checks: m("facture", "Checks"),
  shift_avg: m("moyenne", "Avg"),
  shift_over_short: m("manque/excès", "Over/short"),
  shifts_empty: m("Il n'y a pas de changement pendant cette période.", "No shifts in this range"),
  shifts_empty_hint: m("Z-Report Il indiquera quand l'équipe est fermée.", "Z-reports appear when shifts close."),
  shift_opened: m("Poste ouvert", "Opened"),
  shift_closed: m("Fermer l'équipe", "Closed"),
  shift_still_open: m("toujours ouvert", "Still open"),
  shift_avg_check: m("Moyenne par facture", "Avg check"),
  shift_corkage: m("Frais de bouchon de bouteille", "Corkage"),
  shift_tenders: m("Canaux de paiement", "Tenders"),
  shift_no_tenders: m("Aucun paiement n’est enregistré.", "No tenders recorded."),
  shift_cash_drawer: m("tiroir-caisse", "Cash drawer"),
  shift_opening_float: m("Argent initial", "Opening float"),
  shift_expected_cash: m("L'argent liquide que vous devriez avoir", "Expected cash"),
  shift_counted: m("Peut vraiment compter", "Counted"),

  // ── journal report ────────────────────────────────────────────────────
  journal_title: m("Enregistrez la facture", "Journal"),
  journal_search: m("Recherchez le numéro de facture, le tableau ou le montant en CAD.", "Search check #, table, or exact CAD amount"),
  col_closed: m("fermé quand", "Closed"),
  badge_void: m("Annuler", "VOID"),
  journal_open_item: m("Articles spéciaux", "Open item"),
  journal_tax_included: m("Taxes incluses :", "Tax included:"),
  journal_pagination: m("{from}–{to} depuis {total}", "{from}–{to} of {total}"),
  journal_empty_search: m("Aucune facture trouvée correspondant à votre recherche.", "No checks match your search"),
  journal_empty_search_hint: m(
    "Essayez de saisir le numéro exact de la facture, le nom de la table ou le montant en CADs.",
    "Try a check number, table label, or exact CAD amount."
  ),
  journal_empty: m("Il n'y a aucune facture pendant cette période.", "No checks in this range"),

  // ── account ───────────────────────────────────────────────────────────
  account_title: m("compte", "Account"),
  account_signed_in: m("Déjà connecté", "Signed in"),
  account_name: m("nom", "Name"),
  account_email: m("E-mail", "Email"),
  account_venue: m("magasin", "Venue"),
  account_not_signed_in: m("Pas encore connecté", "Not signed in."),
  account_sign_out: m("Se déconnecter", "Sign out"),
  account_footer: m("Portail cloud CopperLantern · v{version}", "CopperLantern cloud portal · v{version}"),
  account_display: m("afficher", "Display"),
  account_language: m("langue", "Language"),
  account_devices: m("équipement", "Devices"),
  account_devices_hint: m("Associer et gérer des appareils POS", "Pair and manage POS terminals"),

  // ── devices + pairing ─────────────────────────────────────────────────
  nav_devices: m("équipement", "Devices"),
  devices_title: m("équipement", "Devices"),
  devices_sub: m("Associer l'appareil POS avec les magasins et gérer les appareils connectés", "Pair POS terminals and manage connected devices"),
  devices_venue: m("magasin", "Venue"),
  devices_no_venues: m("Ce compte n'a pas encore de boutique.", "No venues on this account yet"),
  devices_pair_title: m("Associer un nouvel appareil", "Pair a terminal"),
  devices_pair_sub: m(
    "Générer le code d'appairage Puis remplir la machine POS à l'intérieur 15 minute",
    "Generate a code, then enter it on the POS terminal within 15 minutes."
  ),
  devices_label: m("Nom de l'appareil (facultatif)", "Label (optional)"),
  devices_label_ph: m("comme une tablette devant un bar", "e.g. Bar tablet"),
  devices_pair_button: m("Générer le code d'appariement", "Generate pairing code"),
  devices_pair_again: m("Créer un nouveau code", "Generate a new code"),
  devices_code_hint: m(
    "Entrez ce code sur la machine. POS — Le code s'affiche une seule fois et ne peut être utilisé qu'une seule fois.",
    "Enter this code on the POS terminal — it’s shown once and works once."
  ),
  devices_code_expires: m("Peut être réutilisé {time} minute", "Code expires in {time}"),
  devices_code_expired: m("Le code a expiré. — Cliquez pour créer un nouveau code maintenant.", "Code expired — generate a new one."),
  devices_list_title: m("Appareils couplés", "Paired devices"),
  devices_none: m("Il n'y a pas encore d'appareils couplés.", "No devices paired yet"),
  devices_none_hint: m(
    "Générez le code d'appairage ci-dessus pour connecter le premier appareil du magasin.",
    "Generate a pairing code above to connect your first terminal."
  ),
  devices_status_active: m("Actif", "Active"),
  devices_status_revoking: m("annulation…", "Revoking…"),
  devices_status_revoked: m("Annulé", "Revoked"),
  devices_paired_on: m("jumelé quand {date}", "Paired {date}"),
  devices_last_seen: m("Dernière utilisation {when}", "Last seen {when}"),
  devices_seen_never: m("Je ne suis pas encore connecté.", "Never connected"),
  devices_seen_just_now: m("Il y a juste un instant", "just now"),
  devices_seen_min: m("{n} il y a une minute", "{n} min ago"),
  devices_seen_hr: m("{n} il y a une heure", "{n} hr ago"),
  devices_seen_day: m("{n} dernier jour", "{n} days ago"),
  devices_revoke: m("Annuler le jumelage", "Revoke"),
  devices_remove: m("supprimer", "Remove"),
  devices_revoke_q: m("Annuler le jumelage {name}?", "Revoke {name}?"),
  devices_revoke_confirm: m(
    "Cette machine sera sortie du magasin et utilisée. POS Vous ne pouvez pas recommencer tant que vous n'avez pas effectué de nouveau couplage.",
    "The terminal will be cut off from the store and can’t use the POS until it’s paired again."
  ),
  devices_revoke_sent: m(
    "Ordre d'annulation envoyé — La machine confirmera dans quelques secondes.",
    "Revoke requested — the store confirms within a few seconds."
  ),
  // store POS (heartbeat) — each store's own POS, synced with its store key
  devices_stores_title: m("Caisses des magasins", "Store POS"),
  devices_stores_sub: m(
    "La caisse de chaque magasin se synchronise avec sa clé de magasin et signale sa présence toutes les quelques secondes.",
    "Each store’s POS syncs with its store key and checks in every few seconds."
  ),
  devices_pos_online: m("En ligne", "Online"),
  devices_pos_stale: m("En retard", "Delayed"),
  devices_pos_offline: m("Hors ligne", "Offline"),
  devices_pos_never: m("Aucune synchronisation reçue pour l’instant", "No check-in received yet"),
  devices_pos_never_hint: m(
    "Ce magasin n’a pas encore contacté le nuage. Vérifiez la connexion Internet de la caisse et sa clé de magasin.",
    "This store hasn’t reached the cloud yet. Check the POS’s internet connection and its store key."
  ),
  devices_pos_seen_sec: m("il y a {n} s", "{n}s ago"),
  devices_pos_lan: m("Adresse réseau local", "LAN address"),
  devices_pos_public: m("Adresse publique", "Public address"),
  devices_pos_install: m("Installation", "Install"),
  devices_pos_version: m("Version", "Version"),
  devices_pos_contract: m("contrat v{n}", "contract v{n}"),
  devices_pos_none_reported: m("Non signalé", "Not reported"),
  devices_pos_devices: m("Terminaux associés à ce magasin", "Terminals on this store"),
  devices_pos_devices_none: m(
    "Aucun terminal supplémentaire — la caisse du magasin suffit.",
    "No extra terminals — the store’s own POS is all that’s needed."
  ),
  devices_pos_empty: m("Aucun magasin dans cette vue.", "No stores in this view."),
  devices_extra_title: m("Associer un terminal supplémentaire", "Pair an extra terminal"),
  devices_extra_optional: m("Facultatif", "Optional"),
  devices_extra_sub: m(
    "Seulement pour ajouter un autre terminal à un magasin. La caisse de chaque magasin est déjà connectée — rien à associer.",
    "Only to add another terminal to a store. Each store’s POS is already connected — nothing to pair."
  ),
  devices_extra_show: m("Associer un terminal", "Pair a terminal"),
  devices_extra_hide: m("Masquer", "Hide"),
  devices_extra_store: m("Magasin", "Store"),

  // ── menu (read-only: each store's tablet owns its menu) ───────────────
  menu_title: m("Menu", "Menu"),
  menu_sub: m(
    "Le menu se modifie sur la tablette de chaque magasin ; il apparaît ici après sa synchronisation.",
    "Menus are edited on each store’s tablet and appear here once it syncs."
  ),
  menu_off: m("Retiré de la vente", "Off menu"),
  menu_empty: m("Aucun menu synchronisé pour l’instant", "No menu synced yet"),
  menu_empty_hint: m(
    "Le menu apparaîtra dès que la tablette du magasin se sera connectée.",
    "It appears as soon as the store’s tablet connects."
  ),
  menu_cat_empty: m("Aucun article dans cette catégorie.", "No items here yet."),

  // ── staff + grants (read-only: each store's tablet owns its staff) ─────
  nav_staff: m("Personnel", "Staff"),
  staff_title: m("Personnel", "Staff"),
  staff_sub: m(
    "Le personnel et les droits se gèrent sur la tablette de chaque magasin.",
    "Staff and permissions are managed on each store’s tablet."
  ),
  staff_none: m("Aucun membre du personnel synchronisé", "No staff synced yet"),
  role_manager: m("Gérant", "Manager"),
  role_server: m("Serveur", "Server"),
  staff_active: m("Actif", "Active"),
  staff_inactive: m("Désactivé", "Inactive"),
  staff_overrides_n: m("{n} droit(s) ajusté(s)", "{n} overrides"),

  // grant matrix
  grants_roles_title: m("Droits selon le poste", "Role permissions"),
  grants_roles_sub: m(
    "Valeurs par défaut de chaque poste, telles que réglées sur la tablette du magasin.",
    "Defaults per role, as set on the store’s tablet."
  ),
  grants_overrides_title: m("Droits individuels", "This person’s permissions"),
  grants_overrides_sub: m("Remplacez les valeurs en fonction de l'emplacement par personne.", "Override the role default for this person"),
  col_permission: m("Droit", "Permission"),
  grant_default: m("selon le poste", "Role default"),
  grant_default_on: m("Par poste (autorisé)", "Role default (allowed)"),
  grant_default_off: m("Par poste (non autorisé)", "Role default (denied)"),
  grant_allow: m("permettre", "Allow"),
  grant_deny: m("Non autorisé", "Deny"),
  grant_overridden: m("Ajuster pour des personnes spécifiques", "Overridden"),

  // the fixed permission vocabulary (mirrors the store / CONTRACT §7)
  perm_void: m("Annuler la facture", "Void a check"),
  perm_refund: m("Remboursement", "Refund"),
  perm_discount_comp: m("Remise / Gratuit", "Discount / comp"),
  perm_cash_movement: m("L'argent entre et sort du tiroir", "Cash in / out"),
  perm_open_shift: m("Poste ouvert", "Open shift"),
  perm_close_shift: m("Fermer l'équipe", "Close shift"),
  perm_price_override: m("Modifier les prix/produits en dehors du menu", "Price override / open item"),
  perm_zone_open_close: m("Zone d'ouverture-fermeture", "Open / close a zone"),
  perm_edit_menu: m("Menu Modifier (86)", "Edit menu (86)"),
  perm_manage_staff: m("Gérer les employés", "Manage staff"),
} as const;

export type MsgKey = keyof typeof messages;
