import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'reader_api.dart';
import 'tap_reader.dart';

enum ReaderStage {
  /// Looking for the store on the Wi-Fi.
  finding,

  /// Found (or typed) the store; waiting for the code the POS shows.
  pairing,

  /// Connecting the Tap to Pay reader.
  connecting,

  /// Connected; waiting for the POS to send a payment.
  ready,

  /// A payment is here: simulated reader → pick a test card and tap; real → tap the card.
  payment,

  /// The card is being read / confirmed.
  processing,

  /// Approved / declined / cancelled, shown for a moment.
  result,

  /// Something needs the person holding the phone (Developer options on, no Stripe…).
  error,
}

/// The card reader's brain, UI-free (tests drive it with fakes): pair with
/// the store, connect Tap to Pay, poll for payments, collect, report back.
class ReaderController extends ChangeNotifier {
  final TapReader reader;
  final ReaderApi Function(String baseUrl, String? token) apiFactory;
  final Future<String?> Function() discover;
  final Duration pollEvery;
  final Duration resultFor;

  ReaderController({
    required this.reader,
    required this.discover,
    ReaderApi Function(String baseUrl, String? token)? apiFactory,
    this.pollEvery = const Duration(seconds: 1),
    this.resultFor = const Duration(seconds: 4),
  }) : apiFactory = apiFactory ?? ((u, t) => ReaderApi(u, token: t));

  static const _kUrl = 'reader.storeUrl';
  static const _kToken = 'reader.token';
  static const _kStore = 'reader.storeName';

  ReaderStage stage = ReaderStage.finding;
  String? storeUrl, storeName, readerName, message, resultTitle;
  bool resultOk = false;
  bool simulated = true;
  ReaderJob? job;
  ReaderTestCard testCard = ReaderTestCard.visa;
  final List<String> log = [];

  ReaderApi? _api;
  Timer? _poll;
  bool _busy = false;
  bool _disposed = false;

  void _set(ReaderStage s, {String? msg}) {
    stage = s;
    message = msg;
    if (!_disposed) notifyListeners();
  }

  void _log(String line) {
    log.insert(0, line);
    if (log.length > 40) log.removeLast();
  }

  /// App start: reuse the saved pairing, else find the store.
  Future<void> start() async {
    final p = await SharedPreferences.getInstance();
    storeUrl = p.getString(_kUrl);
    storeName = p.getString(_kStore);
    final token = p.getString(_kToken);
    if (storeUrl != null && token != null) {
      _api = apiFactory(storeUrl!, token);
      await connect();
      return;
    }
    await find();
  }

  Future<void> find() async {
    _set(ReaderStage.finding);
    final url = storeUrl ?? await discover();
    storeUrl = url;
    _set(
      ReaderStage.pairing,
      msg: url == null
          ? 'No store found on this Wi-Fi. Type its address.'
          : null,
    );
  }

  /// [code] is the 6 digits on the POS (Settings → Card terminal).
  Future<void> pair(
    String code, {
    String? address,
    String deviceName = 'Phone',
  }) async {
    final url = _normalize(address) ?? storeUrl;
    if (url == null) {
      _set(
        ReaderStage.pairing,
        msg: 'Type the store\'s address, like 192.168.1.20:8082',
      );
      return;
    }
    storeUrl = url;
    final api = apiFactory(url, null);
    try {
      storeName = await api.pair(code, deviceName);
    } on ReaderApiException catch (e) {
      _set(
        ReaderStage.pairing,
        msg: e.code == 'terminal_pairing_code_wrong'
            ? 'That isn\'t the code on the POS. Check Settings → Card terminal.'
            : e.message,
      );
      return;
    } catch (_) {
      _set(ReaderStage.pairing, msg: 'Can\'t reach the store at $url');
      return;
    }
    final p = await SharedPreferences.getInstance();
    await p.setString(_kUrl, url);
    await p.setString(_kToken, api.token!);
    await p.setString(_kStore, storeName!);
    _api = api;
    _log('Paired with $storeName');
    await connect();
  }

  static String? _normalize(String? raw) {
    final v = raw?.trim() ?? '';
    if (v.isEmpty) return null;
    final withScheme = v.startsWith('http') ? v : 'http://$v';
    final u = Uri.tryParse(withScheme);
    if (u == null || u.host.isEmpty) return null;
    return '${u.scheme}://${u.host}:${u.hasPort ? u.port : 8082}';
  }

  Future<void> forget() async {
    _poll?.cancel();
    final p = await SharedPreferences.getInstance();
    await p.remove(_kToken);
    _api = null;
    await find();
  }

  /// Store config → connect Tap to Pay → start polling.
  Future<void> connect() async {
    final api = _api;
    if (api == null) return find();
    _poll?.cancel();
    _set(ReaderStage.connecting);
    try {
      await api.heartbeat('connecting');
      final cfg = await api.config();
      storeName = cfg.storeName;
      simulated = cfg.simulated;
      if (!cfg.stripeAvailable || cfg.locationId == null) {
        await api.heartbeat(
          'error',
          message: cfg.reason ?? 'stripe unavailable',
        );
        _set(
          ReaderStage.error,
          msg:
              'The store\'s Stripe isn\'t ready (${cfg.reason ?? 'no location'}).',
        );
        return;
      }
      readerName = await reader.connect(
        locationId: cfg.locationId!,
        simulated: cfg.simulated,
        fetchToken: api.connectionToken,
        merchantName: cfg.storeName,
      );
      await api.heartbeat('ready', readerName: readerName);
      _log('Reader ready: $readerName');
      _set(ReaderStage.ready);
      _poll = Timer.periodic(pollEvery, (_) => tick());
    } on ReaderApiException catch (e) {
      if (e.notPaired) return forget();
      _set(ReaderStage.error, msg: e.message);
    } on TapReaderException catch (e) {
      _log('Reader error: ${e.code}');
      await _quiet(() => api.heartbeat('error', message: e.code));
      _set(ReaderStage.error, msg: e.friendly);
    } catch (e) {
      _set(ReaderStage.error, msg: 'Can\'t reach the store ($e)');
    }
  }

  /// One poll: any payment for the phone? Also the phone's heartbeat.
  Future<void> tick() async {
    final api = _api;
    if (api == null || _busy) return;
    _busy = true;
    try {
      if (stage == ReaderStage.payment && job != null) {
        // waiting for the tap button: still wanted?
        final j = await api.payment(job!.paymentIntentId);
        if (!j.active) _finish(false, 'Cancelled on the POS');
      } else if (stage == ReaderStage.ready) {
        final j = await api.nextPayment();
        if (j != null) await _arrive(j);
      }
    } on ReaderApiException catch (e) {
      if (e.notPaired) await forget();
    } catch (_) {
      // the Wi-Fi blinked: next tick
    } finally {
      _busy = false;
    }
  }

  Future<void> _arrive(ReaderJob j) async {
    job = j;
    testCard = ReaderTestCard.visa;
    _log('Payment ${j.amountLabel}');
    _set(ReaderStage.payment);
    // a real reader goes straight to "tap your card"; the simulated one waits for the test card
    if (!simulated) await collect();
  }

  /// Take the card for the current payment (simulated: with [testCard]).
  Future<void> collect() async {
    final api = _api;
    final j = job;
    if (api == null || j == null) return;
    await _quiet(() => api.report(j.paymentIntentId, 'collecting'));
    _set(ReaderStage.processing);
    // the POS may cancel while the card is on the phone
    final watch = Timer.periodic(const Duration(milliseconds: 1500), (_) async {
      try {
        final now = await api.payment(j.paymentIntentId);
        if (!now.active) await reader.cancel();
      } catch (_) {}
    });
    try {
      await reader.collect(
        j.clientSecret,
        testCard: simulated ? testCard : null,
        onPhase: (p) {
          if (p == 'processing') {
            _quiet(() => api.report(j.paymentIntentId, 'processing'));
          }
        },
      );
      await _quiet(() => api.report(j.paymentIntentId, 'collected'));
      _finish(true, 'Approved');
    } on TapReaderException catch (e) {
      if (e.canceled) {
        await _quiet(
          () => api.report(j.paymentIntentId, 'canceled', code: e.code),
        );
        _finish(false, 'Cancelled');
      } else {
        await _quiet(
          () => api.report(
            j.paymentIntentId,
            'failed',
            code: e.code,
            message: e.message,
            declined: e.declined,
          ),
        );
        _finish(false, e.declined ? 'Declined' : e.friendly);
      }
    } finally {
      watch.cancel();
    }
  }

  /// The customer cancels on the phone.
  Future<void> cancelPayment() async {
    final j = job;
    if (j == null) return;
    await reader.cancel();
    if (stage == ReaderStage.payment) {
      await _quiet(() => _api!.report(j.paymentIntentId, 'canceled'));
      _finish(false, 'Cancelled');
    }
  }

  void _finish(bool ok, String title) {
    resultOk = ok;
    resultTitle = title;
    _log('${job?.amountLabel ?? ''} $title');
    job = null;
    _set(ReaderStage.result);
    Timer(resultFor, () {
      if (stage == ReaderStage.result) _set(ReaderStage.ready);
    });
  }

  Future<void> _quiet(Future<void> Function() f) async {
    try {
      await f();
    } catch (_) {}
  }

  @override
  void dispose() {
    _disposed = true;
    _poll?.cancel();
    super.dispose();
  }
}
