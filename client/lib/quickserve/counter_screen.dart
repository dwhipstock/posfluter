import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../kitchen/kitchen_i18n.dart';
import '../kitchen/kitchen_screen.dart';
import '../screens/check_screen.dart';
import '../screens/login_screen.dart';
import '../screens/receipt_screen.dart';
import '../screens/sales_screen.dart';
import '../screens/settings_screen.dart';
import '../screens/shift_screen.dart';
import '../widgets/resume_refresh.dart';
import '../widgets/stale_check.dart';
import '../widgets/url_qr.dart';
import 'quick_serve_i18n.dart';

/// The quick-serve counter (Copper Lantern Express), one flow for every
/// order, like a burger chain: it opens on a new order (the item grid and the
/// order panel), dine in / take out is a toggle on the panel (a tray or a
/// bag, nothing else changes), Pay, and the next new order opens by itself.
/// Only a paid order is an order: the store numbers it then and sends it to
/// the kitchen. An empty order is never stored. Kiosk orders wait in the
/// strip at the top until the cashier takes their money; the Orders panel
/// moves paid ones along (ready, picked up), recalls and reprints.
class CounterScreen extends StatefulWidget {
  /// How often the kiosk queue is refreshed (kiosk orders arrive on their own).
  final Duration pollEvery;
  const CounterScreen({super.key, this.pollEvery = const Duration(seconds: 4)});

  @override
  State<CounterScreen> createState() => _CounterScreenState();
}

class _CounterScreenState extends State<CounterScreen> with ResumeRefresh {
  /// The order on the panel; 0 = a new one, not stored until its first item.
  int _checkId = 0;

  /// Its dine in / take out, and the store's default for a new order.
  String _mode = 'TAKE_OUT';
  String _defaultMode = 'TAKE_OUT';

  /// A kiosk order being paid: its number ("K12"); null = rung here.
  String? _kiosk;

  /// That order's number (105) for the pay and receipt bars ("Order #105").
  int? _orderNumber;

  List<CounterOrder> _waiting = const [];
  Timer? _poll;

  @override
  void onAppResume() => _reloadWaiting();

  @override
  void initState() {
    super.initState();
    _loadDefaultMode();
    _reloadWaiting();
    _poll = Timer.periodic(widget.pollEvery, (_) => _reloadWaiting());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _loadDefaultMode() async {
    try {
      final m = await QuickServeApi.defaultMode();
      if (!mounted) return;
      setState(() {
        _defaultMode = m;
        if (_checkId == 0) _mode = m;
      });
    } catch (_) {} // take out, as before
  }

  Future<void> _reloadWaiting() async {
    try {
      final w = await QuickServeApi.waiting();
      if (mounted) setState(() => _waiting = w);
    } catch (_) {} // the strip keeps what it had
  }

  /// The first item of a new order: the store keeps it from now on.
  Future<int> _createOrder(
    Item item,
    Variant variant,
    int qty,
    String? note,
  ) async {
    final o = await QuickServeApi.create(
      _mode,
      item.id,
      variant.id,
      qty,
      note: note,
      expectedPriceCents: variant.priceCents,
    );
    if (mounted) setState(() => _checkId = o.checkId);
    return o.checkId;
  }

  /// Paid (or emptied / cleared): the next new order, in the default mode.
  void _next(bool paid) {
    setState(() {
      _checkId = 0;
      _kiosk = null;
      _orderNumber = null;
      _mode = _defaultMode;
    });
    _reloadWaiting();
  }

  Future<void> _discard() async {
    if (_checkId != 0) await QuickServeApi.discard(_checkId);
  }

  Future<void> _setMode(String mode) async {
    if (mode == _mode) return;
    final before = _mode;
    setState(() => _mode = mode);
    if (_checkId == 0) return; // not stored yet: it goes with the first item
    final id = _checkId;
    try {
      await QuickServeApi.setMode(id, mode);
    } catch (e) {
      if (!mounted) return;
      setState(() => _mode = before);
      // the order went away under the panel (expired, paid or cleared on
      // another device): say so and open a new one
      final gone = mayBeStaleCheck(e) ? await goneStatus(id) : null;
      if (!mounted) return;
      if (gone != null && id == _checkId) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            key: const Key('check-gone'),
            content: Text(L.of(context).checkGone(gone, order: true)),
          ),
        );
        _next(false);
        return;
      }
      showApiError(context, e);
    }
  }

  /// Take a kiosk order's money: it loads on the panel. An order being rung
  /// here is cleared first (the cashier says so); another kiosk order just
  /// goes back to the queue.
  Future<void> _takeKiosk(CounterOrder o) async {
    if (o.checkId == _checkId) return;
    if (_checkId != 0 && _kiosk == null) {
      final q = Q.of(context);
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(q.switchTitle),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(L.of(context).cancel),
            ),
            FilledButton(
              key: const Key('switch-confirm'),
              onPressed: () => Navigator.pop(context, true),
              child: Text(q.discard),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
      try {
        await QuickServeApi.discard(_checkId);
      } catch (e) {
        // already gone (expired, cleared elsewhere): nothing to clear
        final gone = mayBeStaleCheck(e) ? await goneStatus(_checkId) : null;
        if (gone == null) {
          if (mounted) showApiError(context, e);
          return;
        }
      }
    }
    if (!mounted) return;
    setState(() {
      _checkId = o.checkId;
      _mode = o.serviceMode;
      _kiosk = o.label;
      _orderNumber = o.orderNumber;
    });
  }

  Future<void> _openOrders() =>
      showDialog<void>(context: context, builder: (_) => const _OrdersPanel());

  Future<void> _pairKiosk() async {
    final q = Q.of(context);
    try {
      final c = await QuickServeApi.kioskCode();
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(q.pairKiosk),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SelectableText(
                c.code,
                style: const TextStyle(
                  fontSize: 56,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 8,
                ),
              ),
              const SizedBox(height: 12),
              Text(q.pairKioskBody(c.expiresInSeconds ~/ 60)),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: Text(MaterialLocalizations.of(context).closeButtonLabel),
            ),
          ],
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _pickupBoard() async {
    final q = Q.of(context);
    String base = Api.baseUrl;
    try {
      // the embedded store answers on loopback; a TV needs its LAN address
      final lan = Api.phoneQrBaseUrl(
        (await Api.cloudInfo())['storeUrl'] as String?,
      );
      if (lan != null) base = lan;
    } catch (_) {}
    if (!mounted) return;
    final url = '$base/pickup';
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(q.pickupBoard),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(q.pickupBoardBody, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            UrlQr(url),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(MaterialLocalizations.of(context).closeButtonLabel),
          ),
        ],
      ),
    );
  }

  Widget _action(
    IconData icon,
    String label,
    VoidCallback onPressed, {
    Key? key,
  }) => TextButton.icon(
    key: key,
    style: TextButton.styleFrom(foregroundColor: T.onPrimary),
    onPressed: onPressed,
    icon: Icon(icon, size: 20),
    label: Text(label),
  );

  /// Dine in / take out on the order panel: a tray or a bag.
  Widget _modeToggle(Q q) {
    Widget choice(String mode, IconData icon, String label) {
      final on = _mode == mode;
      return Expanded(
        child: SizedBox(
          height: 56,
          child: OutlinedButton(
            key: Key('mode-$mode'),
            style: OutlinedButton.styleFrom(
              backgroundColor: on ? T.navy : T.surface,
              foregroundColor: on ? T.onPrimary : T.navy,
              side: BorderSide(color: on ? T.navy : T.border, width: 2),
              padding: const EdgeInsets.symmetric(horizontal: 8),
            ),
            onPressed: () => _setMode(mode),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 22),
                const SizedBox(width: 8),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: T.text(
                        size: 16,
                        weight: FontWeight.w700,
                        color: on ? T.onPrimary : T.navy,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Semantics(
      label: q.serviceMode,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
        child: Row(
          children: [
            choice('TAKE_OUT', LucideIcons.shoppingBag, q.takeOut),
            const SizedBox(width: 8),
            choice('DINE_IN', LucideIcons.utensils, q.dineIn),
          ],
        ),
      ),
    );
  }

  /// Kiosk orders waiting to be paid: one tap loads one on the panel.
  Widget _kioskStrip(Q q) {
    return Container(
      height: 60,
      color: T.surface,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: [
          const Icon(LucideIcons.tabletSmartphone, size: 20, color: T.navy),
          const SizedBox(width: 8),
          Text(
            q.kiosk,
            style: T.text(size: 16, weight: FontWeight.w700, color: T.navy),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: _waiting.isEmpty
                ? Text(
                    q.noKioskOrders,
                    style: T.small(color: T.textMuted),
                    overflow: TextOverflow.ellipsis,
                  )
                : ListView.separated(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    itemCount: _waiting.length,
                    separatorBuilder: (_, _) => const SizedBox(width: 8),
                    itemBuilder: (_, i) {
                      final o = _waiting[i];
                      final on = o.checkId == _checkId;
                      return FilledButton.tonal(
                        key: Key('kiosk-${o.checkId}'),
                        style: FilledButton.styleFrom(
                          backgroundColor: on
                              ? T.accent
                              : T.accent.withValues(alpha: .16),
                          foregroundColor: on ? T.onAccent : T.textPrimary,
                          padding: const EdgeInsets.symmetric(horizontal: 14),
                        ),
                        onPressed: () => _takeKiosk(o),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              o.label,
                              style: T.text(
                                size: 17,
                                weight: FontWeight.w800,
                                color: on ? T.onAccent : T.textPrimary,
                              ),
                            ),
                            const SizedBox(width: 8),
                            Icon(
                              o.takeOut
                                  ? LucideIcons.shoppingBag
                                  : LucideIcons.utensils,
                              size: 16,
                            ),
                            const SizedBox(width: 8),
                            Text(
                              '${money(o.outstandingCents)} · '
                              '${q.toPay.toLowerCase()}',
                            ),
                            // alcohol on the order: check the guest's ID (21+)
                            if (o.hasAlcohol) ...[
                              const SizedBox(width: 8),
                              Tooltip(
                                message: q.idCheck(
                                  StoreProfile.current.legalAge,
                                ),
                                child: Row(
                                  key: Key('kiosk-id-${o.checkId}'),
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(LucideIcons.idCard, size: 16),
                                    const SizedBox(width: 4),
                                    Text(
                                      '${StoreProfile.current.legalAge}+',
                                      style: const TextStyle(
                                        fontWeight: FontWeight.w800,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final q = Q.of(context);
    final isManager = Api.currentUser?.isManager ?? false;
    return Scaffold(
      appBar: AppBar(
        toolbarHeight: 64,
        backgroundColor: T.navy,
        foregroundColor: T.onPrimary,
        shape: const Border(),
        // the theme's title is navy (for the white bars): white on this one
        titleTextStyle: T.headline(color: T.onPrimary),
        title: Text(
          Api.venueName ?? q.orders,
          key: const Key('counter-title'),
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        actions: [
          _action(
            LucideIcons.clipboardList,
            q.orders,
            _openOrders,
            key: const Key('orders-button'),
          ),
          if (KitchenApi.enabled)
            _action(
              LucideIcons.chefHat,
              K.of(context).kitchen,
              () => Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const KitchenScreen())),
            ),
          _action(LucideIcons.monitor, q.pickupBoard, _pickupBoard),
          IconButton(
            tooltip: l.navReports,
            icon: const Icon(LucideIcons.barChart3),
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const ShiftScreen())),
          ),
          IconButton(
            tooltip: l.navRefunds,
            icon: const Icon(LucideIcons.receiptText),
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const SalesScreen())),
          ),
          if (isManager) ...[
            IconButton(
              key: const Key('kiosks'),
              tooltip: q.kiosks,
              icon: const Icon(LucideIcons.tabletSmartphone),
              onPressed: () => showDialog<void>(
                context: context,
                builder: (_) => KiosksDialog(onPair: _pairKiosk),
              ),
            ),
            IconButton(
              tooltip: l.settings,
              icon: const Icon(LucideIcons.slidersHorizontal),
              onPressed: () async {
                await Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const SettingsScreen()),
                );
                _loadDefaultMode();
              },
            ),
          ],
          IconButton(
            tooltip: l.logout,
            icon: const Icon(LucideIcons.logOut),
            onPressed: () async {
              await Api.logout();
              if (context.mounted) {
                Navigator.of(context).pushReplacement(
                  MaterialPageRoute(builder: (_) => const LoginScreen()),
                );
              }
            },
          ),
          const LangActions(color: T.onPrimary),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          _kioskStrip(q),
          const Divider(height: 1),
          Expanded(
            child: CheckScreen(
              checkId: _checkId,
              tableLabel: _kiosk != null ? q.kioskOrder(_kiosk!) : q.newOrder,
              counterOrder: true,
              orderNumber: _orderNumber,
              createOrder: _createOrder,
              onFinished: _next,
              onDiscard: _discard,
              panelTop: _modeToggle(q),
            ),
          ),
        ],
      ),
    );
  }
}

/// The manager's kiosks: the ones paired with this store, each with Unpair
/// (a lost, stolen or replaced kiosk stops taking orders at once, no portal
/// or internet needed), and Pair a kiosk for a new one.
class KiosksDialog extends StatefulWidget {
  final Future<void> Function() onPair;
  const KiosksDialog({super.key, required this.onPair});

  @override
  State<KiosksDialog> createState() => _KiosksDialogState();
}

class _KiosksDialogState extends State<KiosksDialog> {
  List<PairedKiosk>? _kiosks;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    try {
      final k = await QuickServeApi.kiosks();
      if (mounted) {
        setState(() {
          _kiosks = k;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _unpair(PairedKiosk k) async {
    final q = Q.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(q.unpairTitle(k.name)),
        content: Text(q.unpairBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(L.of(context).cancel),
          ),
          FilledButton(
            key: const Key('unpair-confirm'),
            style: FilledButton.styleFrom(backgroundColor: T.destructive),
            onPressed: () => Navigator.pop(context, true),
            child: Text(q.unpair),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final left = await QuickServeApi.unpairKiosk(k.deviceId);
      if (!mounted) return;
      setState(() => _kiosks = left);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(q.unpaired(k.name))));
    } catch (e) {
      if (mounted) showApiError(context, e);
      _reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = Q.of(context);
    final kiosks = _kiosks;
    return AlertDialog(
      title: Text(q.kiosks),
      content: SizedBox(
        width: 480,
        child: _error != null
            ? Text('$_error')
            : kiosks == null
            ? const Center(child: CircularProgressIndicator())
            : kiosks.isEmpty
            ? Text(q.noKiosks, key: const Key('no-kiosks'))
            : Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final k in kiosks)
                    ListTile(
                      key: Key('paired-kiosk-${k.deviceId}'),
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(LucideIcons.tabletSmartphone),
                      title: Text(k.name),
                      trailing: OutlinedButton(
                        key: Key('unpair-${k.deviceId}'),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: T.destructive,
                        ),
                        onPressed: () => _unpair(k),
                        child: Text(q.unpair),
                      ),
                    ),
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(MaterialLocalizations.of(context).closeButtonLabel),
        ),
        FilledButton.icon(
          key: const Key('pair-kiosk'),
          onPressed: () async {
            await widget.onPair();
            _reload();
          },
          icon: const Icon(LucideIcons.plus),
          label: Text(q.pairKiosk),
        ),
      ],
    );
  }
}

/// Today's paid orders: move them along the pickup board (ready, picked up),
/// recall one handed over too soon, reprint a receipt.
class _OrdersPanel extends StatefulWidget {
  const _OrdersPanel();

  @override
  State<_OrdersPanel> createState() => _OrdersPanelState();
}

class _OrdersPanelState extends State<_OrdersPanel> {
  List<CounterOrder>? _orders;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    try {
      final o = await QuickServeApi.orders();
      if (mounted) setState(() => _orders = o);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _set(CounterOrder o, String status) async {
    try {
      await QuickServeApi.setStatus(o.checkId, status);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
    _reload();
  }

  Future<void> _reprint(CounterOrder o) async {
    try {
      final text = await Api.reprintReceipt(o.checkId);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ReceiptScreen(checkId: o.checkId, text: text),
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = Q.of(context);
    final orders = _orders;
    final size = MediaQuery.sizeOf(context);
    return Dialog(
      child: SizedBox(
        width: (size.width * .8).clamp(320, 820),
        height: size.height * .8,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      q.orders,
                      style: T.text(size: 22, weight: FontWeight.w700),
                    ),
                  ),
                  IconButton(
                    tooltip: MaterialLocalizations.of(
                      context,
                    ).closeButtonTooltip,
                    icon: const Icon(LucideIcons.x),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: orders == null
                  ? (_error != null
                        ? Center(child: Text('$_error'))
                        : const DelayedSpinner())
                  : orders.isEmpty
                  ? Center(
                      child: Text(
                        q.noOrders,
                        style: T.text(size: 18, color: T.textMuted),
                      ),
                    )
                  : ListView.separated(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      itemCount: orders.length,
                      separatorBuilder: (_, _) => const Divider(height: 1),
                      itemBuilder: (_, i) => _row(q, orders[i]),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(Q q, CounterOrder o) {
    final ready = o.status == 'READY';
    return Padding(
      key: Key('order-${o.orderNumber}'),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Row(
        children: [
          SizedBox(
            width: 96,
            child: Text(
              o.label,
              style: T.text(size: 28, weight: FontWeight.w800),
            ),
          ),
          Icon(
            o.takeOut ? LucideIcons.shoppingBag : LucideIcons.utensils,
            size: 20,
            color: T.navy,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  q.status(o.status),
                  style: T.text(
                    size: 16,
                    weight: FontWeight.w700,
                    color: ready ? const Color(0xFF1E7A4C) : T.textPrimary,
                  ),
                ),
                Text(
                  '${o.takeOut ? q.takeOut : q.dineIn} · ${q.items(o.itemCount)} · ${money(o.totalCents)}',
                  style: T.small(color: T.textMuted),
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          IconButton(
            key: Key('reprint-${o.orderNumber}'),
            tooltip: q.reprint,
            icon: const Icon(LucideIcons.printer),
            onPressed: () => _reprint(o),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 176,
            height: T.minTouch,
            child: switch (o.status) {
              'PREPARING' => OutlinedButton(
                key: Key('ready-${o.orderNumber}'),
                onPressed: () => _set(o, 'READY'),
                child: Text(q.markReady),
              ),
              'READY' => FilledButton(
                key: Key('pickedup-${o.orderNumber}'),
                onPressed: () => _set(o, 'PICKED_UP'),
                child: Text(q.markPickedUp),
              ),
              _ => TextButton.icon(
                key: Key('recall-${o.orderNumber}'),
                icon: const Icon(LucideIcons.undo2, size: 18),
                onPressed: () => _set(o, 'READY'),
                label: Text(q.recall),
              ),
            },
          ),
        ],
      ),
    );
  }
}

/// Settings (quick-serve only): whether a new counter order starts as take
/// out or dine in, and whether a kiosk order prints the guest's ticket.
/// Saved as soon as it is changed.
class CounterModeSetting extends StatefulWidget {
  const CounterModeSetting({super.key});

  @override
  State<CounterModeSetting> createState() => _CounterModeSettingState();
}

class _CounterModeSettingState extends State<CounterModeSetting> {
  String? _mode;
  bool? _ticket;

  @override
  void initState() {
    super.initState();
    QuickServeApi.defaultMode().then((m) {
      if (mounted) setState(() => _mode = m);
    }, onError: (_) {});
    QuickServeApi.kioskTicket().then((on) {
      if (mounted) setState(() => _ticket = on);
    }, onError: (_) {});
  }

  Future<void> _saveTicket(bool on) async {
    final before = _ticket;
    setState(() => _ticket = on);
    try {
      await QuickServeApi.setKioskTicket(on);
    } catch (e) {
      if (!mounted) return;
      setState(() => _ticket = before);
      showApiError(context, e);
    }
  }

  Future<void> _save(String m) async {
    final before = _mode;
    setState(() => _mode = m);
    try {
      await QuickServeApi.setDefaultMode(m);
    } catch (e) {
      if (!mounted) return;
      setState(() => _mode = before);
      showApiError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = Q.of(context);
    final mode = _mode;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(q.defaultMode, style: T.text(weight: FontWeight.w600)),
          const SizedBox(height: 8),
          if (mode != null)
            SegmentedButton<String>(
              key: const Key('default-mode'),
              segments: [
                ButtonSegment(
                  value: 'TAKE_OUT',
                  icon: const Icon(LucideIcons.shoppingBag),
                  label: Text(q.takeOut),
                ),
                ButtonSegment(
                  value: 'DINE_IN',
                  icon: const Icon(LucideIcons.utensils),
                  label: Text(q.dineIn),
                ),
              ],
              selected: {mode},
              onSelectionChanged: (s) => _save(s.first),
            ),
          if (_ticket != null)
            SwitchListTile(
              key: const Key('kiosk-ticket'),
              contentPadding: EdgeInsets.zero,
              title: Text(q.kioskTicket),
              subtitle: Text(q.kioskTicketHelp),
              value: _ticket!,
              onChanged: _saveTicket,
            ),
        ],
      ),
    );
  }
}
