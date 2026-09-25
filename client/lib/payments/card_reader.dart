import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:mek_stripe_terminal/mek_stripe_terminal.dart';
import 'package:permission_handler/permission_handler.dart';

import '../api.dart';
import 'stripe_log.dart';

/// What the reader is doing, for the payment screen.
enum ReaderPhase { permissions, connecting, waitingForCard, processing }

/// A failure the payment screen turns into a precise en/fr message.
/// [kind] picks the message; [code] (the Terminal SDK error code, or the
/// exception type) is shown in small text and logged.
class CardReaderException implements Exception {
  final CardReaderError kind;
  final String? code;
  const CardReaderException(this.kind, [this.code]);

  /// System settings screen that fixes it, if any: `location` / `bluetooth` / `app`.
  String? get settings => switch (kind) {
    CardReaderError.locationOff => 'location',
    CardReaderError.bluetoothOff => 'bluetooth',
    CardReaderError.permissionDenied => 'app',
    _ => null,
  };

  @override
  String toString() =>
      'CardReaderException(${kind.name}${code == null ? '' : ', $code'})';
}

enum CardReaderError {
  /// App permission (location / nearby devices) denied.
  permissionDenied,

  /// Device-wide Location switch is off.
  locationOff,

  /// Device-wide Bluetooth switch is off.
  bluetoothOff,
  unsupported,

  /// A real network failure between the tablet and Stripe.
  offline,

  /// The SDK could not get a connection token from the store.
  tokenFailed,

  /// Stripe answered with an error (account, location, session…).
  stripeApi,
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
/// is initialized lazily (once per process), so startup and every other
/// tender never touch it. Every step and every SDK error is logged
/// (see stripe_log.dart) — never a token or secret.
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
  bool _listening = false;

  /// The SDK's connection-token provider: our store's
  /// POST /stripe/connection-token, sent with this staff session's bearer
  /// token (Api headers). Logged without the secret.
  static Future<String> _fetchToken() async {
    stripeLog('connection token: requesting from the store');
    try {
      final secret = await Api.stripeConnectionToken();
      stripeLog(
        'connection token: ok (${secret.startsWith('pst_test_') ? 'test' : 'NOT test'} mode)',
      );
      return secret;
    } on ApiException catch (e) {
      stripeLog(
        'connection token: FAILED store code=${e.code} message=${e.message}',
        warn: true,
      );
      throw Exception('store connection-token failed: ${e.code ?? e.message}');
    } catch (e) {
      stripeLog('connection token: FAILED ${e.runtimeType}: $e', warn: true);
      throw Exception('store connection-token failed: ${e.runtimeType}');
    }
  }

  Future<void> _permissions() async {
    final wanted = [
      Permission.locationWhenInUse,
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
    ];
    for (final p in wanted) {
      final status = await p.request();
      stripeLog('permission $p: ${status.name}');
      // limited/restricted count as granted enough for the simulated reader
      if (status.isDenied || status.isPermanentlyDenied) {
        permissionDenied = true;
        throw CardReaderException(
          CardReaderError.permissionDenied,
          '$p ${status.name}',
        );
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
    final services = await deviceServices();
    stripeLog(
      'device services: location=${services.locationOn} bluetooth=${services.bluetoothOn}',
    );
    // the Terminal SDK refuses to discover readers (even simulated ones)
    // while device Location is off
    if (services.locationOn == false) {
      throw const CardReaderException(
        CardReaderError.locationOff,
        'locationServicesDisabled',
      );
    }
    onPhase(ReaderPhase.connecting);
    try {
      if (!Terminal.isInitialized) {
        stripeLog('Terminal.init');
        await Terminal.init(fetchToken: _fetchToken);
        stripeLog('Terminal.init ok');
      }
      final terminal = Terminal.instance;
      _listen(terminal);
      final connected = await terminal.getConnectedReader();
      if (connected != null) {
        stripeLog(
          'reader already connected: ${connected.serialNumber} (${connected.deviceType?.name})',
        );
        return;
      }
      final reader = await _discoverSimulated(terminal);
      if (_canceled) throw const CardReaderException(CardReaderError.canceled);
      stripeLog(
        'connectReader ${reader.serialNumber} (${reader.deviceType?.name}, simulated=${reader.simulated}) location=$locationId',
      );
      final r = await terminal.connectReader(
        reader,
        configuration: BluetoothConnectionConfiguration(
          locationId: locationId,
          readerDelegate: _SimulatedReaderDelegate(),
        ),
      );
      stripeLog(
        'connectReader ok: ${r.serialNumber} location=${r.locationId ?? r.location?.id}',
      );
    } catch (e) {
      throw _failure('prepare', e);
    }
  }

  /// Log connection/payment status changes once per process.
  void _listen(Terminal terminal) {
    if (_listening) return;
    _listening = true;
    terminal.onConnectionStatusChange.listen(
      (s) => stripeLog('connection status: ${s.name}'),
    );
    terminal.onPaymentStatusChange.listen(
      (s) => stripeLog('payment status: ${s.name}'),
    );
  }

  Future<Reader> _discoverSimulated(Terminal terminal) async {
    final found = Completer<Reader>();
    await _discovery?.cancel(); // a discovery left over from a failed attempt
    _discovery = null;
    stripeLog('discoverReaders: Bluetooth, isSimulated=true, timeout 20s');
    _discovery = terminal
        .discoverReaders(
          BluetoothDiscoveryConfiguration(
            isSimulated: true,
            timeoutInSeconds: 20,
          ),
        )
        .listen(
          (readers) {
            stripeLog(
              'discoverReaders: ${readers.length} found ${readers.map((r) => r.serialNumber).join(', ')}',
            );
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
                  'no simulated reader found',
                ),
              );
            }
          },
        );
    try {
      return await found.future;
    } finally {
      await _discovery?.cancel();
      _discovery = null;
    }
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
      stripeLog('setSimulatorConfiguration card=${card.name}');
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
      stripeLog(
        'retrievePaymentIntent ok: ${intent.id} ${intent.amount} ${intent.currency} ${intent.status.name}',
      );
      if (_canceled) throw const CardReaderException(CardReaderError.canceled);
      onPhase(ReaderPhase.waitingForCard);
      _processing = terminal.processPaymentIntent(intent, skipTipping: true);
      final done = await _processing!;
      stripeLog('processPaymentIntent ok: ${done.id} ${done.status.name}');
    } catch (e) {
      throw _failure('collect', e);
    } finally {
      _processing = null;
      await statusSub?.cancel();
    }
  }

  @override
  Future<void> cancel() async {
    _canceled = true;
    stripeLog('cancel requested');
    try {
      await _discovery?.cancel();
      _discovery = null;
      await _processing?.cancel();
    } catch (e) {
      stripeLog('cancel: ${e.runtimeType}: $e');
    }
  }

  /// Log [e] in full (code, message, apiError, never a secret) and map it.
  static CardReaderException _failure(String step, Object e) {
    if (e is CardReaderException) {
      stripeLog('$step: ${e.kind.name} (${e.code})', warn: true);
      return e;
    }
    if (e is TerminalException) {
      stripeLog(
        '$step: TerminalException code=${e.code.name} message=${e.message}'
        '${e.apiError == null ? '' : ' apiError=${e.apiError}'}'
        '${e.paymentIntent == null ? '' : ' paymentIntent=${e.paymentIntent!.id}/${e.paymentIntent!.status.name}'}',
        warn: true,
      );
      return CardReaderException(mapTerminalCode(e.code), e.code.name);
    }
    if (e is PlatformException) {
      stripeLog(
        '$step: PlatformException code=${e.code} message=${e.message} details=${e.details}',
        warn: true,
      );
      return CardReaderException(CardReaderError.readerFailed, e.code);
    }
    stripeLog('$step: ${e.runtimeType}: $e', warn: true);
    return CardReaderException(
      CardReaderError.readerFailed,
      e.runtimeType.toString(),
    );
  }

  /// Terminal SDK error code → what to tell staff. "offline" only for real
  /// network failures between the tablet and Stripe.
  @visibleForTesting
  static CardReaderError mapTerminalCode(TerminalExceptionCode code) =>
      switch (code) {
        TerminalExceptionCode.declinedByStripeApi ||
        TerminalExceptionCode.declinedByReader => CardReaderError.declined,
        TerminalExceptionCode.canceled => CardReaderError.canceled,
        TerminalExceptionCode.locationServicesDisabled =>
          CardReaderError.locationOff,
        TerminalExceptionCode.bluetoothDisabled => CardReaderError.bluetoothOff,
        TerminalExceptionCode.bluetoothPermissionDenied =>
          CardReaderError.permissionDenied,
        TerminalExceptionCode.notConnectedToInternet ||
        TerminalExceptionCode.requestTimedOut ||
        TerminalExceptionCode.stripeApiConnectionError ||
        TerminalExceptionCode.internetConnectTimeOut ||
        TerminalExceptionCode.internalNetworkError => CardReaderError.offline,
        TerminalExceptionCode.connectionTokenProviderError =>
          CardReaderError.tokenFailed,
        TerminalExceptionCode.stripeApiError ||
        TerminalExceptionCode.stripeApiResponseDecodingError ||
        TerminalExceptionCode.sessionExpired ||
        TerminalExceptionCode.invalidClientSecret ||
        TerminalExceptionCode.featureNotEnabledOnAccount ||
        TerminalExceptionCode.invalidParameter ||
        TerminalExceptionCode.invalidRequiredParameter =>
          CardReaderError.stripeApi,
        _ => CardReaderError.readerFailed,
      };
}

class _SimulatedReaderDelegate extends MobileReaderDelegate {
  @override
  void onReportAvailableUpdate(ReaderSoftwareUpdate update) =>
      stripeLog('reader: update available (ignored for the simulated reader)');
  @override
  void onStartInstallingUpdate(
    ReaderSoftwareUpdate update,
    Cancellable cancelUpdate,
  ) => stripeLog('reader: installing update');
  @override
  void onReportReaderSoftwareUpdateProgress(double progress) {}
  @override
  void onFinishInstallingUpdate(
    ReaderSoftwareUpdate? update,
    TerminalException? exception,
  ) => stripeLog(
    'reader: update finished${exception == null ? '' : ' with ${exception.code.name}: ${exception.message}'}',
  );
  @override
  void onRequestReaderDisplayMessage(ReaderDisplayMessage message) =>
      stripeLog('reader: display ${message.name}');
  @override
  void onRequestReaderInput(List<ReaderInputOption> options) =>
      stripeLog('reader: waiting for ${options.map((o) => o.name).join('/')}');
  @override
  void onDisconnect(DisconnectReason reason) =>
      stripeLog('reader: disconnected (${reason.name})', warn: true);
}
