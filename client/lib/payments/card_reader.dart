import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:mek_stripe_terminal/mek_stripe_terminal.dart';
import 'package:permission_handler/permission_handler.dart';

import '../api.dart';

/// What the reader is doing, for the payment screen.
enum ReaderPhase { permissions, connecting, waitingForCard, processing }

/// A failure the payment screen turns into a friendly en/fr message.
/// [kind] picks the message; [detail] is for logs only.
class CardReaderException implements Exception {
  final CardReaderError kind;
  final String? detail;
  const CardReaderException(this.kind, [this.detail]);
  @override
  String toString() =>
      'CardReaderException($kind${detail == null ? '' : ': $detail'})';
}

enum CardReaderError {
  permissionDenied,
  servicesOff,
  unsupported,
  offline,
  declined,
  canceled,
  readerFailed,
}

/// Simulated test cards for the simulated reader (Stripe test numbers).
enum SimulatedTestCard {
  approved(null),
  declined('4000000000000002'),
  insufficientFunds('4000000000009995');

  final String? number;
  const SimulatedTestCard(this.number);
}

/// The card-reader side of "Card (Stripe)". The screen only talks to this, so
/// widget tests swap in a fake and never touch the Stripe SDK.
abstract class CardReader {
  /// Permissions, SDK init, discover + connect the (simulated) reader.
  Future<void> prepare(String locationId, void Function(ReaderPhase) onPhase);

  /// Collect + authorize the PaymentIntent behind [clientSecret].
  Future<void> collect(
    String clientSecret,
    SimulatedTestCard card,
    void Function(ReaderPhase) onPhase,
  );

  /// Stop an in-flight [prepare]/[collect]. Safe to call any time.
  Future<void> cancel();
}

/// Stripe Terminal via `mek_stripe_terminal`, SIMULATED Bluetooth reader only
/// (test mode). Nothing here runs until staff picks "Card (Stripe)": the SDK
/// is initialized lazily, so startup and every other tender never touch it.
class StripeTerminalReader implements CardReader {
  StripeTerminalReader._();
  static final StripeTerminalReader instance = StripeTerminalReader._();

  /// Set once the operator denied a permission this session: Stripe stays
  /// disabled (greyed out) instead of asking again on every payment.
  static bool permissionDenied = false;

  static bool get supported => !kIsWeb && Platform.isAndroid;

  CancelableFuture<PaymentIntent>? _processing;
  StreamSubscription<List<Reader>>? _discovery;
  bool _canceled = false;

  Future<void> _permissions() async {
    final wanted = [
      Permission.locationWhenInUse,
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
    ];
    for (final p in wanted) {
      final status = await p.request();
      // limited/restricted count as granted enough for the simulated reader
      if (status.isDenied || status.isPermanentlyDenied) {
        permissionDenied = true;
        throw CardReaderException(CardReaderError.permissionDenied, '$p');
      }
    }
  }

  @override
  Future<void> prepare(
    String locationId,
    void Function(ReaderPhase) onPhase,
  ) async {
    _canceled = false;
    if (!supported) {
      throw const CardReaderException(CardReaderError.unsupported);
    }
    onPhase(ReaderPhase.permissions);
    await _permissions();
    onPhase(ReaderPhase.connecting);
    try {
      if (!Terminal.isInitialized) {
        await Terminal.init(fetchToken: Api.stripeConnectionToken);
      }
      final terminal = Terminal.instance;
      if (await terminal.getConnectedReader() != null) return;
      final reader = await _discoverSimulated(terminal);
      if (_canceled) throw const CardReaderException(CardReaderError.canceled);
      await terminal.connectReader(
        reader,
        configuration: BluetoothConnectionConfiguration(
          locationId: locationId,
          readerDelegate: _SimulatedReaderDelegate(),
        ),
      );
    } on TerminalException catch (e) {
      throw _map(e);
    }
  }

  Future<Reader> _discoverSimulated(Terminal terminal) {
    final found = Completer<Reader>();
    _discovery?.cancel();
    _discovery = terminal
        .discoverReaders(
          BluetoothDiscoveryConfiguration(
            isSimulated: true,
            timeoutInSeconds: 20,
          ),
        )
        .listen(
          (readers) {
            if (readers.isNotEmpty && !found.isCompleted) {
              found.complete(readers.first);
            }
          },
          onError: (Object e) {
            if (!found.isCompleted) found.completeError(e);
          },
          onDone: () {
            if (!found.isCompleted) {
              found.completeError(
                const CardReaderException(
                  CardReaderError.readerFailed,
                  'no reader',
                ),
              );
            }
          },
        );
    return found.future.whenComplete(() {
      _discovery?.cancel();
      _discovery = null;
    });
  }

  @override
  Future<void> collect(
    String clientSecret,
    SimulatedTestCard card,
    void Function(ReaderPhase) onPhase,
  ) async {
    final terminal = Terminal.instance;
    StreamSubscription<PaymentStatus>? statusSub;
    try {
      await terminal.setSimulatorConfiguration(
        SimulatorConfiguration(
          update: SimulateReaderUpdate.none,
          simulatedCard: SimulatedCard(testCardNumber: card.number),
        ),
      );
      statusSub = terminal.onPaymentStatusChange.listen((s) {
        if (s == PaymentStatus.waitingForInput) {
          onPhase(ReaderPhase.waitingForCard);
        } else if (s == PaymentStatus.processing) {
          onPhase(ReaderPhase.processing);
        }
      });
      final intent = await terminal.retrievePaymentIntent(clientSecret);
      if (_canceled) throw const CardReaderException(CardReaderError.canceled);
      onPhase(ReaderPhase.waitingForCard);
      _processing = terminal.processPaymentIntent(intent, skipTipping: true);
      await _processing!;
    } on TerminalException catch (e) {
      throw _map(e);
    } finally {
      _processing = null;
      await statusSub?.cancel();
    }
  }

  @override
  Future<void> cancel() async {
    _canceled = true;
    try {
      await _discovery?.cancel();
      _discovery = null;
      await _processing?.cancel();
    } catch (_) {
      /* nothing to cancel, or already finished */
    }
  }

  static CardReaderException _map(TerminalException e) {
    final kind = switch (e.code) {
      TerminalExceptionCode.declinedByStripeApi ||
      TerminalExceptionCode.declinedByReader => CardReaderError.declined,
      TerminalExceptionCode.canceled => CardReaderError.canceled,
      TerminalExceptionCode.bluetoothPermissionDenied =>
        CardReaderError.permissionDenied,
      TerminalExceptionCode.locationServicesDisabled ||
      TerminalExceptionCode.bluetoothDisabled => CardReaderError.servicesOff,
      TerminalExceptionCode.notConnectedToInternet ||
      TerminalExceptionCode.requestTimedOut ||
      TerminalExceptionCode.stripeApiConnectionError ||
      TerminalExceptionCode.internalNetworkError ||
      TerminalExceptionCode.connectionTokenProviderError =>
        CardReaderError.offline,
      _ => CardReaderError.readerFailed,
    };
    return CardReaderException(kind, e.code.name);
  }
}

class _SimulatedReaderDelegate extends MobileReaderDelegate {
  @override
  void onReportAvailableUpdate(ReaderSoftwareUpdate update) {}
  @override
  void onStartInstallingUpdate(
    ReaderSoftwareUpdate update,
    Cancellable cancelUpdate,
  ) {}
  @override
  void onReportReaderSoftwareUpdateProgress(double progress) {}
  @override
  void onFinishInstallingUpdate(
    ReaderSoftwareUpdate? update,
    TerminalException? exception,
  ) {}
  @override
  void onRequestReaderDisplayMessage(ReaderDisplayMessage message) {}
  @override
  void onRequestReaderInput(List<ReaderInputOption> options) {}
}
