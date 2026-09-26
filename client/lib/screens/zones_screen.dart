import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../pending_alerts.dart';
import '../widgets/brand.dart';
import '../widgets/floor_plan.dart';
import '../widgets/pin_pad.dart';
import '../widgets/resume_refresh.dart';
import 'check_screen.dart';
import 'floor_plan_edit_screen.dart';
import 'login_screen.dart';
import 'menu_management_screen.dart';
import 'sales_screen.dart';
import 'settings_screen.dart';
import 'shift_screen.dart';
import 'staff_screen.dart';

class ZonesScreen extends StatefulWidget {
  const ZonesScreen({super.key});

  @override
  State<ZonesScreen> createState() => _ZonesScreenState();
}

class _ZonesScreenState extends State<ZonesScreen> with ResumeRefresh {
  @override
  void onAppResume() {
    _loadAlertConfig();
    _reload();
  }

  late Future<List<Zone>> _zones;
  Timer? _poll;
  final _alerts = PendingAlerts();

  /// Selected room on the floor plan; null until the first load picks zone #1.
  String? _zoneId;
  bool _busy = false;
  bool _zonesRequestInFlight = false;

  /// Last successful zones payload. A FutureBuilder snapshot DROPS its data
  /// when a newer future completes with an error, so without this a single
  /// failed 5s poll would blank a working floor grid to the error screen
  /// mid-shift. Stale-but-real beats gone.
  List<Zone>? _lastZones;

  @override
  void initState() {
    super.initState();
    _loadAlertConfig();
    // header names the store; startup's health probe usually has it already
    Api.loadVenueName().then((_) {
      if (mounted) setState(() {});
    }, onError: (_) {});
    _zonesRequestInFlight = true;
    _zones = _fetchZonesSerialized();
    // occupancy/pending badges change from customer phones and other flows —
    // poll like the tables screen does, else the grid goes stale. TODO: push/SSE
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _reload());
  }

  @override
  void dispose() {
    _poll?.cancel();
    _alerts.dispose();
    super.dispose();
  }

  // Feed every successful poll into the alert controller (arrival chime +
  // escalation), reusing this one poll rather than a second path.
  Future<List<Zone>> _fetchZones() async {
    final zones = await Api.zones();
    _alerts.ingest(zones, DateTime.now());
    _lastZones = zones;
    return zones;
  }

  Future<List<Zone>> _fetchZonesSerialized() async {
    try {
      return await _fetchZones();
    } finally {
      _zonesRequestInFlight = false;
    }
  }

  Future<void> _loadAlertConfig() async {
    try {
      _alerts.configure(await Api.alertConfig());
    } catch (_) {
      // keep last-known config on a transient failure; alerts stay best-effort
    }
  }

  // FutureBuilder keeps the old grid while the new future resolves — no flicker.
  // Block body on purpose: `=> setState(() => _zones = Api.zones())` returns the
  // Future from the closure, and setState THROWS on that in debug builds — the
  // rebuild never got scheduled and the first poll tick's exception killed the
  // periodic timer. The screen then only refreshed on a global rebuild (e.g. the
  // language toggle) — that was the "stale zones" bug.
  void _reload() {
    if (!mounted || _zonesRequestInFlight) return;
    _zonesRequestInFlight = true;
    setState(() {
      _zones = _fetchZonesSerialized();
    });
  }

  Future<void> _changePin() async {
    final l = L.of(context);
    String step = 'current';
    String? current;
    String? first;
    final padKey = GlobalKey<PinPadState>();

    await showDialog(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialog) {
          final title = switch (step) {
            'current' => l.currentPin,
            'new' => l.newPin,
            _ => l.confirmNewPin,
          };
          return Dialog(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(l.changePin, style: T.headline()),
                  const SizedBox(height: 4),
                  Text(title, style: T.small()),
                  const SizedBox(height: 16),
                  PinPad(
                    key: padKey,
                    onComplete: (pin) async {
                      switch (step) {
                        case 'current':
                          current = pin;
                          setDialog(() => step = 'new');
                          padKey.currentState?.clear();
                        case 'new':
                          first = pin;
                          setDialog(() => step = 'confirm');
                          padKey.currentState?.clear();
                        default:
                          if (pin != first) {
                            ScaffoldMessenger.of(this.context).showSnackBar(
                              SnackBar(content: Text(l.pinMismatch)),
                            );
                            setDialog(() => step = 'new');
                            padKey.currentState?.clear();
                            return;
                          }
                          final messenger = ScaffoldMessenger.of(this.context);
                          try {
                            await Api.changePin(current!, pin);
                            if (context.mounted) Navigator.pop(context);
                            messenger.showSnackBar(
                              SnackBar(content: Text(l.pinChanged)),
                            );
                          } catch (e) {
                            if (e is SessionExpiredException) return;
                            messenger.showSnackBar(
                              SnackBar(content: Text('$e')),
                            );
                            setDialog(() => step = 'current');
                            padKey.currentState?.clear();
                          }
                      }
                    },
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: Text(l.cancel),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final isManager = Api.currentUser?.isManager ?? false;
    return Scaffold(
      // navy brand header: badge + venue, then labelled actions
      appBar: AppBar(
        toolbarHeight: 76,
        backgroundColor: T.navy,
        foregroundColor: T.onPrimary,
        shape: const Border(),
        titleSpacing: 16,
        title: _VenueTitle(userName: Api.currentUser?.name),
        actions: [
          const LangActions(color: T.onPrimary),
          const SizedBox(width: 4),
          _HeaderAction(
            icon: LucideIcons.utensils,
            label: l.navMenu,
            tooltip: l.manageMenu,
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const MenuManagementScreen()),
            ),
          ),
          _HeaderAction(
            icon: LucideIcons.barChart3,
            label: l.navReports,
            tooltip: l.shiftReports,
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const ShiftScreen())),
          ),
          // refund a finalized bill — recent closed bills, manager-gated
          _HeaderAction(
            icon: LucideIcons.receiptText,
            label: l.navRefunds,
            tooltip: l.refunds,
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const SalesScreen())),
          ),
          // floor-plan editor: shown to everyone, a manager PIN unlocks it
          // (same inline-approval pattern as void/86/zone close)
          _HeaderAction(
            icon: LucideIcons.pencilRuler,
            label: l.navLayout,
            tooltip: l.editLayout,
            onPressed: _openLayoutEditor,
          ),
          _HeaderAction(
            icon: LucideIcons.refreshCw,
            label: l.navRefresh,
            tooltip: l.navRefresh,
            onPressed: _reload,
          ),
          // top-right menu: settings (manager), change PIN (everyone), slips, logout
          PopupMenuButton<String>(
            tooltip: l.navMore,
            position: PopupMenuPosition.under,
            child: _HeaderAction(icon: LucideIcons.settings, label: l.navMore),
            onSelected: (v) async {
              switch (v) {
                case 'settings':
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const SettingsScreen()),
                  );
                case 'staff':
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const StaffScreen()),
                  );
                case 'pin':
                  _changePin();
                case 'slips':
                  // the pages carry every table's link: open with a ticket
                  try {
                    final ticket = await Api.slipsTicket();
                    await launchUrl(
                      Uri.parse(
                        '${Api.baseUrl}/slips?ticket=${Uri.encodeQueryComponent(ticket)}',
                      ),
                      mode: LaunchMode.externalApplication,
                    );
                  } catch (e) {
                    if (e is SessionExpiredException) return;
                    if (context.mounted) showApiError(context, e);
                  }
                case 'logout':
                  await Api.logout();
                  if (context.mounted) {
                    Navigator.of(context).pushReplacement(
                      MaterialPageRoute(builder: (_) => const LoginScreen()),
                    );
                  }
              }
            },
            itemBuilder: (context) => [
              if (isManager)
                PopupMenuItem(
                  value: 'settings',
                  child: Row(
                    children: [
                      const Icon(LucideIcons.slidersHorizontal, size: 18),
                      const SizedBox(width: 10),
                      Text(l.settings),
                    ],
                  ),
                ),
              if (Api.currentUser?.can(Perm.manageStaff) ?? false)
                PopupMenuItem(
                  value: 'staff',
                  child: Row(
                    children: [
                      const Icon(LucideIcons.users, size: 18),
                      const SizedBox(width: 10),
                      Text(l.staffTitle),
                    ],
                  ),
                ),
              PopupMenuItem(
                value: 'pin',
                child: Row(
                  children: [
                    const Icon(LucideIcons.keyRound, size: 18),
                    const SizedBox(width: 10),
                    Text(l.changePin),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'slips',
                child: Row(
                  children: [
                    const Icon(LucideIcons.printer, size: 18),
                    const SizedBox(width: 10),
                    Text(l.printSlips),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'logout',
                child: Row(
                  children: [
                    const Icon(
                      LucideIcons.logOut,
                      size: 18,
                      color: T.destructive,
                    ),
                    const SizedBox(width: 10),
                    Text(
                      l.logout,
                      style: const TextStyle(color: T.destructive),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          // Global "orders waiting" banner — visible from any zone, above the
          // grid. Clears itself when every pending order is actioned.
          _PendingAlertBanner(alerts: _alerts),
          Expanded(
            child: FutureBuilder<List<Zone>>(
              future: _zones,
              builder: (context, snap) {
                // only surface errors when there's nothing to show — a transient
                // poll failure must not blank a working grid (snap.data alone
                // is not enough: an errored refresh drops the snapshot's data)
                final zones = snap.data ?? _lastZones;
                if (snap.hasError && zones == null) {
                  return _ErrorRetry(error: '${snap.error}', onRetry: _reload);
                }
                if (zones == null) return const DelayedSpinner();
                if (zones.isEmpty) return const SizedBox.shrink();
                final zone = zones.firstWhere(
                  (z) => z.id == _zoneId,
                  orElse: () => zones.first,
                );
                // room switcher: one chip per zone, occupancy + status at
                // a glance; long-press = close/reopen (manager PIN)
                final chips = [
                  for (final z in zones)
                    _zoneChip(z, zones, selected: z.id == zone.id, l: l),
                  _addZoneChip(l),
                ];
                return LayoutBuilder(
                  builder: (context, c) {
                    // landscape tablet: rooms + legend in a side column so
                    // the floor gets the full height
                    if (c.maxWidth >= 900) {
                      return Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Container(
                            width: 260,
                            decoration: const BoxDecoration(
                              color: T.surface,
                              border: Border(
                                right: BorderSide(color: T.border),
                              ),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Expanded(
                                  child: ListView(
                                    padding: const EdgeInsets.fromLTRB(
                                      16,
                                      4,
                                      16,
                                      16,
                                    ),
                                    children: [
                                      SectionLabel(l.rooms),
                                      for (final chip in chips) ...[
                                        chip,
                                        const SizedBox(height: 10),
                                      ],
                                    ],
                                  ),
                                ),
                                const Divider(),
                                _FloorLegend(zone: zone),
                              ],
                            ),
                          ),
                          Expanded(
                            child: Padding(
                              padding: const EdgeInsets.all(16),
                              child: _floorPlan(zone, l),
                            ),
                          ),
                        ],
                      );
                    }
                    return Column(
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                          child: SingleChildScrollView(
                            scrollDirection: Axis.horizontal,
                            child: Row(
                              spacing: 10,
                              children: [
                                for (final chip in chips)
                                  IntrinsicWidth(child: chip),
                              ],
                            ),
                          ),
                        ),
                        Expanded(
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                            child: _floorPlan(zone, l),
                          ),
                        ),
                        _FloorLegend(zone: zone, horizontal: true),
                      ],
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  // Long-press → room-management sheet → inline manager PIN (same pattern as
  // void/86). Add/rename/delete/reorder + the operational close/reopen all live
  // here, where the rooms are laid out. Shown to everyone; a non-manager can't
  // complete any action without a manager's PIN.
  Future<void> _manageZone(Zone zone, List<Zone> zones) async {
    final l = L.of(context);
    final zoneName = l.name(zone.nameFr, zone.nameEn);
    final idx = zones.indexWhere((z) => z.id == zone.id);
    final willClose = !zone.isClosed;
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
              child: Align(
                alignment: Alignment.centerLeft,
                child: SectionLabel(zoneName),
              ),
            ),
            _sheetAction(ctx, l.renameRoom, LucideIcons.pencil, 'rename'),
            _sheetAction(
              ctx,
              willClose ? l.zoneCloseAction : l.zoneReopenAction,
              willClose ? LucideIcons.eyeOff : LucideIcons.eye,
              'toggle',
              color: willClose ? T.destructive : T.primary,
            ),
            if (idx > 0)
              _sheetAction(ctx, l.moveRoomLeft, LucideIcons.arrowLeft, 'left'),
            if (idx >= 0 && idx < zones.length - 1)
              _sheetAction(
                ctx,
                l.moveRoomRight,
                LucideIcons.arrowRight,
                'right',
              ),
            _sheetAction(
              ctx,
              l.deleteRoom,
              LucideIcons.trash2,
              'delete',
              color: T.destructive,
            ),
            ListTile(
              title: Text(l.cancel, style: T.small()),
              onTap: () => Navigator.pop(ctx),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (action == null || !mounted) return;
    switch (action) {
      case 'rename':
        await _renameZone(zone);
      case 'toggle':
        await _toggleZoneStatus(zone);
      case 'left':
        await _moveZone(zone, zones, -1);
      case 'right':
        await _moveZone(zone, zones, 1);
      case 'delete':
        await _deleteZone(zone);
    }
  }

  Widget _sheetAction(
    BuildContext ctx,
    String label,
    IconData icon,
    String value, {
    Color? color,
  }) => ListTile(
    leading: Icon(icon, size: 20, color: color ?? T.textPrimary),
    title: Text(
      label,
      style: T.text(color: color ?? T.textPrimary, weight: FontWeight.w600),
    ),
    onTap: () => Navigator.pop(ctx, value),
  );

  Future<void> _toggleZoneStatus(Zone zone) async {
    final l = L.of(context);
    final willClose = !zone.isClosed;
    final action = willClose ? l.zoneCloseAction : l.zoneReopenAction;
    final zoneName = l.name(zone.nameFr, zone.nameEn);
    final approval = await requireGrant(
      context,
      Perm.zoneOpenClose,
      title: '$action · $zoneName',
    );
    if (approval == null || !mounted) return;
    try {
      await Api.setZoneStatus(
        zone.id,
        willClose ? 'CLOSED' : 'OPEN',
        approval.managerPin,
      );
      _reload();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// Two-field (FR + EN) name dialog shared by add and rename. Returns
  /// (nameFr, nameEn) trimmed, or null if cancelled / left blank.
  Future<(String, String)?> _zoneNameDialog({
    required String title,
    String nameFr = '',
    String nameEn = '',
  }) async {
    final l = L.of(context);
    final frCtl = TextEditingController(text: nameFr);
    final enCtl = TextEditingController(text: nameEn);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: SizedBox(
          width: 360,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: frCtl,
                autofocus: true,
                decoration: InputDecoration(labelText: l.roomNameFrField),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: enCtl,
                decoration: InputDecoration(labelText: l.roomNameEnField),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.save),
          ),
        ],
      ),
    );
    if (ok != true) return null;
    final fr = frCtl.text.trim();
    final en = enCtl.text.trim();
    if (fr.isEmpty || en.isEmpty) return null;
    return (fr, en);
  }

  /// Add an empty room. Selects it on success so the blank floor plan (with its
  /// "tap the pencil to build the layout" onboarding) is ready for tables/objects.
  Future<void> _addZone() async {
    final l = L.of(context);
    final names = await _zoneNameDialog(title: l.addRoom);
    if (names == null || !mounted) return;
    final pin = await askManagerPin(context, title: l.addRoom);
    if (pin == null || !mounted) return;
    try {
      final created = await Api.createZone(names.$1, names.$2, pin);
      if (!mounted) return;
      setState(() => _zoneId = created.id);
      _reload();
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.roomCreated)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _renameZone(Zone zone) async {
    final l = L.of(context);
    final names = await _zoneNameDialog(
      title: l.renameRoom,
      nameFr: zone.nameFr,
      nameEn: zone.nameEn,
    );
    if (names == null || !mounted) return;
    final pin = await askManagerPin(
      context,
      title: '${l.renameRoom} · ${l.name(zone.nameFr, zone.nameEn)}',
    );
    if (pin == null || !mounted) return;
    try {
      await Api.renameZone(
        zone.id,
        nameFr: names.$1,
        nameEn: names.$2,
        managerPin: pin,
      );
      _reload();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// Swap the room with its neighbour and persist the whole order. [delta] is
  /// -1 (left) or +1 (right).
  Future<void> _moveZone(Zone zone, List<Zone> zones, int delta) async {
    final l = L.of(context);
    final ids = [for (final z in zones) z.id];
    final idx = ids.indexOf(zone.id);
    final target = idx + delta;
    if (idx < 0 || target < 0 || target >= ids.length) return;
    final tmp = ids[idx];
    ids[idx] = ids[target];
    ids[target] = tmp;
    final pin = await askManagerPin(context, title: l.manageRoom);
    if (pin == null || !mounted) return;
    try {
      await Api.reorderZones(ids, pin);
      _reload();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _deleteZone(Zone zone) async {
    final l = L.of(context);
    final zoneName = l.name(zone.nameFr, zone.nameEn);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.deleteRoom),
        content: Text(l.deleteRoomConfirm(zoneName)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: T.destructive,
              foregroundColor: T.onDestructive,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.deleteRoom),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final pin = await askManagerPin(
      context,
      title: '${l.deleteRoom} · $zoneName',
    );
    if (pin == null || !mounted) return;
    try {
      await Api.deleteZone(zone.id, pin);
      if (!mounted) return;
      // dropping the selected room? clear selection so build re-picks the first
      if (_zoneId == zone.id) _zoneId = null;
      _reload();
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.roomDeleted)));
    } catch (e) {
      // zone_not_empty comes back as a clear localized error
      if (mounted) showApiError(context, e);
    }
  }

  /// Room-switcher chip: zone name + occupancy, closed pill / pending count.
  /// Same status priority as the old zone card — a room with orders waiting
  /// gets the same amber breathing glow as an individual pending table.
  Widget _zoneChip(
    Zone zone,
    List<Zone> zones, {
    required bool selected,
    required L l,
  }) {
    final open = zone.tables.where((t) => t.openCheckId != null).length;
    final pendingCount = zone.tables.fold<int>(
      0,
      (sum, t) => sum + t.pendingCount,
    );
    final chip = PosPanel(
      color: selected ? T.navy : T.surface,
      borderColor: pendingCount > 0
          ? T.pending
          : (selected ? T.navy : T.border),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      onTap: () => setState(() => _zoneId = zone.id),
      onLongPress: () => _manageZone(zone, zones),
      child: Row(
        children: [
          Expanded(
            child: Text(
              l.name(zone.nameFr, zone.nameEn),
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: T.text(
                size: 17,
                weight: FontWeight.w600,
                color: selected ? T.onPrimary : T.textPrimary,
              ),
            ),
          ),
          const SizedBox(width: 10),
          if (zone.isClosed)
            Pill(l.zoneClosed, color: selected ? T.onPrimary : T.destructive)
          else
            Pill(
              '$open/${zone.tables.length}',
              color: selected
                  ? T.onPrimary
                  : (open > 0 ? T.accent : T.textMuted),
            ),
          if (pendingCount > 0) ...[
            const SizedBox(width: 8),
            PendingBadge(pendingCount),
          ],
        ],
      ),
    );
    return pendingCount > 0
        ? PulsingGlow(radius: T.radiusMedium, child: chip)
        : chip;
  }

  /// Trailing "+" chip in the room switcher: add a new room. Shown to everyone
  /// like the layout-editor pencil; a manager PIN gates the actual creation.
  Widget _addZoneChip(L l) => PosPanel(
    color: Colors.transparent,
    borderColor: T.border,
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    onTap: _addZone,
    child: Row(
      children: [
        const Icon(LucideIcons.plus, size: 18, color: T.textMuted),
        const SizedBox(width: 6),
        Text(
          l.addRoom,
          style: T.text(weight: FontWeight.w600, color: T.textMuted),
        ),
      ],
    ),
  );

  /// Service-mode floor plan: tables at their real coords; tap = open/resume
  /// the check, exactly like the old card grid.
  Widget _floorPlan(Zone zone, L l) {
    if (zone.tables.isEmpty) {
      return Center(
        child: Text(
          l.emptyZoneOnboarding,
          style: T.small(),
          textAlign: TextAlign.center,
        ),
      );
    }
    final now = DateTime.now();
    return FloorPlanViewport(
      contentBounds: floorContentBounds(zone),
      builder: (scale) => [
        // structural props first — they sit beneath the tables as quiet context
        for (final o in zone.objects)
          placedObject(
            o,
            scale,
            child: FloorObjectShape(object: o, scale: scale),
          ),
        for (final t in zone.tables)
          placedTable(
            t,
            scale,
            child: GestureDetector(
              onTap: () => _tapTable(zone, t, l),
              // Long-press on a TABLE = its scan-to-order QR (the zone chip's
              // long-press is room management — different element, no clash).
              onLongPress: () => _showTableQr(zone, t, l),
              child: TableShape(
                table: t,
                scale: scale,
                statusColor: tableStatusColor(t, zoneClosed: zone.isClosed),
                subtitle: t.openCheckId != null
                    ? money(t.openCheckTotalCents ?? 0)
                    : l.seatsShort(t.seats),
                detail: _openedFor(t, now, l),
                // seat count is the only optional line: small free tables
                // show just their label
                subtitleOptional: t.openCheckId == null,
              ),
            ),
          ),
      ],
    );
  }

  /// "25 min" since the table's check opened; null for a free table.
  static String? _openedFor(TableInfo t, DateTime now, L l) {
    final at = t.openCheckId == null
        ? null
        : DateTime.tryParse(t.openCheckOpenedAt ?? '');
    if (at == null) return null;
    final d = now.difference(at);
    return l.openFor(d.isNegative ? Duration.zero : d);
  }

  /// Edit mode for the zone currently on screen. The PIN collected here
  /// travels with every editor mutation, each re-verified server-side.
  Future<void> _openLayoutEditor() async {
    final l = L.of(context);
    final List<Zone> zones;
    try {
      zones = await _zones;
    } catch (_) {
      return; // load failed — the error state is already on screen
    }
    if (zones.isEmpty || !mounted) return;
    final zone = zones.firstWhere(
      (z) => z.id == _zoneId,
      orElse: () => zones.first,
    );
    final zoneName = l.name(zone.nameFr, zone.nameEn);
    final pin = await askManagerPin(
      context,
      title: '${l.editLayout} · $zoneName',
    );
    if (pin == null || !mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => FloorPlanEditScreen(zone: zone, managerPin: pin),
      ),
    );
    _reload(); // layout and/or tables changed
  }

  /// The scan-to-order URL for a table: the server's random per-table link
  /// (/m/t/{token}) on the LAN origin, not the tablet-only API origin.
  static String? _menuUrl(TableInfo table, String storeBaseUrl) =>
      table.menuPath == null ? null : '$storeBaseUrl${table.menuPath}';

  /// Manager: rotate this table's link so its old slips stop working, then
  /// reload so the on-screen QR shows the new one.
  Future<void> _regenerateTableLink(TableInfo table) async {
    final l = L.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.regenerateTableLink),
        content: Text(l.regenerateTableLinkConfirm(table.displayLabel)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.regenerate),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await Api.regenerateTableLink(table.id);
      messenger.showSnackBar(SnackBar(content: Text(l.tableLinkRegenerated)));
      _reload();
    } catch (e) {
      if (e is SessionExpiredException) return;
      if (mounted) showApiError(context, e);
    }
  }

  /// On-screen stand-in for the printed QR slip: long-press a table → show its
  /// scan-to-order QR big enough for a guest to scan straight off the tablet.
  Future<void> _showTableQr(Zone zone, TableInfo table, L l) async {
    String? storeBaseUrl;
    try {
      storeBaseUrl = Api.usesEmbeddedStore
          ? Api.phoneQrBaseUrl((await Api.cloudInfo())['storeUrl'] as String?)
          : Api.phoneQrBaseUrl(Api.baseUrl);
    } catch (_) {
      // A QR with a guessed or tablet-only address would be worse than none.
    }
    if (!mounted) return;
    final url = storeBaseUrl == null ? null : _menuUrl(table, storeBaseUrl);
    if (url == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.tableQrNeedsWifi)));
      return;
    }
    final isManager = Api.currentUser?.isManager ?? false;
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        // Deliberately a LIGHT surface (receipt paper) with dark text: the QR
        // reads like the printed slip, and — critically — the dialog is always
        // unmistakably a dialog on the near-black theme. A blank content subtree
        // must never again pass for a screen lock (see table-qr-dialog-invisible).
        backgroundColor: T.receiptPaper,
        title: Text(
          l.tableQrTitle(table.displayLabel),
          style: const TextStyle(color: T.receiptInk),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              color: Colors.white,
              padding: const EdgeInsets.all(12),
              child: _tableQrImage(url),
            ),
            const SizedBox(height: 12),
            Text(
              url,
              style: T.small(color: T.receiptInk),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 4),
            Text(
              l.tableQrHint,
              style: T.small(color: T.receiptInk),
              textAlign: TextAlign.center,
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(l.close)),
          if (isManager)
            TextButton.icon(
              icon: const Icon(LucideIcons.refreshCw, size: 18),
              label: Text(l.regenerateTableLink),
              onPressed: () {
                Navigator.pop(ctx);
                _regenerateTableLink(table);
              },
            ),
          FilledButton.icon(
            icon: const Icon(LucideIcons.printer, size: 18),
            label: Text(l.printQrCode),
            onPressed: () {
              Navigator.pop(ctx);
              _printTableSlip(table);
            },
          ),
        ],
      ),
    );
  }

  /// A 260px scan-to-order QR that can NEVER take the dialog down with it.
  ///
  /// The real trap: [AlertDialog] sizes its content with an [IntrinsicWidth],
  /// which walks the subtree asking for intrinsic dimensions — but [QrImageView]'s
  /// root is a [LayoutBuilder], and a LayoutBuilder THROWS when asked for
  /// intrinsics ("LayoutBuilder does not support returning intrinsic dimensions").
  /// That aborts layout of the whole dialog, so it renders as an invisible scrim
  /// that reads like a screen lock. The fix is the tight [SizedBox] wrapper: a
  /// box with tight width+height answers intrinsic queries with its own fixed
  /// size and never descends into the LayoutBuilder.
  ///
  /// Pre-validation + [errorStateBuilder] are the secondary guard: an unencodable
  /// payload renders the URL as text instead of a blank square.
  Widget _tableQrImage(String url) {
    const double size = 260;
    final validation = QrValidator.validate(
      data: url,
      version: QrVersions.auto,
      errorCorrectionLevel: QrErrorCorrectLevel.M,
    );
    return SizedBox(
      width: size,
      height: size,
      child: validation.status != QrValidationStatus.valid
          ? _qrFallback(url, size)
          : QrImageView(
              data: url,
              size: size,
              version: QrVersions.auto,
              errorCorrectionLevel: QrErrorCorrectLevel.M,
              backgroundColor: Colors.white,
              eyeStyle: const QrEyeStyle(
                eyeShape: QrEyeShape.square,
                color: Colors.black,
              ),
              dataModuleStyle: const QrDataModuleStyle(
                dataModuleShape: QrDataModuleShape.square,
                color: Colors.black,
              ),
              errorStateBuilder: (ctx, err) => _qrFallback(url, size),
            ),
    );
  }

  /// Visible stand-in when the QR itself can't render — the guest can still
  /// reach the menu by typing the URL, and the dialog stays legible.
  Widget _qrFallback(String url, double size) => SizedBox(
    width: size,
    height: size,
    child: Center(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Text(
          url,
          style: const TextStyle(color: T.receiptInk),
          textAlign: TextAlign.center,
        ),
      ),
    ),
  );

  /// Print the table's scan-to-order QR on the thermal printer. The QR payload
  /// is built server-side (same /m/t/{token} link as the on-screen code). Never
  /// throws at the user: a missing/offline printer comes back as a status toast.
  Future<void> _printTableSlip(TableInfo table) async {
    final l = L.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final status = await Api.printTableSlip(table.id);
      final msg = !status.configured
          ? l.printerNotConfigured
          : status.online
          ? l.slipSentToPrinter
          : l.printerOffline;
      messenger.showSnackBar(SnackBar(content: Text(msg)));
    } catch (e) {
      if (e is SessionExpiredException) return;
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _tapTable(Zone zone, TableInfo table, L l) async {
    // Closed zone: can't start a NEW check on a free table, but an existing
    // open check is still tappable (edit / tender it) — same rule as before.
    if (zone.isClosed && table.openCheckId == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.newCheckBlockedZoneClosed)));
      return;
    }
    if (_busy) return;
    setState(() => _busy = true);
    try {
      // idempotent server-side: returns the existing open check if any
      final check = await Api.openCheck(table.id);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) =>
              CheckScreen(checkId: check.id, tableLabel: table.displayLabel),
        ),
      );
      _reload();
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// Full-width alert bar shown once the oldest un-actioned pending order passes
/// the escalate window. Ticks its own age display every second (display only —
/// no server poll); colour ramps amber → red the longer orders sit.
class _PendingAlertBanner extends StatefulWidget {
  final PendingAlerts alerts;
  const _PendingAlertBanner({required this.alerts});

  @override
  State<_PendingAlertBanner> createState() => _PendingAlertBannerState();
}

class _PendingAlertBannerState extends State<_PendingAlertBanner> {
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {}); // refresh the age label between polls
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return ListenableBuilder(
      listenable: widget.alerts,
      builder: (context, _) {
        final now = DateTime.now();
        if (!widget.alerts.bannerActive(now)) return const SizedBox.shrink();
        final age = widget.alerts.age(now)!;
        // amber → red across the first 3 minutes past the escalate window
        final t = (widget.alerts.overdueSeconds(now) / 180).clamp(0.0, 1.0);
        final color = Color.lerp(T.attention, T.destructive, t)!;
        return Material(
          color: color,
          child: SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              child: Row(
                children: [
                  const Icon(
                    LucideIcons.bellRing,
                    size: 20,
                    color: Colors.white,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      '${l.ordersWaiting(widget.alerts.pendingCount)} · ${l.alertAge(age)}',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ErrorRetry extends StatelessWidget {
  final String error;
  final VoidCallback onRetry;
  const _ErrorRetry({required this.error, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(l.cannotReachServer),
          Text(error, style: T.small()),
          const SizedBox(height: 8),
          FilledButton(onPressed: onRetry, child: Text(l.retry)),
        ],
      ),
    );
  }
}

/// Floor header title: small badge, venue brand, then location and the
/// signed-in staff member. Two short lines, so nothing truncates.
class _VenueTitle extends StatelessWidget {
  final String? userName;
  const _VenueTitle({this.userName});

  @override
  Widget build(BuildContext context) {
    final second = [
      if (Api.venueLocation != null) Api.venueLocation!,
      if (userName != null && userName!.isNotEmpty) userName!,
    ].join('  ·  ');
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const BrandLogo(size: 48, ring: true),
        const SizedBox(width: 14),
        Flexible(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                Api.venueBrand,
                maxLines: 1,
                overflow: TextOverflow.fade,
                softWrap: false,
                style: T.text(
                  size: 21,
                  weight: FontWeight.w700,
                  color: T.onPrimary,
                ),
              ),
              if (second.isNotEmpty)
                Text(
                  second,
                  maxLines: 1,
                  overflow: TextOverflow.fade,
                  softWrap: false,
                  style: T.small(color: T.onNavyMuted, weight: FontWeight.w500),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Header action: icon over a short label (touch-first — no hover needed to
/// learn what it does); the tooltip carries the longer name.
class _HeaderAction extends StatelessWidget {
  final IconData icon;
  final String label;
  final String? tooltip;

  /// Null when a parent (the overflow menu button) handles the tap.
  final VoidCallback? onPressed;
  const _HeaderAction({
    required this.icon,
    required this.label,
    this.tooltip,
    this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final body = ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 72, minHeight: T.minTouch),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 22, color: T.onPrimary),
            const SizedBox(height: 4),
            Text(
              label,
              maxLines: 1,
              style: T
                  .small(color: T.onNavyMuted, weight: FontWeight.w600)
                  .copyWith(fontSize: 12.5),
            ),
          ],
        ),
      ),
    );
    if (onPressed == null) return body;
    return Tooltip(
      message: tooltip ?? label,
      child: InkWell(
        onTap: onPressed,
        borderRadius: T.radiusMedium,
        child: body,
      ),
    );
  }
}

/// What the table colours mean, plus this room's occupancy.
class _FloorLegend extends StatelessWidget {
  final Zone zone;
  final bool horizontal;
  const _FloorLegend({required this.zone, this.horizontal = false});

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final open = zone.tables.where((t) => t.openCheckId != null).length;
    Widget swatch(Color fill, Color border, String label) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 18,
          height: 18,
          decoration: BoxDecoration(
            color: fill,
            borderRadius: T.radiusSmall,
            border: Border.all(color: border, width: 1.5),
          ),
        ),
        const SizedBox(width: 8),
        Text(label, style: T.small(color: T.textPrimary)),
      ],
    );
    final items = [
      swatch(T.surface, T.border, l.legendFree),
      swatch(T.accent, T.accent, l.legendOccupied),
      swatch(T.pending, T.pending, l.legendPending),
    ];
    if (horizontal) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: Wrap(spacing: 20, runSpacing: 8, children: items),
      );
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 10,
        children: [
          Text(
            l.tablesOccupied(open, zone.tables.length),
            style: T.text(size: 16, weight: FontWeight.w600),
          ),
          ...items,
        ],
      ),
    );
  }
}
