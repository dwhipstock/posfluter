import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../kitchen/kitchen_banner.dart';
import '../kitchen/kitchen_i18n.dart';
import '../kitchen/kitchen_screen.dart';
import '../screens/check_screen.dart';
import '../screens/login_screen.dart';
import '../screens/sales_screen.dart';
import '../screens/settings_screen.dart';
import '../screens/shift_screen.dart';
import '../widgets/resume_refresh.dart';
import 'quick_serve_i18n.dart';

/// Where a quick-serve counter (Copper Lantern Express) lands: no floor plan,
/// today's numbered orders instead. Start a new one (dine in / take out), open
/// one to ring and take payment, and move it along the pickup board
/// (preparing -> ready -> picked up). Kiosk orders arrive here on their own,
/// unpaid and already sent to the kitchen.
class CounterScreen extends StatefulWidget {
  const CounterScreen({super.key});

  @override
  State<CounterScreen> createState() => _CounterScreenState();
}

class _CounterScreenState extends State<CounterScreen> with ResumeRefresh {
  List<CounterOrder>? _orders;
  Object? _error;
  Timer? _poll;
  bool _busy = false;

  @override
  void onAppResume() => _reload();

  @override
  void initState() {
    super.initState();
    _reload();
    // kiosk orders and kitchen bumps arrive on their own
    _poll = Timer.periodic(const Duration(seconds: 4), (_) => _reload());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _reload() async {
    try {
      final orders = await QuickServeApi.orders();
      if (mounted) {
        setState(() {
          _orders = orders;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted && _orders == null) setState(() => _error = e);
    }
  }

  Future<void> _new(String mode) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final o = await QuickServeApi.create(mode);
      if (!mounted) return;
      await _open(o);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _open(CounterOrder o) async {
    final q = Q.of(context);
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => CheckScreen(
          checkId: o.checkId,
          tableLabel: '#${o.orderNumber} · ${o.takeOut ? q.takeOut : q.dineIn}',
          counterOrder: true,
        ),
      ),
    );
    // back from ringing: a new order goes to the kitchen and the board
    if (o.status == 'NEW') {
      try {
        await QuickServeApi.place(o.checkId);
      } catch (_) {} // an emptied (cancelled) order has nothing to place
    }
    _reload();
  }

  Future<void> _setStatus(CounterOrder o, String status) async {
    try {
      await QuickServeApi.setStatus(o.checkId, status);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
    _reload();
  }

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
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(q.pickupBoard),
        content: SelectableText(q.pickupBoardBody('$base/pickup')),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(MaterialLocalizations.of(context).closeButtonLabel),
          ),
        ],
      ),
    );
  }

  Widget _action(IconData icon, String label, VoidCallback onPressed) =>
      TextButton.icon(
        style: TextButton.styleFrom(foregroundColor: T.onPrimary),
        onPressed: onPressed,
        icon: Icon(icon, size: 20),
        label: Text(label),
      );

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final q = Q.of(context);
    final isManager = Api.currentUser?.isManager ?? false;
    final orders = _orders;
    return Scaffold(
      appBar: AppBar(
        toolbarHeight: 72,
        backgroundColor: T.navy,
        foregroundColor: T.onPrimary,
        shape: const Border(),
        title: Text(
          Api.venueName ?? q.orders,
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        actions: [
          const LangActions(color: T.onPrimary),
          if (KitchenApi.enabled)
            _action(
              LucideIcons.chefHat,
              K.of(context).kitchen,
              () => Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const KitchenScreen())),
            ),
          _action(LucideIcons.monitor, q.pickupBoard, _pickupBoard),
          _action(
            LucideIcons.barChart3,
            l.navReports,
            () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const ShiftScreen())),
          ),
          _action(
            LucideIcons.receiptText,
            l.navRefunds,
            () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const SalesScreen())),
          ),
          if (isManager) ...[
            _action(LucideIcons.tabletSmartphone, q.pairKiosk, _pairKiosk),
            IconButton(
              tooltip: l.settings,
              icon: const Icon(LucideIcons.slidersHorizontal),
              onPressed: () => Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const SettingsScreen())),
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
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          const KitchenQueueBanner(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    key: const Key('new-dine-in'),
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(64),
                      textStyle: const TextStyle(fontSize: 20),
                    ),
                    onPressed: _busy ? null : () => _new('DINE_IN'),
                    icon: const Icon(LucideIcons.utensils),
                    label: Text(q.newDineIn),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: FilledButton.icon(
                    key: const Key('new-take-out'),
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(64),
                      textStyle: const TextStyle(fontSize: 20),
                    ),
                    onPressed: _busy ? null : () => _new('TAKE_OUT'),
                    icon: const Icon(LucideIcons.shoppingBag),
                    label: Text(q.newTakeOut),
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: orders == null
                ? (_error != null
                      ? Center(child: Text('$_error'))
                      : const DelayedSpinner())
                : orders.isEmpty
                ? Center(
                    child: Text(
                      q.noOrders,
                      style: const TextStyle(fontSize: 20, color: T.textMuted),
                    ),
                  )
                : GridView.extent(
                    padding: const EdgeInsets.all(16),
                    maxCrossAxisExtent: 320,
                    mainAxisSpacing: 12,
                    crossAxisSpacing: 12,
                    childAspectRatio: 1.25,
                    children: [
                      for (final o in orders)
                        _OrderCard(
                          order: o,
                          onOpen: () => _open(o),
                          onStatus: (s) => _setStatus(o, s),
                        ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}

class _OrderCard extends StatelessWidget {
  final CounterOrder order;
  final VoidCallback onOpen;
  final void Function(String status) onStatus;
  const _OrderCard({
    required this.order,
    required this.onOpen,
    required this.onStatus,
  });

  @override
  Widget build(BuildContext context) {
    final q = Q.of(context);
    final o = order;
    final ready = o.status == 'READY';
    return Card(
      clipBehavior: Clip.antiAlias,
      color: ready ? const Color(0xFFE3F4EA) : null,
      child: InkWell(
        key: Key('order-${o.orderNumber}'),
        onTap: onOpen,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(
                    '#${o.orderNumber}',
                    style: const TextStyle(
                      fontSize: 34,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const Spacer(),
                  if (o.fromKiosk)
                    Tooltip(
                      message: q.fromKiosk,
                      child: const Icon(LucideIcons.tabletSmartphone),
                    ),
                ],
              ),
              Text(
                '${o.takeOut ? q.takeOut : q.dineIn} · ${q.items(o.itemCount)}',
              ),
              const SizedBox(height: 4),
              Text(
                q.status(o.status),
                style: TextStyle(
                  fontWeight: FontWeight.w700,
                  color: ready ? const Color(0xFF1E7A4C) : T.textMuted,
                ),
              ),
              Text(
                o.paid ? q.paid : '${q.toPay} · ${money(o.outstandingCents)}',
                style: TextStyle(
                  fontWeight: FontWeight.w600,
                  color: o.paid ? const Color(0xFF1E7A4C) : T.destructive,
                ),
              ),
              if (o.hasAlcohol && !o.paid)
                Text(q.idCheck, style: const TextStyle(fontSize: 12)),
              const Spacer(),
              if (o.status == 'PREPARING' || o.status == 'NEW')
                OutlinedButton(
                  onPressed: o.itemCount == 0 ? null : () => onStatus('READY'),
                  child: Text(q.markReady),
                )
              else if (ready)
                FilledButton(
                  onPressed: () => onStatus('PICKED_UP'),
                  child: Text(q.markPickedUp),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
