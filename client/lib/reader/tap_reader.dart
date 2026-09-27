import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:mek_stripe_terminal/mek_stripe_terminal.dart';
import 'package:permission_handler/permission_handler.dart';

import '../payments/stripe_log.dart';

/// Stripe's documented test cards for a simulated reader (card_present).
enum ReaderTestCard {
  visa('Approve — Visa', '4242424242424242'),
  mastercard('Approve — Mastercard', '5555555555554444'),
  declined('Decline — generic', '4000000000000002'),
  insufficientFunds('Decline — insufficient funds', '4000000000009995'),
  expired('Decline — expired card', '4000000000000069');

  final String label, number;
  const ReaderTestCard(this.label, this.number);
}

/// A reader failure, with the Terminal SDK's code (tapToPayInsecureEnvironment…).
class TapReaderException implements Exception {
  final String code;
  final String message;
  final bool declined;
  final bool canceled;
  const TapReaderException(
    this.code,
    this.message, {
    this.declined = false,
    this.canceled = false,
  });

  /// What to tell the person holding the phone.
  String get friendly => switch (code) {
    'tapToPayInsecureEnvironment' =>
      'Turn Developer options OFF on this phone (Settings → System → Developer options), then try again. '
          'Stripe refuses payments while they are on.',
    'tapToPayDeviceTampered' =>
      'This phone failed Stripe\'s security check (rooted or modified).',
    'tapToPayUnsupportedDevice' =>
      'This phone can\'t take Tap to Pay (it needs NFC and a hardware keystore).',
    'tapToPayUnsupportedOperatingSystemVersion' =>
      'Tap to Pay needs Android 13 or newer.',
    'tapToPayDebugNotSupported' =>
      'Tap to Pay needs the release build of Card Reader (not a debug build).',
    'nfcDisabled' => 'Turn NFC on (Settings → Connections → NFC).',
    'permissionDenied' =>
      'Allow location for Card Reader: Stripe needs it to take payments.',
    'locationServicesDisabled' =>
      'Turn Location on: Stripe needs it to take payments.',
    'connectionTokenProviderError' =>
      'The store couldn\'t get a Stripe token (check the store\'s Stripe key).',
    _ => message.isEmpty ? code : message,
  };

  @override
  String toString() => 'TapReaderException($code, $message)';
}

/// The phone's card reader. The controller only talks to this, so tests swap
/// in a fake and never touch the Stripe SDK.
abstract class TapReader {
  /// Connect the Tap to Pay reader to [locationId]. Returns a label for it.
  Future<String> connect({
    required String locationId,
    required bool simulated,
    required Future<String> Function() fetchToken,
    String? merchantName,
  });

  /// Collect + confirm the PaymentIntent behind [clientSecret] (authorized,
  /// not captured: the store captures). [testCard] only on a simulated reader.
  Future<void> collect(
    String clientSecret, {
    ReaderTestCard? testCard,
    void Function(String phase)? onPhase,
  });

  /// Stop a collect in progress. Safe any time.
  Future<void> cancel();
}

/// Stripe Tap to Pay on Android through `mek_stripe_terminal`.
class StripeTapToPayReader implements TapReader {
  StripeTapToPayReader._();
  static final StripeTapToPayReader instance = StripeTapToPayReader._();

  static Future<String> Function()? _fetch;
  CancelableFuture<PaymentIntent>? _processing;
  bool _simulated = true;

  static Future<String> _token() async {
    final f = _fetch;
    if (f == null) throw Exception('no store connection');
    stripeLog('reader: connection token from the store');
    return f();
  }

  @override
  Future<String> connect({
    required String locationId,
    required bool simulated,
    required Future<String> Function() fetchToken,
    String? merchantName,
  }) async {
    if (kIsWeb || !Platform.isAndroid) {
      throw const TapReaderException(
        'unsupported',
        'Tap to Pay needs an Android phone',
      );
    }
    _fetch = fetchToken;
    final loc = await Permission.locationWhenInUse.request();
    if (!loc.isGranted && !loc.isLimited) {
      throw const TapReaderException(
        'permissionDenied',
        'location permission denied',
      );
    }
    try {
      if (!Terminal.isInitialized) await Terminal.init(fetchToken: _token);
      final t = Terminal.instance;
      final connected = await t.getConnectedReader();
      if (connected != null && simulated == _simulated) {
        return connected.label ?? connected.serialNumber;
      }
      if (connected != null) await t.disconnectReader();
      _simulated = simulated;
      stripeLog('reader: discover Tap to Pay (simulated=$simulated)');
      final readers = await t
          .discoverReaders(
            TapToPayDiscoveryConfiguration(isSimulated: simulated),
          )
          .firstWhere((r) => r.isNotEmpty)
          .timeout(const Duration(seconds: 30));
      final r = await t.connectReader(
        readers.first,
        configuration: TapToPayConnectionConfiguration(
          locationId: locationId,
          merchantDisplayName: merchantName,
          readerDelegate: _Delegate(),
        ),
      );
      stripeLog('reader: connected ${r.serialNumber}');
      return simulated
          ? 'Stripe simulated Tap to Pay'
          : 'Tap to Pay (${r.serialNumber})';
    } catch (e) {
      throw _map(e);
    }
  }

  @override
  Future<void> collect(
    String clientSecret, {
    ReaderTestCard? testCard,
    void Function(String phase)? onPhase,
  }) async {
    final t = Terminal.instance;
    StreamSubscription<PaymentStatus>? sub;
    try {
      if (_simulated) {
        await t.setSimulatorConfiguration(
          SimulatorConfiguration(
            update: SimulateReaderUpdate.none,
            simulatedCard: SimulatedCard(
              testCardNumber: (testCard ?? ReaderTestCard.visa).number,
            ),
          ),
        );
      }
      sub = t.onPaymentStatusChange.listen((s) {
        if (s == PaymentStatus.processing) onPhase?.call('processing');
      });
      final intent = await t.retrievePaymentIntent(clientSecret);
      onPhase?.call('collecting');
      _processing = t.processPaymentIntent(intent, skipTipping: true);
      final done = await _processing!;
      stripeLog('reader: ${done.id} ${done.status.name}');
    } catch (e) {
      throw _map(e);
    } finally {
      _processing = null;
      await sub?.cancel();
    }
  }

  @override
  Future<void> cancel() async {
    try {
      await _processing?.cancel();
    } catch (_) {}
  }

  static TapReaderException _map(Object e) {
    if (e is TapReaderException) return e;
    if (e is TerminalException) {
      stripeLog('reader: ${e.code.name} ${e.message}', warn: true);
      return TapReaderException(
        e.code.name,
        e.message,
        declined:
            e.code == TerminalExceptionCode.declinedByStripeApi ||
            e.code == TerminalExceptionCode.declinedByReader,
        canceled: e.code == TerminalExceptionCode.canceled,
      );
    }
    if (e is PlatformException) {
      return TapReaderException(e.code, e.message ?? '');
    }
    if (e is TimeoutException) {
      return const TapReaderException(
        'readerNotFound',
        'no Tap to Pay reader found',
      );
    }
    return TapReaderException(e.runtimeType.toString(), e.toString());
  }
}

class _Delegate extends TapToPayReaderDelegate {
  @override
  void onDisconnect(DisconnectReason reason) =>
      stripeLog('reader: disconnected (${reason.name})', warn: true);
  @override
  void onReportReaderSoftwareUpdateProgress(double progress) {}
  @override
  void onStartInstallingUpdate(
    ReaderSoftwareUpdate update,
    Cancellable cancelUpdate,
  ) {}
  @override
  void onFinishInstallingUpdate(
    ReaderSoftwareUpdate? update,
    TerminalException? exception,
  ) {}
  @override
  void onRequestReaderDisplayMessage(ReaderDisplayMessage message) =>
      stripeLog('reader: display ${message.name}');
  @override
  void onRequestReaderInput(List<ReaderInputOption> options) =>
      stripeLog('reader: waiting for ${options.map((o) => o.name).join('/')}');
}
