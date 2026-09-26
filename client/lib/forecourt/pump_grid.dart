import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api.dart';
import '../design/skin.dart';
import '../retail/sp_theme.dart';
import 'forecourt_i18n.dart';

/// Pump state colours: one hue per state, bright on the dark counter, each
/// ≥ 3:1 against the tile (non-text contrast) and paired with black text.
class PumpColors {
  PumpColors._();
  static const idle = Color(0xFF5B6470);
  static const calling = Color(0xFFFF8A00);
  static const authorised = Color(0xFF2F7BFF);
  static const fuelling = Color(0xFF22C55E);
  static const payable = Color(0xFFFFC400);
  static const suspended = Color(0xFFFFB020);
  static const stopped = Color(0xFFFF4D4F);
  static const offline = Color(0xFF3A4048);

  /// Text on a state colour.
  static Color on(Color c) => c == authorised || c == offline || c == idle
      ? Colors.white
      : Colors.black;
}

/// Polls the store's forecourt view twice a second while the counter is on
/// screen. A failed poll (the store itself unreachable) shows every pump
/// offline; the next poll is the retry.
class ForecourtController extends ChangeNotifier {
  ForecourtController({
    this.interval = const Duration(milliseconds: 500),
    this.pumpCount = 8,
    Future<Forecourt> Function()? fetch,
  }) : _fetch = fetch ?? ForecourtApi.state;

  final Duration interval;
  final int pumpCount;
  final Future<Forecourt> Function() _fetch;
  Timer? _timer;
  bool _inFlight = false;
  bool _disposed = false;

  Forecourt state = const Forecourt(online: false);
  bool loaded = false;

  void start() {
    _timer ??= Timer.periodic(interval, (_) => refresh());
    refresh();
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
  }

  Future<void> refresh() async {
    if (_inFlight || _disposed) return;
    _inFlight = true;
    try {
      set(await _fetch());
    } catch (e) {
      set(
        Forecourt.offline(
          state.pumps.isEmpty ? pumpCount : state.pumps.length,
          message: '$e',
        ),
      );
    } finally {
      _inFlight = false;
    }
  }

  /// A command's answer is the fresh view: show it at once.
  void set(Forecourt f) {
    if (_disposed) return;
    state = f;
    loaded = true;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    stop();
    super.dispose();
  }
}

/// What a tile says and in which colour: the most urgent thing first.
class PumpLook {
  final Color color;
  final String label;
  final bool blink;
  const PumpLook(this.color, this.label, {this.blink = false});

  static PumpLook of(PumpInfo p, F f) => switch (p) {
    _ when p.offline => PumpLook(PumpColors.offline, f.state('OFFLINE')),
    _ when p.stopped => PumpLook(
      PumpColors.stopped,
      f.state('EMERGENCY_STOP'),
      blink: true,
    ),
    _ when p.state == 'ERROR' => PumpLook(PumpColors.stopped, f.state('ERROR')),
    _ when p.state == 'FUELLING' => PumpLook(
      PumpColors.fuelling,
      f.state('FUELLING'),
    ),
    _ when p.state == 'SUSPENDED' => PumpLook(
      PumpColors.suspended,
      f.state('SUSPENDED'),
    ),
    _ when p.unpaid.isNotEmpty => PumpLook(
      PumpColors.payable,
      f.payNow,
      blink: true,
    ),
    _ when p.state == 'CALLING' => PumpLook(
      PumpColors.calling,
      f.state('CALLING'),
      blink: true,
    ),
    _ when p.state == 'AUTHORISED' => PumpLook(
      PumpColors.authorised,
      f.state('AUTHORISED'),
    ),
    _ => PumpLook(PumpColors.idle, f.state('IDLE')),
  };
}

/// The forecourt strip on the counter: one tile per pump, live.
class PumpGrid extends StatelessWidget {
  final ForecourtController controller;
  final void Function(PumpInfo pump) onTap;
  final VoidCallback onStopAll;
  const PumpGrid({
    super.key,
    required this.controller,
    required this.onTap,
    required this.onStopAll,
  });

  @override
  Widget build(BuildContext context) {
    final f = F.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final fc = controller.state;
        final pumps = fc.pumps.isEmpty
            ? [
                for (var i = 1; i <= controller.pumpCount; i++)
                  PumpInfo.offline(i),
              ]
            : fc.pumps;
        final offline = controller.loaded && !fc.online;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              height: 34,
              child: Row(
                children: [
                  Text(
                    f.pumps.toUpperCase(),
                    style: s
                        .text(
                          size: 15,
                          weight: FontWeight.w800,
                          color: c.textMuted,
                        )
                        .copyWith(letterSpacing: 1.6),
                  ),
                  const SizedBox(width: 12),
                  if (offline)
                    Flexible(
                      child: Container(
                        key: const Key('forecourt-offline'),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 4,
                        ),
                        decoration: BoxDecoration(
                          color: c.warnSoft,
                          borderRadius: s.radiusSmall,
                        ),
                        child: Text(
                          f.forecourtOffline,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: s.text(
                            size: 13.5,
                            weight: FontWeight.w700,
                            color: c.warn,
                          ),
                        ),
                      ),
                    ),
                  const Spacer(),
                  SizedBox(
                    height: 32,
                    child: FilledButton.icon(
                      key: const Key('stop-all'),
                      style: FilledButton.styleFrom(
                        backgroundColor: PumpColors.stopped,
                        foregroundColor: Colors.black,
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        minimumSize: const Size(0, 32),
                        shape: RoundedRectangleBorder(
                          borderRadius: s.radiusSmall,
                        ),
                      ),
                      onPressed: offline ? null : onStopAll,
                      icon: const Icon(Icons.pan_tool_rounded, size: 16),
                      label: Text(
                        f.stopAll,
                        style: s
                            .text(
                              size: 13.5,
                              weight: FontWeight.w800,
                              color: Colors.black,
                            )
                            .copyWith(letterSpacing: 1),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            LayoutBuilder(
              builder: (context, box) {
                const gap = 8.0;
                final cols = pumps.length <= 4
                    ? pumps.length
                    : (pumps.length / 2).ceil();
                final w = (box.maxWidth - gap * (cols - 1)) / cols;
                return Wrap(
                  spacing: gap,
                  runSpacing: gap,
                  children: [
                    for (final p in pumps)
                      SizedBox(
                        width: w,
                        height: 128,
                        child: PumpTile(
                          key: Key('pump-${p.pump}'),
                          pump: p,
                          onTap: () => onTap(p),
                        ),
                      ),
                  ],
                );
              },
            ),
          ],
        );
      },
    );
  }
}

/// One pump: its number on the state colour, what it's doing, the dollars
/// ticking, gallons and grade under them, and a prepay's progress.
class PumpTile extends StatefulWidget {
  final PumpInfo pump;
  final VoidCallback onTap;
  const PumpTile({super.key, required this.pump, required this.onTap});

  @override
  State<PumpTile> createState() => _PumpTileState();
}

class _PumpTileState extends State<PumpTile>
    with SingleTickerProviderStateMixin {
  late final AnimationController _blink = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 700),
  );

  @override
  void dispose() {
    _blink.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final f = F.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final p = widget.pump;
    final look = PumpLook.of(p, f);
    if (look.blink && !_blink.isAnimating) {
      _blink.repeat(reverse: true);
    } else if (!look.blink && _blink.isAnimating) {
      _blink.stop();
      _blink.value = 0;
    }
    final unpaid = p.unpaid;
    // the big figure: what's pumping, what's owed, or the last sale
    final int amount;
    final int volume;
    final String? grade;
    final int price;
    if (!p.live && unpaid.isNotEmpty) {
      amount = unpaid.first.amountCents;
      volume = unpaid.first.volumeMilli;
      grade = unpaid.first.grade;
      price = unpaid.first.priceMills;
    } else {
      amount = p.amountCents;
      volume = p.volumeMilli;
      grade = p.grade;
      price = p.priceMills;
    }
    final dim = p.offline || (!p.live && unpaid.isEmpty && p.state == 'IDLE');
    final limit = p.limitCents ?? p.prepay?.prepaidCents;
    final prepayNote = p.prepay == null
        ? null
        : switch (p.prepay!.status) {
            'IN_BASKET' => f.prepayOnSale,
            'AUTH_FAILED' => f.waitingForPump,
            _ => f.prepaid(money(p.prepay!.prepaidCents)),
          };
    final sub = p.offline
        ? ''
        : prepayNote ??
              (p.mode == 'POSTPAY' && (p.state == 'AUTHORISED' || p.live)
                  ? f.postpay
                  : (p.state == 'CALLING' && p.nozzleUp != null
                        ? f.nozzleUp
                        : ''));
    return Material(
      color: c.surface,
      shape: RoundedRectangleBorder(
        borderRadius: s.tileRadius,
        side: BorderSide(
          color: p.offline ? c.border : look.color.withValues(alpha: .9),
          width: p.offline ? 1 : 2,
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: widget.onTap,
        child: Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      AnimatedBuilder(
                        animation: _blink,
                        builder: (_, child) => Opacity(
                          opacity: 1 - _blink.value * .55,
                          child: child,
                        ),
                        child: Container(
                          width: 38,
                          height: 38,
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: look.color,
                            borderRadius: s.radiusMedium,
                          ),
                          child: Text(
                            '${p.pump}',
                            style: TextStyle(
                              fontFamily: 'BarlowCondensed',
                              fontSize: 28,
                              height: 1,
                              fontWeight: FontWeight.w800,
                              color: PumpColors.on(look.color),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              look.label,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: s
                                  .text(
                                    size: 14,
                                    weight: FontWeight.w800,
                                    color: p.offline ? c.textMuted : look.color,
                                  )
                                  .copyWith(letterSpacing: .8),
                            ),
                            Text(
                              sub,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: s.text(
                                size: 12,
                                weight: FontWeight.w600,
                                color: c.textMuted,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const Spacer(),
                  if (!p.offline)
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: Text(
                        money(amount),
                        style: TextStyle(
                          fontFamily: 'BarlowCondensed',
                          fontSize: 34,
                          height: 1,
                          fontWeight: FontWeight.w800,
                          fontFeatures: const [FontFeature.tabularFigures()],
                          color: dim ? c.textMuted : c.text,
                        ),
                      ),
                    ),
                  const SizedBox(height: 2),
                  Text(
                    p.offline
                        ? ''
                        : [
                            f.gal(gallons(volume)),
                            if (grade != null)
                              f.grade(grade, p.gradeName ?? grade),
                            if (price > 0) pricePerGallon(price),
                          ].join(' · '),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: s.figures(
                      size: 12.5,
                      weight: FontWeight.w600,
                      color: c.textMuted,
                    ),
                  ),
                  if (limit != null &&
                      limit > 0 &&
                      (p.live || p.state == 'AUTHORISED')) ...[
                    const SizedBox(height: 5),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(3),
                      child: LinearProgressIndicator(
                        value: (p.amountCents / limit).clamp(0.0, 1.0),
                        minHeight: 5,
                        backgroundColor: c.surfaceAlt,
                        color: look.color,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (unpaid.length > 1)
              Positioned(
                right: 8,
                top: 8,
                child: _badge(
                  context,
                  f.more(unpaid.length - 1),
                  PumpColors.payable,
                ),
              ),
            if (p.change != null)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: Container(
                  key: Key('change-${p.pump}'),
                  color: PumpColors.payable,
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  alignment: Alignment.center,
                  child: Text(
                    f.change(money(p.change!.refundCents)),
                    style: s
                        .text(
                          size: 13.5,
                          weight: FontWeight.w800,
                          color: Colors.black,
                        )
                        .copyWith(letterSpacing: .8),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _badge(BuildContext context, String text, Color color) {
    final s = BrandSkin.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(color: color, borderRadius: s.radiusSmall),
      child: Text(
        text,
        style: s.text(size: 11, weight: FontWeight.w800, color: Colors.black),
      ),
    );
  }
}

/// What the cashier can do with a pump, as a sheet: the actions that make
/// sense for its state, the most likely first.
class PumpSheet extends StatelessWidget {
  final PumpInfo pump;
  final bool online;
  final Future<void> Function(PayableFuel trx) onAddFuel;
  final Future<void> Function() onPrepay;
  final Future<void> Function(Future<Forecourt> Function() command) onCommand;

  const PumpSheet({
    super.key,
    required this.pump,
    required this.online,
    required this.onAddFuel,
    required this.onPrepay,
    required this.onCommand,
  });

  static Future<void> show(
    BuildContext context, {
    required PumpInfo pump,
    required bool online,
    required Future<void> Function(PayableFuel trx) onAddFuel,
    required Future<void> Function() onPrepay,
    required Future<void> Function(Future<Forecourt> Function() command)
    onCommand,
  }) => showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    backgroundColor: SpColors.of(context).surface,
    builder: (_) => PumpSheet(
      pump: pump,
      online: online,
      onAddFuel: onAddFuel,
      onPrepay: onPrepay,
      onCommand: onCommand,
    ),
  );

  @override
  Widget build(BuildContext context) {
    final f = F.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final p = pump;
    final look = PumpLook.of(p, f);
    final n = p.pump;
    void close() => Navigator.of(context).pop();

    Widget action(
      String key,
      String label,
      IconData icon,
      Future<void> Function() run, {
      Color? color,
      bool primary = false,
    }) => Padding(
      padding: const EdgeInsets.only(top: 10),
      child: SizedBox(
        height: 60,
        child: primary
            ? FilledButton.icon(
                key: Key(key),
                style: FilledButton.styleFrom(
                  backgroundColor: color ?? c.sage,
                  foregroundColor: PumpColors.on(color ?? c.sage),
                  shape: RoundedRectangleBorder(borderRadius: s.radiusMedium),
                ),
                onPressed: () async {
                  close();
                  await run();
                },
                icon: Icon(icon, size: 24),
                label: Text(
                  label,
                  style: s.text(
                    size: 19,
                    weight: FontWeight.w800,
                    color: PumpColors.on(color ?? c.sage),
                  ),
                ),
              )
            : OutlinedButton.icon(
                key: Key(key),
                style: OutlinedButton.styleFrom(
                  foregroundColor: color ?? c.text,
                  side: BorderSide(color: color ?? c.border, width: 1.5),
                  shape: RoundedRectangleBorder(borderRadius: s.radiusMedium),
                ),
                onPressed: () async {
                  close();
                  await run();
                },
                icon: Icon(icon, size: 22),
                label: Text(
                  label,
                  style: s.text(
                    size: 17,
                    weight: FontWeight.w700,
                    color: color ?? c.text,
                  ),
                ),
              ),
      ),
    );

    final actions = <Widget>[];
    if (!online || p.offline) {
      actions.add(
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: Text(
            f.notReachable,
            style: s.text(size: 16, color: c.warn, weight: FontWeight.w600),
          ),
        ),
      );
    } else {
      for (final t in p.payable) {
        final label =
            '${f.addToSale(money(t.amountCents))} · ${f.grade(t.grade, t.gradeName)} · ${gallons(t.volumeMilli)} gal';
        if (t.saleId != null) {
          actions.add(
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(
                '${f.onAnotherSale}: ${money(t.amountCents)} · ${f.grade(t.grade, t.gradeName)}',
                style: s.text(size: 15, color: c.textMuted),
              ),
            ),
          );
        } else {
          actions.add(
            action(
              'add-fuel-${t.trxId}',
              label,
              Icons.add_shopping_cart_rounded,
              () => onAddFuel(t),
              color: PumpColors.payable,
              primary: true,
            ),
          );
        }
      }
      if (p.change != null) {
        final ch = p.change!;
        actions.add(
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Text(
              f.changeNote(money(ch.dispensedCents), money(ch.prepaidCents)),
              style: s.text(size: 15, color: c.textMuted),
            ),
          ),
        );
        actions.add(
          action(
            'change-given',
            f.changeGiven(money(ch.refundCents)),
            Icons.payments_rounded,
            () => onCommand(() => ForecourtApi.changeGiven(ch.fuelSaleId)),
            color: PumpColors.payable,
            primary: true,
          ),
        );
      }
      final busy = p.live || p.state == 'SUSPENDED';
      final free =
          p.prepay == null &&
          !busy &&
          !p.outOfService &&
          p.state != 'AUTHORISED';
      if (free) {
        actions.add(
          action(
            'authorise',
            f.authorise,
            Icons.play_arrow_rounded,
            () => onCommand(() => ForecourtApi.authorise(n)),
            color: PumpColors.authorised,
            primary: p.state == 'CALLING',
          ),
        );
        actions.add(
          action(
            'prepay',
            f.prepay,
            Icons.attach_money_rounded,
            onPrepay,
            color: p.state == 'CALLING' ? null : c.sage,
            primary: p.state != 'CALLING',
          ),
        );
      }
      if (p.state == 'FUELLING') {
        actions.add(
          action(
            'stop-pump',
            f.stopPump,
            Icons.pause_rounded,
            () => onCommand(() => ForecourtApi.stop(n)),
            color: PumpColors.suspended,
          ),
        );
      }
      if (p.state == 'SUSPENDED') {
        actions.add(
          action(
            'resume-pump',
            f.resumePump,
            Icons.play_arrow_rounded,
            () => onCommand(() => ForecourtApi.resume(n)),
            color: PumpColors.fuelling,
            primary: true,
          ),
        );
      }
      if (p.outOfService && !p.offline) {
        actions.add(
          action(
            'reset-pump',
            f.resetPump,
            Icons.restart_alt_rounded,
            () => onCommand(() => ForecourtApi.reset(n)),
            primary: true,
          ),
        );
      }
      final pre = p.prepay;
      if (pre != null &&
          (pre.status == 'AUTHORISED' || pre.status == 'AUTH_FAILED') &&
          !p.live) {
        actions.add(
          action(
            'cancel-prepay',
            f.cancelPrepay(money(pre.prepaidCents)),
            Icons.undo_rounded,
            () => onCommand(() => ForecourtApi.cancelPrepay(pre.fuelSaleId)),
          ),
        );
      }
      if (!p.stopped) {
        actions.add(
          action(
            'estop-pump',
            f.emergencyStopPump,
            Icons.pan_tool_rounded,
            () => onCommand(() => ForecourtApi.emergencyStop(n)),
            color: PumpColors.stopped,
          ),
        );
      }
    }

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 52,
                  height: 52,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: look.color,
                    borderRadius: s.radiusMedium,
                  ),
                  child: Text(
                    '$n',
                    style: TextStyle(
                      fontFamily: 'BarlowCondensed',
                      fontSize: 38,
                      height: 1,
                      fontWeight: FontWeight.w800,
                      color: PumpColors.on(look.color),
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        f.pump(n),
                        style: s.text(
                          size: 24,
                          weight: FontWeight.w800,
                          color: c.text,
                        ),
                      ),
                      Text(
                        look.label,
                        style: s.text(
                          size: 15,
                          weight: FontWeight.w800,
                          color: look.color,
                        ),
                      ),
                    ],
                  ),
                ),
                if (p.live || p.amountCents > 0)
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        money(p.amountCents),
                        style: TextStyle(
                          fontFamily: 'BarlowCondensed',
                          fontSize: 34,
                          fontWeight: FontWeight.w800,
                          color: c.text,
                        ),
                      ),
                      Text(
                        '${gallons(p.volumeMilli)} gal',
                        style: s.figures(size: 14, color: c.textMuted),
                      ),
                    ],
                  ),
              ],
            ),
            ...actions,
          ],
        ),
      ),
    );
  }
}

/// "$X on pump N": quick amounts and a keypad-friendly field.
class PrepayDialog extends StatefulWidget {
  final int pump;
  const PrepayDialog({super.key, required this.pump});

  static Future<int?> show(BuildContext context, int pump) => showDialog<int>(
    context: context,
    builder: (_) => PrepayDialog(pump: pump),
  );

  @override
  State<PrepayDialog> createState() => _PrepayDialogState();
}

class _PrepayDialogState extends State<PrepayDialog> {
  static const _presets = [1000, 2000, 2500, 3000, 4000, 5000, 7500, 10000];
  final _ctl = TextEditingController();
  int? _cents;

  @override
  void dispose() {
    _ctl.dispose();
    super.dispose();
  }

  int? get _typed {
    final v = double.tryParse(_ctl.text.trim().replaceAll('\$', ''));
    if (v == null || v <= 0) return null;
    return (v * 100).round();
  }

  @override
  Widget build(BuildContext context) {
    final f = F.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final amount = _typed ?? _cents;
    return AlertDialog(
      title: Text(f.prepayTitle(widget.pump)),
      content: SizedBox(
        width: 460,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(f.prepayHint, style: s.text(size: 15, color: c.textMuted)),
            const SizedBox(height: 16),
            GridView.count(
              shrinkWrap: true,
              crossAxisCount: 4,
              mainAxisSpacing: 8,
              crossAxisSpacing: 8,
              childAspectRatio: 1.9,
              physics: const NeverScrollableScrollPhysics(),
              children: [
                for (final p in _presets)
                  Material(
                    color: _cents == p && _typed == null
                        ? c.sage
                        : c.surfaceAlt,
                    shape: RoundedRectangleBorder(borderRadius: s.radiusMedium),
                    child: InkWell(
                      key: Key('preset-$p'),
                      customBorder: RoundedRectangleBorder(
                        borderRadius: s.radiusMedium,
                      ),
                      onTap: () => setState(() {
                        _cents = p;
                        _ctl.clear();
                      }),
                      child: Center(
                        child: Text(
                          '\$${p ~/ 100}',
                          style: TextStyle(
                            fontFamily: 'BarlowCondensed',
                            fontSize: 26,
                            fontWeight: FontWeight.w800,
                            color: _cents == p && _typed == null
                                ? c.onSage
                                : c.text,
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 14),
            TextField(
              key: const Key('prepay-amount'),
              controller: _ctl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
              ],
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                labelText: f.otherAmount,
                prefixText: '\$ ',
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(f.cancel),
        ),
        FilledButton(
          key: const Key('prepay-confirm'),
          onPressed: amount == null
              ? null
              : () => Navigator.pop(context, amount),
          child: Text(amount == null ? f.prepay : f.addPrepay(money(amount))),
        ),
      ],
    );
  }
}
