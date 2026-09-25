import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../payments/card_reader.dart';

enum _Stage {
  starting,
  permissions,
  connecting,
  tapCard,
  processing,
  approved,
  declined,
  failed,
}

/// "Card (Stripe)" payment, TEST MODE with the simulated reader:
/// connect → tap card (simulated) → processing → approved / declined.
///
/// Pops with the [TenderResult] once the store recorded the tender, or with
/// null when cancelled / failed — in which case nothing was recorded and the
/// tender screen is exactly as before (cash etc. still work).
class StripePaymentScreen extends StatefulWidget {
  final int checkId;
  final int? groupId;

  /// null = the full amount due.
  final int? amountCents;
  final String locationId;
  final SimulatedTestCard simulatedCard;
  final CardReader reader;

  const StripePaymentScreen({
    super.key,
    required this.checkId,
    this.groupId,
    this.amountCents,
    required this.locationId,
    required this.reader,
    this.simulatedCard = SimulatedTestCard.approved,
  });

  @override
  State<StripePaymentScreen> createState() => _StripePaymentScreenState();
}

class _StripePaymentScreenState extends State<StripePaymentScreen> {
  _Stage _stage = _Stage.starting;
  StripeIntent? _intent;
  String? _message; // friendly text for declined / failed
  bool _leaving = false;
  late SimulatedTestCard _card = widget.simulatedCard;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _run());
  }

  void _phase(ReaderPhase p) {
    if (!mounted || _leaving) return;
    setState(() {
      _stage = switch (p) {
        ReaderPhase.permissions => _Stage.permissions,
        ReaderPhase.connecting => _Stage.connecting,
        ReaderPhase.waitingForCard => _Stage.tapCard,
        ReaderPhase.processing => _Stage.processing,
      };
    });
  }

  Future<void> _run() async {
    final l = L.of(context);
    setState(() {
      _stage = _Stage.starting;
      _message = null;
    });
    try {
      // reader first: a reader that can't connect never creates a PaymentIntent
      await widget.reader.prepare(widget.locationId, _phase);
      if (_leaving) return;
      _intent ??= await Api.createStripeIntent(
        widget.checkId,
        amountCents: widget.amountCents,
        groupId: widget.groupId,
      );
      if (_leaving) return;
      if (mounted) setState(() {}); // show the amount + currency
      await widget.reader.collect(_intent!.clientSecret, _card, _phase);
      if (_leaving) return;
      _phase(ReaderPhase.processing);
      final result = await Api.confirmStripePayment(_intent!.paymentId);
      if (!mounted) return;
      setState(() => _stage = _Stage.approved);
      await Future<void>.delayed(const Duration(milliseconds: 900));
      if (mounted) Navigator.pop(context, result);
    } on CardReaderException catch (e) {
      if (_leaving || e.kind == CardReaderError.canceled) return;
      if (e.kind == CardReaderError.declined) {
        await _declined(l);
      } else {
        _fail(l.stripeReaderError(e.kind.name));
      }
    } on ApiException catch (e) {
      if (_leaving) return;
      if (e.code == 'stripe_declined') {
        _show(_Stage.declined, l.stripeDeclineMessage(e.declineCode));
      } else {
        _fail(e.toString());
      }
    } on SessionExpiredException {
      return;
    } catch (e) {
      if (!_leaving) _fail(l.stripeReaderError('offline'));
    }
  }

  /// The reader said declined: ask the store for Stripe's decline reason.
  Future<void> _declined(L l) async {
    String? code;
    final intent = _intent;
    if (intent != null) {
      try {
        await Api.confirmStripePayment(intent.paymentId);
      } on ApiException catch (e) {
        code = e.declineCode;
      } catch (_) {}
    }
    _show(_Stage.declined, l.stripeDeclineMessage(code));
  }

  void _fail(String message) => _show(_Stage.failed, message);

  void _show(_Stage stage, String message) {
    if (!mounted) return;
    setState(() {
      _stage = stage;
      _message = message;
    });
  }

  /// Back to the tender screen, recording nothing.
  Future<void> _cancel() async {
    if (_leaving || _stage == _Stage.approved) return;
    setState(() => _leaving = true);
    await widget.reader.cancel();
    final intent = _intent;
    if (intent != null) {
      try {
        await Api.cancelStripePayment(intent.paymentId);
      } catch (_) {
        /* best-effort: an uncaptured authorization lapses on its own */
      }
    }
    if (mounted) Navigator.pop(context, null);
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final intent = _intent;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _cancel();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.stripeTitle),
          automaticallyImplyLeading: false,
          actions: const [LangActions()],
        ),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: T.accentSoft,
                      borderRadius: T.radiusSmall,
                    ),
                    child: Text(
                      l.stripeTestMode,
                      textAlign: TextAlign.center,
                      style: T.small(color: T.accent, weight: FontWeight.w600),
                    ),
                  ),
                  const SizedBox(height: 24),
                  if (intent != null) ...[
                    Text(
                      '${intent.currency} ${(intent.amountCents / 100).toStringAsFixed(2)}',
                      textAlign: TextAlign.center,
                      style: T.price(size: 44, weight: FontWeight.w700),
                    ),
                    if (intent.currency.toUpperCase() != 'CAD')
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                          l.chargedInCurrency(intent.currency),
                          textAlign: TextAlign.center,
                          style: T.small(color: T.attention),
                        ),
                      ),
                    const SizedBox(height: 24),
                  ],
                  PosPanel(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 28,
                    ),
                    child: Column(
                      children: [
                        _stageIcon(),
                        const SizedBox(height: 16),
                        Text(
                          _stageTitle(l),
                          key: const ValueKey('stripe-stage'),
                          textAlign: TextAlign.center,
                          style: T.headline(
                            color: switch (_stage) {
                              _Stage.approved => T.navy,
                              _Stage.declined || _Stage.failed => T.destructive,
                              _ => T.textPrimary,
                            },
                          ),
                        ),
                        if (_message != null) ...[
                          const SizedBox(height: 10),
                          Text(
                            _message!,
                            textAlign: TextAlign.center,
                            style: T.text(size: 16),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            l.nothingCharged,
                            textAlign: TextAlign.center,
                            style: T.small(),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  if (_stage == _Stage.declined) ...[
                    _simulatedCardPicker(l),
                    const SizedBox(height: 12),
                    SizedBox(
                      height: T.minTouch,
                      child: FilledButton.icon(
                        icon: const Icon(LucideIcons.refreshCw),
                        label: Text(l.retry),
                        onPressed: _leaving ? null : _run,
                      ),
                    ),
                    const SizedBox(height: 8),
                  ],
                  if (_stage == _Stage.failed) ...[
                    SizedBox(
                      height: T.minTouch,
                      child: OutlinedButton.icon(
                        icon: const Icon(LucideIcons.refreshCw),
                        label: Text(l.retry),
                        onPressed: _leaving ? null : _run,
                      ),
                    ),
                    const SizedBox(height: 8),
                  ],
                  if (_stage != _Stage.approved)
                    SizedBox(
                      height: T.minTouch,
                      child: TextButton.icon(
                        key: const ValueKey('stripe-cancel'),
                        icon: const Icon(LucideIcons.x),
                        label: Text(l.cancel),
                        onPressed: _leaving || _stage == _Stage.processing
                            ? null
                            : _cancel,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _simulatedCardPicker(L l) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(l.simulatedCardLabel, style: T.small()),
      const SizedBox(height: 6),
      Wrap(
        spacing: 8,
        children: [
          for (final c in SimulatedTestCard.values)
            ChoiceChip(
              label: Text(switch (c) {
                SimulatedTestCard.approved => l.simApproved,
                SimulatedTestCard.declined => l.simDeclined,
                SimulatedTestCard.insufficientFunds => l.simInsufficient,
              }),
              selected: _card == c,
              onSelected: (_) => setState(() => _card = c),
            ),
        ],
      ),
    ],
  );

  String _stageTitle(L l) => switch (_stage) {
    _Stage.starting => l.stripePreparing,
    _Stage.permissions => l.stripePermissions,
    _Stage.connecting => l.stripeConnecting,
    _Stage.tapCard => l.stripeTapCard,
    _Stage.processing => l.stripeProcessing,
    _Stage.approved => l.stripeApproved,
    _Stage.declined => l.stripeDeclinedTitle,
    _Stage.failed => l.stripeErrorTitle,
  };

  Widget _stageIcon() => switch (_stage) {
    _Stage.approved => const Icon(
      LucideIcons.circleCheck,
      size: 64,
      color: T.navy,
    ),
    _Stage.declined || _Stage.failed => const Icon(
      LucideIcons.circleX,
      size: 64,
      color: T.destructive,
    ),
    _Stage.tapCard => const Icon(LucideIcons.nfc, size: 64, color: T.accent),
    _ => const SizedBox(
      width: 56,
      height: 56,
      child: CircularProgressIndicator(strokeWidth: 5, color: T.accent),
    ),
  };
}
