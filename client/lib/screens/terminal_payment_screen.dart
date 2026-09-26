import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../payments/terminal.dart';

/// "Card (terminal)": a card payment on a terminal the STORE drives (the
/// built-in simulator, the stand-alone simulator on the LAN, J.P. Morgan).
///
/// Starts the payment, then follows it (the store records the tender the
/// moment the terminal approves). Pops with the [TenderResult], or null when
/// cancelled / declined / failed — nothing recorded, the bill stays payable.
///
/// With the built-in simulator ([TerminalStatus.embedded]) the tablet can also
/// play the card reader itself: the reader panel beside the status.
class TerminalPaymentScreen extends StatefulWidget {
  final int checkId;
  final int? groupId;

  /// null = the full amount due.
  final int? amountCents;
  final String tipMode;
  final TerminalStatus status;
  final TerminalClient client;
  final SimReaderClient reader;

  /// How often to ask the store where the payment is.
  final Duration pollInterval;

  /// How long "Approved" stays up before popping.
  final Duration resultHold;
  const TerminalPaymentScreen({
    super.key,
    required this.checkId,
    this.groupId,
    this.amountCents,
    this.tipMode = 'none',
    required this.status,
    this.client = const TerminalClient(),
    this.reader = const SimReaderClient(),
    this.pollInterval = const Duration(milliseconds: 700),
    this.resultHold = const Duration(milliseconds: 1400),
  });

  @override
  State<TerminalPaymentScreen> createState() => _TerminalPaymentScreenState();
}

class _TerminalPaymentScreenState extends State<TerminalPaymentScreen> {
  TerminalPayment? _payment;

  /// The start call failed (terminal unreachable, not paired…): its text.
  String? _startError;
  bool _starting = true;
  bool _leaving = false;
  bool _polling = false;
  Timer? _timer;

  bool get _embedded => widget.status.embedded || (_payment?.embedded ?? false);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _start());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _start() async {
    setState(() {
      _starting = true;
      _startError = null;
      _payment = null;
    });
    try {
      final p = await widget.client.start(
        widget.checkId,
        amountCents: widget.amountCents,
        groupId: widget.groupId,
        tipMode: widget.tipMode,
      );
      if (!mounted || _leaving) return;
      setState(() {
        _starting = false;
        _payment = p;
      });
      _follow(p);
    } on SessionExpiredException {
      return;
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _starting = false;
        _startError = e.toString();
      });
    }
  }

  void _follow(TerminalPayment p) {
    if (p.recorded != null) {
      _approved(p);
      return;
    }
    if (!p.pending) return;
    _timer?.cancel();
    _timer = Timer(widget.pollInterval, _poll);
  }

  Future<void> _poll() async {
    final p = _payment;
    if (p == null || _leaving || _polling || !mounted) return;
    _polling = true;
    try {
      final next = await widget.client.poll(p.paymentId);
      if (!mounted || _leaving) return;
      setState(() => _payment = next);
      _polling = false;
      _follow(next);
    } on SessionExpiredException {
      _polling = false;
    } catch (_) {
      // the store didn't answer this once: keep following
      _polling = false;
      if (mounted && !_leaving) _timer = Timer(widget.pollInterval, _poll);
    }
  }

  Future<void> _approved(TerminalPayment p) async {
    await Future<void>.delayed(widget.resultHold);
    if (mounted && !_leaving) {
      _leaving = true;
      Navigator.pop(context, p.recorded);
    }
  }

  /// Stop the payment on the terminal and go back, recording nothing.
  Future<void> _cancel() async {
    if (_leaving) return;
    final p = _payment;
    if (p != null && p.recorded != null) return; // approved: popping anyway
    if (p != null && p.pending) {
      try {
        final after = await widget.client.cancel(p.paymentId);
        if (after.recorded != null) {
          // approved in the same instant: keep the payment
          if (mounted) setState(() => _payment = after);
          _approved(after);
          return;
        }
      } on ApiException catch (e) {
        if (e.code == 'terminal_cancel_unavailable') {
          if (mounted) showApiError(context, e);
          return; // the card is being processed: keep following
        }
        if (e.code == 'terminal_already_recorded') {
          _poll();
          return;
        }
        // anything else (terminal unreachable…): the store closed it
      } catch (_) {}
    }
    _timer?.cancel();
    _leaving = true;
    if (mounted) Navigator.pop(context, null);
  }

  void _payAnotherWay() {
    _timer?.cancel();
    _leaving = true;
    Navigator.pop(context, null);
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _pendingOrStarting ? _cancel() : _payAnotherWay();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.terminalTitle),
          automaticallyImplyLeading: false,
          actions: const [LangActions()],
        ),
        body: LayoutBuilder(
          builder: (context, box) {
            final wide = box.maxWidth >= 900;
            final status = ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: _statusPanel(l),
            );
            if (!_embedded) {
              return Center(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: status,
                ),
              );
            }
            final reader = SimReaderPanel(client: widget.reader);
            if (wide) {
              return Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(24),
                      child: Center(child: status),
                    ),
                  ),
                  SizedBox(
                    width: 440,
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.fromLTRB(0, 24, 24, 24),
                      child: reader,
                    ),
                  ),
                ],
              );
            }
            return SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [status, const SizedBox(height: 20), reader],
              ),
            );
          },
        ),
      ),
    );
  }

  bool get _pendingOrStarting =>
      _starting || (_payment?.pending ?? false) && _payment?.recorded == null;

  Widget _statusPanel(L l) {
    final p = _payment;
    final amount = p?.amountCents ?? widget.amountCents;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.status.kind == 'jpmorgan')
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: T.accentSoft,
              borderRadius: T.radiusSmall,
            ),
            child: Text(
              l.terminalKindName('jpmorgan'),
              textAlign: TextAlign.center,
              style: T.small(color: T.accent, weight: FontWeight.w600),
            ),
          ),
        const SizedBox(height: 16),
        if (amount != null)
          Text(
            money(amount + (p?.tipCents ?? 0)),
            key: const ValueKey('terminal-amount'),
            textAlign: TextAlign.center,
            style: T.price(size: 44, weight: FontWeight.w700),
          ),
        const SizedBox(height: 20),
        PosPanel(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 28),
          child: _stage(l, p),
        ),
        const SizedBox(height: 20),
        ..._actions(l, p),
      ],
    );
  }

  Widget _stage(L l, TerminalPayment? p) {
    Widget big(IconData icon, Color color) =>
        Icon(icon, size: 64, color: color);
    Widget title(String text, {Color? color}) => Text(
      text,
      key: const ValueKey('terminal-stage'),
      textAlign: TextAlign.center,
      style: T.headline(color: color ?? T.textPrimary),
    );
    Widget line(String text, {Key? key}) => Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Text(
        text,
        key: key,
        textAlign: TextAlign.center,
        style: T.text(size: 16),
      ),
    );
    if (_startError != null) {
      return Column(
        children: [
          big(LucideIcons.wifiOff, T.destructive),
          const SizedBox(height: 12),
          title(l.terminalFailed, color: T.destructive),
          line(_startError!, key: const ValueKey('terminal-message')),
          line(l.nothingCharged),
        ],
      );
    }
    if (_starting || p == null) {
      return Column(
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: 16),
          title(l.terminalStarting),
        ],
      );
    }
    final card = p.card;
    if (p.recorded != null) {
      return Column(
        children: [
          big(LucideIcons.circleCheck, T.navy),
          const SizedBox(height: 12),
          title(l.terminalApproved, color: T.navy),
          if (card != null && (card.brand != null || card.last4 != null))
            line(
              '${card.brand ?? ''} •••• ${card.last4 ?? ''}',
              key: const ValueKey('terminal-card'),
            ),
          if (card?.authCode != null)
            line('${l.authCodeLabel} ${card!.authCode}'),
          if (card?.processorRef != null)
            line(
              '${card!.processor ?? l.processorRefLabel} · ${card.processorRef}',
              key: const ValueKey('terminal-processor-ref'),
            ),
        ],
      );
    }
    switch (p.status) {
      case 'PENDING':
        return Column(
          children: [
            p.prompt == 'processing'
                ? const CircularProgressIndicator()
                : big(LucideIcons.nfc, T.primary),
            const SizedBox(height: 16),
            title(switch (p.prompt) {
              'enter_pin' => l.terminalEnterPin,
              'choose_tip' => l.terminalChooseTip,
              'processing' => l.terminalProcessing,
              _ => l.terminalPresentCard,
            }),
            if (!_embedded) line(l.followTerminal(widget.status.address)),
            if (p.readerOffline)
              line(
                l.terminalOfflineNow,
                key: const ValueKey('terminal-offline'),
              ),
          ],
        );
      case 'DECLINED':
        return Column(
          children: [
            big(LucideIcons.circleX, T.destructive),
            const SizedBox(height: 12),
            title(l.terminalDeclined, color: T.destructive),
            line(
              l.terminalDeclineMessage(p.declineCode),
              key: const ValueKey('terminal-message'),
            ),
            if (card?.processorRef != null)
              line(
                '${card!.processor ?? l.processorRefLabel} · ${card.processorRef}',
              ),
            line(l.nothingCharged),
          ],
        );
      case 'TIMEOUT':
        return Column(
          children: [
            big(LucideIcons.timer, T.destructive),
            const SizedBox(height: 12),
            title(l.terminalTimedOut, color: T.destructive),
            line(l.nothingCharged),
          ],
        );
      case 'CANCELED':
        return Column(
          children: [
            big(LucideIcons.ban, T.textMuted),
            const SizedBox(height: 12),
            title(l.terminalCancelled),
            if (p.errorCode != null && l.apiError(p.errorCode) != null)
              line(l.apiError(p.errorCode)!),
            line(l.nothingCharged),
          ],
        );
      default: // FAILED
        return Column(
          children: [
            big(LucideIcons.triangleAlert, T.destructive),
            const SizedBox(height: 12),
            title(l.terminalFailed, color: T.destructive),
            line(
              l.apiError(p.errorCode) ?? l.apiError('internal')!,
              key: const ValueKey('terminal-message'),
            ),
            line(l.nothingCharged),
          ],
        );
    }
  }

  List<Widget> _actions(L l, TerminalPayment? p) {
    if (p?.recorded != null) return const [];
    if (_starting || (p?.pending ?? false)) {
      return [
        SizedBox(
          height: T.minTouch,
          child: OutlinedButton.icon(
            key: const ValueKey('terminal-cancel'),
            icon: const Icon(LucideIcons.x),
            label: Text(l.cancel),
            onPressed: _leaving ? null : _cancel,
          ),
        ),
      ];
    }
    return [
      SizedBox(
        height: T.minTouch,
        child: FilledButton.icon(
          key: const ValueKey('terminal-retry'),
          icon: const Icon(LucideIcons.refreshCw),
          label: Text(l.retry),
          onPressed: _leaving ? null : _start,
        ),
      ),
      const SizedBox(height: 8),
      SizedBox(
        height: T.minTouch,
        child: OutlinedButton(
          key: const ValueKey('terminal-other-way'),
          onPressed: _leaving ? null : _payAnotherWay,
          child: Text(l.payAnotherWay),
        ),
      ),
    ];
  }
}

/// The built-in simulator's card reader, played on this tablet: the
/// customer-facing screen plus the "wallet" (test card, bank answer,
/// tap / insert / swipe). Talks to /terminal/ui/* like the browser page.
class SimReaderPanel extends StatefulWidget {
  final SimReaderClient client;
  final Duration pollInterval;
  const SimReaderPanel({
    super.key,
    this.client = const SimReaderClient(),
    this.pollInterval = const Duration(milliseconds: 500),
  });

  @override
  State<SimReaderPanel> createState() => _SimReaderPanelState();
}

class _SimReaderPanelState extends State<SimReaderPanel> {
  static const _cards = [
    ('visa', 'Visa', '4242'),
    ('mastercard', 'Mastercard', '4444'),
    ('amex', 'Amex', '8431'),
    ('interac', 'Interac', '0002'),
    ('discover', 'Discover', '1117'),
  ];
  SimScreen _screen = SimScreen.idle;
  String _card = 'visa';
  String _outcome = 'approve';
  String _pin = '';
  Timer? _timer;
  bool _busy = false;

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
    try {
      final s = await widget.client.state();
      if (!mounted) return;
      setState(() {
        if (s.screen != _screen.screen || s.txnId != _screen.txnId) _pin = '';
        _screen = s;
      });
    } catch (_) {
      /* the store is busy: keep the last screen */
    }
    if (mounted) _timer = Timer(widget.pollInterval, _refresh);
  }

  Future<void> _act(Future<SimScreen> Function() op) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final s = await op();
      if (mounted) setState(() => _screen = s);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _present(String entry) {
    _pin = '';
    _act(() => widget.client.present(entry, _card, _outcome));
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final s = _screen;
    return Column(
      key: const ValueKey('sim-reader'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(l.readerTitle, style: T.small(weight: FontWeight.w700)),
        const SizedBox(height: 8),
        // the reader: dark bezel, light screen
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFF161B21),
            borderRadius: BorderRadius.circular(22),
          ),
          child: Container(
            constraints: const BoxConstraints(minHeight: 240),
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: const Color(0xFFF6F7F4),
              borderRadius: BorderRadius.circular(12),
            ),
            child: DefaultTextStyle(
              style: const TextStyle(color: Color(0xFF15181C)),
              child: _readerScreen(l, s),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Text(l.readerTestCard, style: T.small()),
        const SizedBox(height: 6),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final (key, brand, last4) in _cards)
              ChoiceChip(
                key: ValueKey('reader-card-$key'),
                label: Text('$brand •••• $last4'),
                selected: _card == key,
                onSelected: (_) => setState(() => _card = key),
              ),
          ],
        ),
        const SizedBox(height: 10),
        Text(l.readerOutcome, style: T.small()),
        const SizedBox(height: 6),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final (key, label) in [
              ('approve', l.readerApprove),
              ('insufficient_funds', l.readerInsufficient),
              ('do_not_honour', l.readerDoNotHonour),
              ('timeout', l.readerTimeout),
              ('cancel', l.readerCustomerCancels),
            ])
              ChoiceChip(
                key: ValueKey('reader-outcome-$key'),
                label: Text(label),
                selected: _outcome == key,
                onSelected: (_) => setState(() => _outcome = key),
              ),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            for (final (key, label) in [
              ('tap', l.readerTap),
              ('insert', l.readerInsert),
              ('swipe', l.readerSwipe),
            ]) ...[
              Expanded(
                child: SizedBox(
                  height: T.minTouch,
                  child: FilledButton(
                    key: ValueKey('reader-$key'),
                    onPressed: s.screen == 'present' && !_busy
                        ? () => _present(key)
                        : null,
                    child: Text(label, textAlign: TextAlign.center),
                  ),
                ),
              ),
              if (key != 'swipe') const SizedBox(width: 8),
            ],
          ],
        ),
        const SizedBox(height: 6),
        Text(l.readerNoRealCards, style: T.small().copyWith(fontSize: 12)),
      ],
    );
  }

  Widget _readerScreen(L l, SimScreen s) {
    final total = s.totalCents ?? s.amountCents;
    Widget amount([double size = 40]) => total == null
        ? const SizedBox.shrink()
        : Text(
            money(total),
            key: const ValueKey('reader-amount'),
            style: T.price(
              size: size,
              weight: FontWeight.w700,
              color: const Color(0xFF15181C),
            ),
          );
    Widget big(String text, Color color) => Text(
      text,
      key: const ValueKey('reader-screen-title'),
      textAlign: TextAlign.center,
      style: T.text(size: 24, weight: FontWeight.w800, color: color),
    );
    final cardLine = s.last4 == null
        ? null
        : '${s.brand ?? ''} •••• ${s.last4}';
    final children = <Widget>[
      switch (s.screen) {
        'tip' => Column(
          children: [
            Text(l.readerAddTip, style: T.text(size: 18)),
            amount(34),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: [
                for (final pct in [15, 18, 20])
                  OutlinedButton(
                    key: ValueKey('reader-tip-$pct'),
                    onPressed: () => _act(
                      () => widget.client.tip(
                        ((s.amountCents ?? 0) * pct / 100).round(),
                      ),
                    ),
                    child: Text(
                      '$pct % · ${money(((s.amountCents ?? 0) * pct / 100).round())}',
                    ),
                  ),
                OutlinedButton(
                  key: const ValueKey('reader-tip-0'),
                  onPressed: () => _act(() => widget.client.tip(0)),
                  child: Text(l.readerNoTip),
                ),
              ],
            ),
          ],
        ),
        'present' => Column(
          children: [
            amount(),
            const SizedBox(height: 10),
            const Icon(LucideIcons.nfc, size: 56, color: Color(0xFF15181C)),
            const SizedBox(height: 8),
            big(l.readerTapInsertSwipe, const Color(0xFF15181C)),
            if (s.secondsLeft != null)
              Text('${s.secondsLeft} s', style: T.small()),
          ],
        ),
        'pin' => _pinPad(l, s),
        'processing' => Column(
          children: [
            amount(28),
            const SizedBox(height: 14),
            const CircularProgressIndicator(),
            const SizedBox(height: 10),
            big(l.terminalProcessing, const Color(0xFF15181C)),
          ],
        ),
        'approved' => Column(
          children: [
            const Icon(
              LucideIcons.circleCheck,
              size: 56,
              color: Color(0xFF1F8F4E),
            ),
            big(l.readerApprovedBig, const Color(0xFF1F8F4E)),
            amount(28),
            if (cardLine != null) Text(cardLine),
            if (s.authCode != null) Text('${l.authCodeLabel} ${s.authCode}'),
          ],
        ),
        'declined' => Column(
          children: [
            const Icon(LucideIcons.circleX, size: 56, color: Color(0xFFC0392B)),
            big(l.readerDeclinedBig, const Color(0xFFC0392B)),
            if (s.message != null)
              Text(s.message!, textAlign: TextAlign.center),
            if (cardLine != null) Text(cardLine),
          ],
        ),
        'cancelled' => big(l.readerCancelledBig, const Color(0xFFB7791F)),
        'timeout' => big(l.readerTimeoutBig, const Color(0xFFB7791F)),
        'pairing' => Column(
          children: [
            Text(l.terminalCodeLabel),
            Text(
              s.pairingCode ?? '------',
              style: T.price(size: 40, weight: FontWeight.w800),
            ),
          ],
        ),
        _ => big(l.readerReady, const Color(0xFF15181C)),
      },
    ];
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        ...children,
        if (['tip', 'present', 'pin'].contains(s.screen))
          TextButton(
            key: const ValueKey('reader-red-key'),
            onPressed: () => _act(widget.client.cancel),
            child: Text(l.readerCustomerCancels),
          ),
      ],
    );
  }

  Widget _pinPad(L l, SimScreen s) {
    Widget key(String k) => Padding(
      padding: const EdgeInsets.all(4),
      child: SizedBox(
        width: 64,
        height: 48,
        child: OutlinedButton(
          key: ValueKey('reader-pin-$k'),
          onPressed: () {
            if (k == 'ok') {
              if (_pin.length >= 4) {
                final pin = _pin;
                _pin = '';
                _act(() => widget.client.pin(pin));
              }
            } else if (k == 'del') {
              setState(
                () => _pin = _pin.isEmpty
                    ? ''
                    : _pin.substring(0, _pin.length - 1),
              );
            } else if (_pin.length < 6) {
              setState(() => _pin += k);
            }
          },
          child: k == 'ok'
              ? const Icon(LucideIcons.check)
              : k == 'del'
              ? const Icon(LucideIcons.delete)
              : Text(k, style: T.text(size: 20, weight: FontWeight.w600)),
        ),
      ),
    );
    return Column(
      children: [
        Text(
          l.readerEnterPin,
          style: T.text(size: 18, weight: FontWeight.w600),
        ),
        const SizedBox(height: 6),
        Text(
          '●' * _pin.length + '○' * (4 - _pin.length).clamp(0, 4),
          key: const ValueKey('reader-pin-dots'),
          style: T.text(size: 22),
        ),
        for (final row in const [
          ['1', '2', '3'],
          ['4', '5', '6'],
          ['7', '8', '9'],
          ['del', '0', 'ok'],
        ])
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [for (final k in row) key(k)],
          ),
      ],
    );
  }
}
