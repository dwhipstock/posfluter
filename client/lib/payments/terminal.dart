import 'package:flutter/material.dart';

import '../api.dart';
import '../screens/stripe_payment_screen.dart';
import '../screens/terminal_payment_screen.dart';
import 'card_reader.dart';

/// A card-present payment, whatever terminal the store has. Both flows pop
/// with the recorded [TenderResult], or null when nothing was recorded (the
/// bill stays payable by cash or anything else).
///
///  - [StripeCardPayment]: Stripe Terminal. The tablet's SDK (mek_stripe_terminal)
///    connects the reader and collects; the store captures. Unchanged flow.
///  - [StoreTerminalCardPayment]: a terminal the STORE drives (the built-in
///    simulator, the stand-alone simulator on the LAN, J.P. Morgan): the tablet
///    starts it and follows it; the store records the tender on approval.
abstract class CardPresentPayment {
  Future<TenderResult?> run(
    BuildContext context, {
    required int checkId,
    int? groupId,
    int? amountCents,
  });
}

class StripeCardPayment implements CardPresentPayment {
  final String locationId;
  final SimulatedTestCard simulatedCard;
  final CardReader reader;
  const StripeCardPayment({
    required this.locationId,
    required this.reader,
    this.simulatedCard = SimulatedTestCard.approved,
  });

  @override
  Future<TenderResult?> run(
    BuildContext context, {
    required int checkId,
    int? groupId,
    int? amountCents,
  }) => Navigator.of(context).push<TenderResult>(
    MaterialPageRoute(
      builder: (_) => StripePaymentScreen(
        checkId: checkId,
        groupId: groupId,
        amountCents: amountCents,
        locationId: locationId,
        simulatedCard: simulatedCard,
        reader: reader,
      ),
    ),
  );
}

class StoreTerminalCardPayment implements CardPresentPayment {
  final TerminalStatus status;

  /// none | on_reader (the reader asks for a tip: restaurants).
  final String tipMode;
  final TerminalClient client;
  final SimReaderClient reader;
  const StoreTerminalCardPayment({
    required this.status,
    this.tipMode = 'none',
    this.client = const TerminalClient(),
    this.reader = const SimReaderClient(),
  });

  @override
  Future<TenderResult?> run(
    BuildContext context, {
    required int checkId,
    int? groupId,
    int? amountCents,
  }) => Navigator.of(context).push<TenderResult>(
    MaterialPageRoute(
      builder: (_) => TerminalPaymentScreen(
        checkId: checkId,
        groupId: groupId,
        amountCents: amountCents,
        tipMode: tipMode,
        status: status,
        client: client,
        reader: reader,
      ),
    ),
  );
}

/// The store-driven terminal's calls (a seam: tests pass a fake).
class TerminalClient {
  const TerminalClient();
  Future<TerminalStatus> status() => Api.terminalStatus();
  Future<TerminalPayment> start(
    int checkId, {
    int? amountCents,
    int? groupId,
    String tipMode = 'none',
  }) => Api.startTerminalPayment(
    checkId,
    amountCents: amountCents,
    groupId: groupId,
    tipMode: tipMode,
  );
  Future<TerminalPayment> poll(String paymentId) =>
      Api.terminalPayment(paymentId);
  Future<TerminalPayment> cancel(String paymentId) =>
      Api.cancelTerminalPayment(paymentId);
  Future<TerminalStatus> pair(String host, String code) =>
      Api.pairTerminal(host, code);
  Future<TerminalStatus> unpair() => Api.unpairTerminal();
}

/// The built-in simulator's reader, played on this tablet (/terminal/ui/*).
class SimReaderClient {
  const SimReaderClient();
  Future<SimScreen> state() => Api.simReaderState();
  Future<SimScreen> present(String entry, String card, String outcome) =>
      Api.simReaderPresent(entry, card, outcome);
  Future<SimScreen> pin(String pin) => Api.simReaderPin(pin);
  Future<SimScreen> tip(int tipCents) => Api.simReaderTip(tipCents);
  Future<SimScreen> cancel() => Api.simReaderCancel();
}
