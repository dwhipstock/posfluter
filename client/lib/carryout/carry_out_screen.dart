import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../quickserve/quick_serve_i18n.dart';
import '../screens/check_screen.dart';
import '../widgets/resume_refresh.dart';
import 'carry_out_i18n.dart';

/// The headline a carry-out order goes by: "Order #105 · Carry-out".
String carryOutHeadline(BuildContext context, CounterOrder o) =>
    C.of(context).headline(o.orderNumber ?? 0);

/// Open [o] on the normal check screen. Back with nothing on it (opened,
/// then not wanted): the empty order is dropped, so it never sits in the list.
Future<void> openCarryOutOrder(BuildContext context, CounterOrder o) async {
  await Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => CheckScreen(
        checkId: o.checkId,
        tableLabel: carryOutHeadline(context, o),
        carryOut: true,
        orderNumber: o.orderNumber,
        panelTop: CarryOutCustomerBar(order: o),
      ),
    ),
  );
  try {
    final c = await Api.getCheck(o.checkId);
    if (c.status == 'OPEN' && c.lines.isEmpty && c.pendingLines.isEmpty) {
      await CarryOutApi.discard(o.checkId);
    }
  } catch (_) {} // best effort: an empty order is harmless
}

/// "New carry-out order": the call-in's name and phone (both optional),
/// then the order opens on the check screen, already numbered.
Future<void> newCarryOutOrder(BuildContext context) async {
  final c = C.of(context);
  final l = L.of(context);
  final name = TextEditingController();
  final phone = TextEditingController();
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(c.newOrder),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              key: const Key('carryout-name'),
              controller: name,
              autofocus: true,
              maxLength: 60,
              textCapitalization: TextCapitalization.words,
              decoration: InputDecoration(labelText: c.customerName),
            ),
            TextField(
              key: const Key('carryout-phone'),
              controller: phone,
              maxLength: 32,
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(labelText: c.phone),
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
          key: const Key('carryout-start'),
          onPressed: () => Navigator.pop(ctx, true),
          child: Text(c.start),
        ),
      ],
    ),
  );
  if (ok != true || !context.mounted) return;
  try {
    final o = await CarryOutApi.create(
      name: name.text.trim().isEmpty ? null : name.text.trim(),
      phone: phone.text.trim().isEmpty ? null : phone.text.trim(),
    );
    if (context.mounted) await openCarryOutOrder(context, o);
  } catch (e) {
    if (context.mounted) showApiError(context, e);
  }
}

/// The carry-out orders not picked up yet: number, customer, total, paid or
/// pay at pickup, how long ago; Mark ready / Picked up. Tap one to open it.
class CarryOutScreen extends StatefulWidget {
  const CarryOutScreen({super.key});

  @override
  State<CarryOutScreen> createState() => _CarryOutScreenState();
}

class _CarryOutScreenState extends State<CarryOutScreen> with ResumeRefresh {
  List<CounterOrder>? _orders;
  Object? _error;
  Timer? _poll;

  @override
  void onAppResume() => _reload();

  @override
  void initState() {
    super.initState();
    _reload();
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _reload());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _reload() async {
    try {
      final o = await CarryOutApi.orders();
      if (mounted) {
        setState(() {
          _orders = o;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted && _orders == null) setState(() => _error = e);
    }
  }

  Future<void> _set(CounterOrder o, String status) async {
    if (status == 'PICKED_UP' && !o.paid) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(C.of(context).payFirst)));
      return;
    }
    try {
      await CarryOutApi.setStatus(o.checkId, status);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
    _reload();
  }

  Future<void> _open(CounterOrder o) async {
    await openCarryOutOrder(context, o);
    _reload();
  }

  Future<void> _new() async {
    await newCarryOutOrder(context);
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    final c = C.of(context);
    final orders = _orders;
    return Scaffold(
      appBar: AppBar(
        backgroundColor: T.navy,
        foregroundColor: T.onPrimary,
        // the theme's title is navy (for the white bars): white on this one
        titleTextStyle: T.headline(color: T.onPrimary),
        title: Text(c.carryOut, key: const Key('carryout-title')),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: FilledButton.icon(
              key: const Key('carryout-new'),
              style: FilledButton.styleFrom(
                backgroundColor: T.accent,
                foregroundColor: T.onAccent,
                minimumSize: const Size(0, T.minTouch),
              ),
              icon: const Icon(LucideIcons.plus),
              label: Text(c.newOrder),
              onPressed: _new,
            ),
          ),
        ],
      ),
      body: orders == null
          ? (_error != null
                ? Center(child: Text('$_error'))
                : const DelayedSpinner())
          : orders.isEmpty
          ? Center(
              child: Text(
                c.noOrders,
                style: T.text(size: 18, color: T.textMuted),
              ),
            )
          : ListView.separated(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: orders.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (_, i) => _row(orders[i]),
            ),
    );
  }

  Widget _row(CounterOrder o) {
    final c = C.of(context);
    final q = Q.of(context);
    final l = L.of(context);
    final at = DateTime.tryParse(o.createdAt ?? '');
    final ago = at == null
        ? null
        : l.openFor(
            DateTime.now().difference(at).isNegative
                ? Duration.zero
                : DateTime.now().difference(at),
          );
    final who = [
      o.customerName,
      o.customerPhone,
    ].whereType<String>().join(' · ');
    final status = o.status == 'OPEN' ? c.notSent : q.status(o.status);
    return InkWell(
      key: Key('carryout-${o.orderNumber}'),
      onTap: () => _open(o),
      child: Padding(
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
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    who.isEmpty ? status : '$who · $status',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: T.text(size: 16, weight: FontWeight.w700),
                  ),
                  Text(
                    [
                      q.items(o.itemCount),
                      money(o.totalCents),
                      ?ago,
                    ].join(' · '),
                    style: T.small(color: T.textMuted),
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Pill(
              o.paid ? c.paid : c.payAtPickup,
              color: o.paid ? const Color(0xFF1E7A4C) : T.attention,
            ),
            const SizedBox(width: 12),
            SizedBox(
              width: 176,
              height: T.minTouch,
              child: o.status == 'READY'
                  ? FilledButton(
                      key: Key('pickedup-${o.orderNumber}'),
                      onPressed: () => _set(o, 'PICKED_UP'),
                      child: Text(q.markPickedUp),
                    )
                  : OutlinedButton(
                      key: Key('ready-${o.orderNumber}'),
                      onPressed: () => _set(o, 'READY'),
                      child: Text(q.markReady),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Settings (a table-service store with carry-out): the header button, for
/// a floor with no Carry-out spot. Hidden when the store has no carry-out.
/// Saved as soon as it is changed.
class CarryOutHeaderSetting extends StatefulWidget {
  const CarryOutHeaderSetting({super.key});

  @override
  State<CarryOutHeaderSetting> createState() => _CarryOutHeaderSettingState();
}

class _CarryOutHeaderSettingState extends State<CarryOutHeaderSetting> {
  bool? _on;

  @override
  void initState() {
    super.initState();
    CarryOutApi.headerButton().then(
      (v) {
        if (mounted) setState(() => _on = v);
      },
      onError: (_) {}, // no carry-out here: nothing shown
    );
  }

  Future<void> _set(bool v) async {
    setState(() => _on = v);
    try {
      final saved = await CarryOutApi.setHeaderButton(v);
      if (mounted) setState(() => _on = saved);
    } catch (e) {
      if (mounted) {
        setState(() => _on = !v);
        showApiError(context, e);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final on = _on;
    if (on == null) return const SizedBox.shrink();
    final c = C.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: SwitchListTile(
        key: const Key('carryout-header-setting'),
        contentPadding: EdgeInsets.zero,
        value: on,
        onChanged: _set,
        title: Text(c.headerSetting),
        subtitle: Text(c.headerSettingHelp, style: T.small()),
      ),
    );
  }
}

/// On the check screen, under the header: the order's customer, tap to change.
class CarryOutCustomerBar extends StatefulWidget {
  final CounterOrder order;
  const CarryOutCustomerBar({super.key, required this.order});

  @override
  State<CarryOutCustomerBar> createState() => _CarryOutCustomerBarState();
}

class _CarryOutCustomerBarState extends State<CarryOutCustomerBar> {
  late String? _name = widget.order.customerName;
  late String? _phone = widget.order.customerPhone;

  Future<void> _edit() async {
    final c = C.of(context);
    final l = L.of(context);
    final name = TextEditingController(text: _name ?? '');
    final phone = TextEditingController(text: _phone ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(c.customer),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: name,
                maxLength: 60,
                textCapitalization: TextCapitalization.words,
                decoration: InputDecoration(labelText: c.customerName),
              ),
              TextField(
                controller: phone,
                maxLength: 32,
                keyboardType: TextInputType.phone,
                decoration: InputDecoration(labelText: c.phone),
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
            child: Text(c.save),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final o = await CarryOutApi.setCustomer(
        widget.order.checkId,
        name: name.text.trim(),
        phone: phone.text.trim(),
      );
      if (mounted) {
        setState(() {
          _name = o.customerName;
          _phone = o.customerPhone;
        });
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = C.of(context);
    final who = [_name, _phone].whereType<String>().join(' · ');
    return Material(
      color: T.surfaceAlt,
      child: InkWell(
        key: const Key('carryout-customer'),
        onTap: _edit,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
          child: Row(
            children: [
              const Icon(LucideIcons.user, size: 18, color: T.textPrimary),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  who.isEmpty ? c.customer : who,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: T.text(
                    size: 15,
                    weight: FontWeight.w600,
                    color: who.isEmpty ? T.textMuted : T.textPrimary,
                  ),
                ),
              ),
              const Icon(LucideIcons.pencil, size: 16, color: T.textMuted),
            ],
          ),
        ),
      ),
    );
  }
}

/// A Carry-out spot on the floor (service mode): a tile the size of the
/// object, bag icon, "Carry-out" and how many orders are open. Tap = the
/// carry-out list. It is no table: the room's occupied count ignores it.
class CarryOutSpot extends StatelessWidget {
  final FloorObject object;
  final double scale;
  final int openCount;
  final VoidCallback onTap;
  const CarryOutSpot({
    super.key,
    required this.object,
    required this.scale,
    required this.openCount,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final c = C.of(context);
    final l = L.of(context);
    final w = object.width * scale;
    final h = object.height * scale;
    final label = l.name(
      object.labelFr ?? object.labelEn ?? '',
      object.labelEn ?? object.labelFr ?? '',
      object.names,
    );
    final caption = label.isEmpty ? c.carryOut : label;
    final size = (math.min(w, h) * .22).clamp(10.0, 18.0);
    final busy = openCount > 0;
    final content = Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.shopping_bag,
              size: size * 1.3,
              color: busy ? T.onAccent : T.navy,
            ),
            const SizedBox(width: 6),
            Text(
              caption,
              maxLines: 1,
              style: T.text(
                size: size,
                weight: FontWeight.w700,
                color: busy ? T.onAccent : T.textPrimary,
              ),
            ),
          ],
        ),
        if (h > 40)
          Text(
            c.openCount(openCount),
            key: const Key('carryout-spot-count'),
            style: T.text(
              size: size * .85,
              weight: FontWeight.w600,
              color: busy ? T.onAccent : T.textMuted,
            ),
          ),
      ],
    );
    return Semantics(
      button: true,
      label: '${c.carryOut}, ${c.openCount(openCount)}',
      child: Material(
        color: busy ? T.accent : T.surface,
        shape: RoundedRectangleBorder(
          borderRadius: T.radiusMedium,
          side: BorderSide(color: busy ? T.accent : T.navy, width: 2),
        ),
        elevation: busy ? 2 : 0,
        child: InkWell(
          key: Key('carryout-spot-${object.id}'),
          borderRadius: T.radiusMedium,
          onTap: onTap,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: object.rotation == 0
                    ? content
                    : Transform.rotate(
                        angle: -object.rotation * math.pi / 180,
                        child: content,
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
