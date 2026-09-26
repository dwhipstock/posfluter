import 'dart:async';

/// Where scanned barcodes come from: a USB / Bluetooth HID scanner, which
/// "types" the code ([HidScanner] + [ScanBurstDetector]), or anything else
/// that emits decoded codes on [scans]. The camera (stock/barcode_scanner.dart)
/// is opened on demand from the screens instead.
abstract class BarcodeSource {
  Stream<String> get scans;
  void dispose();
}

/// Tells a barcode scanner from a person at the keyboard. A HID scanner types
/// a whole code as one fast burst — a few milliseconds per key — and ends it
/// with Enter; a person types an order of magnitude slower. Feed it every
/// printable key and every Enter with the time it arrived.
///
/// - a key that follows the previous one within [maxGap] extends the burst;
///   a slower key starts a new one (so hand-typed text never accumulates);
/// - Enter within [maxGap] of a burst of at least [minLength] keys → that
///   burst is a scan; anything else → null (a person pressed Enter).
///
/// Pure and clock-free: callers pass timestamps, so it is unit-testable.
class ScanBurstDetector {
  ScanBurstDetector({
    this.maxGap = const Duration(milliseconds: 50),
    this.minLength = 6,
  });

  final Duration maxGap;
  final int minLength;

  final StringBuffer _burst = StringBuffer();
  Duration? _last;

  /// The keys of the burst in progress (so a search field can give them back).
  String get pending => _burst.toString();

  /// A printable character arrived at [at].
  void char(String c, Duration at) {
    if (_last == null || at - _last! > maxGap) _burst.clear();
    _burst.write(c);
    _last = at;
  }

  /// Enter arrived at [at]: the scanned code, or null.
  String? enter(Duration at) {
    final code = _burst.toString();
    final fast = _last != null && at - _last! <= maxGap;
    reset();
    return fast && code.length >= minLength ? code.trim() : null;
  }

  void reset() {
    _burst.clear();
    _last = null;
  }
}

/// A 2D scanner reading the back of a driver's licence types the whole AAMVA
/// text — many lines, with Enter between them. Collect every key of a fast
/// burst (Enter as a newline) and hand the text over once the keys stop for
/// [idle]. Short bursts (a product barcode) are not an ID.
class IdScanCapture {
  IdScanCapture({
    this.maxGap = const Duration(milliseconds: 60),
    this.idle = const Duration(milliseconds: 150),
    this.minLength = 30,
  });

  final Duration maxGap;
  final Duration idle;
  final int minLength;

  final StringBuffer _text = StringBuffer();
  Duration? _last;

  void key(String c, Duration at) {
    if (_last != null && at - _last! > maxGap) _text.clear();
    _text.write(c);
    _last = at;
  }

  void enter(Duration at) => key('\n', at);

  /// At [now]: the captured ID text once the burst has gone quiet, else null.
  String? complete(Duration now) {
    if (_last == null || now - _last! < idle) return null;
    final text = _text.toString();
    _text.clear();
    _last = null;
    return text.length >= minLength ? text : null;
  }
}

/// The HID scanner as a [BarcodeSource]: key events in, scans out. The retail
/// screen forwards raw keys (character + Enter) with their timestamps.
class HidScanner implements BarcodeSource {
  HidScanner({ScanBurstDetector? detector})
    : detector = detector ?? ScanBurstDetector();

  final ScanBurstDetector detector;
  final _out = StreamController<String>.broadcast();

  @override
  Stream<String> get scans => _out.stream;

  void char(String c, Duration at) => detector.char(c, at);

  /// Returns the code when this Enter completed a scan (also emitted on [scans]).
  String? enter(Duration at) {
    final code = detector.enter(at);
    if (code != null) _out.add(code);
    return code;
  }

  @override
  void dispose() => _out.close();
}
