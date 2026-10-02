import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import 'pin_pad.dart';

/// A card payment for this check is still on the reader (the store or the
/// tablet restarted in the middle of it). Says so, asks the store every
/// [pollInterval] (the store asks the reader), and once it is settled says
/// how: recorded (the amount and what is still due), or not taken (pay
/// another way). A manager can cancel it on the reader meanwhile.
///
/// [onSettled] gets the fresh check when the card payment ends either way:
/// the screen around it re-enables its tenders.
class CardPendingBanner extends StatefulWidget {
  final int checkId;

  /// A split check's bill group: "still due" is the group's.
  final int? groupId;
  final void Function(Check check) onSettled;
  final Duration pollInterval;

  const CardPendingBanner({
    super.key,
    required this.checkId,
    this.groupId,
    required this.onSettled,
    this.pollInterval = const Duration(seconds: 2),
  });

  @override
  State<CardPendingBanner> createState() => _CardPendingBannerState();
}

class _CardPendingBannerState extends State<CardPendingBanner> {
  Timer? _timer;
  List<InFlightCard> _pending = const [];

  /// Set once nothing is pending any more: what happened.
  List<InFlightCard>? _resolved;
  Check? _check;
  bool _loading = true;
  bool _cancelling = false;
  bool _checking = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_checking || !mounted) return;
    _checking = true;
    try {
      _apply(await Api.cardPending(widget.checkId));
    } on SessionExpiredException {
      return;
    } catch (_) {
      // the store didn't answer this once: keep asking
      _schedule();
    } finally {
      _checking = false;
    }
  }

  void _schedule() {
    _timer?.cancel();
    if (mounted) _timer = Timer(widget.pollInterval, _refresh);
  }

  final _ended = <InFlightCard>[];

  void _apply(CardPendingStatus s) {
    if (!mounted) return;
    _ended.addAll(s.resolved);
    setState(() {
      _loading = false;
      _pending = s.pending;
      _check = s.check;
      if (s.pending.isEmpty) _resolved = List.of(_ended);
    });
    if (s.pending.isEmpty) {
      _timer?.cancel();
      widget.onSettled(s.check);
    } else {
      _schedule();
    }
  }

  Future<void> _managerCancel(InFlightCard card) async {
    final l = L.of(context);
    final approval = await requireGrant(
      context,
      Perm.voidCheck,
      title: l.cardPendingCancel,
    );
    if (approval == null || !mounted) return;
    setState(() => _cancelling = true);
    try {
      _apply(
        await Api.cancelPendingCard(
          widget.checkId,
          card.paymentId,
          approval.managerPin,
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _cancelling = false);
    }
  }

  int _dueOf(Check c) {
    final g = widget.groupId;
    if (g == null) return c.outstandingCents;
    return c.split?.groups
            .where((x) => x.id == g)
            .firstOrNull
            ?.outstandingCents ??
        c.outstandingCents;
  }

  String _prompt(L l, InFlightCard p) => switch (p.prompt) {
    'enter_pin' => l.terminalEnterPin,
    'choose_tip' => l.terminalChooseTip,
    'processing' => l.terminalProcessing,
    'waiting_for_phone' => l.terminalWaitingForPhone,
    _ => l.terminalPresentCard,
  };

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final resolved = _resolved;
    if (resolved != null) {
      final recorded = resolved.where((r) => r.recorded).toList();
      final check = _check;
      final String text;
      if (recorded.isNotEmpty && check != null) {
        final amount = money(recorded.fold(0, (n, r) => n + r.amountCents));
        final due = _dueOf(check);
        text = due == 0
            ? l.cardPendingRecordedPaid(amount)
            : l.cardPendingRecorded(amount, money(due));
      } else {
        text = l.cardPendingNotTaken;
      }
      return PosPanel(
        key: const ValueKey('card-pending-result'),
        color: T.surfaceAlt,
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Icon(
              recorded.isNotEmpty ? LucideIcons.circleCheck : LucideIcons.info,
              color: T.primary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(text, style: T.text(weight: FontWeight.w600)),
            ),
          ],
        ),
      );
    }
    final offline = _pending.any((p) => p.readerOffline);
    return PosPanel(
      key: const ValueKey('card-pending-banner'),
      color: T.pending,
      borderColor: T.attention,
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: T.onPending,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  l.cardPendingTitle,
                  style: T.text(weight: FontWeight.w700, color: T.onPending),
                ),
              ),
            ],
          ),
          for (final p in _pending) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${l.cardPendingAmount(money(p.amountCents))} · '
                    '${p.readerOffline ? l.cardPendingOffline : _prompt(l, p)}',
                    key: ValueKey('card-pending-${p.paymentId}'),
                    style: T.small(color: T.onPending),
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  key: ValueKey('card-pending-cancel-${p.paymentId}'),
                  icon: const Icon(LucideIcons.lockKeyhole, size: 16),
                  label: Text(l.cardPendingCancel),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: T.onPending,
                    side: const BorderSide(color: T.onPending),
                    minimumSize: const Size(0, 44),
                  ),
                  onPressed: _cancelling ? null : () => _managerCancel(p),
                ),
              ],
            ),
          ],
          if (!_loading && !offline) ...[
            const SizedBox(height: 6),
            Text(l.cardPendingWait, style: T.small(color: T.onPending)),
          ],
        ],
      ),
    );
  }
}
