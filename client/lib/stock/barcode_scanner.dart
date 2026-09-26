import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../design/tokens.dart';
import '../retail/scan_detector.dart';
import '../retail/sp_theme.dart';
import 'stock_i18n.dart';

/// Product barcodes the camera looks for: UPC-A/E and EAN-13/8 (shelf
/// products), plus Code 128 for in-store labels.
const productBarcodeFormats = [
  BarcodeFormat.upcA,
  BarcodeFormat.upcE,
  BarcodeFormat.ean13,
  BarcodeFormat.ean8,
  BarcodeFormat.code128,
];

/// The phone/tablet camera as a barcode scanner (mobile_scanner: CameraX +
/// ML Kit on Android, Apple Vision on iOS). Not on the web: a browser only
/// opens the camera on HTTPS, and the store is plain HTTP on the LAN — web
/// stays on HID scanners and typing.
class CameraScanner {
  CameraScanner._();

  /// Overridable for tests.
  static bool? supportedOverride;

  static bool get supported =>
      supportedOverride ??
      (!kIsWeb &&
          (defaultTargetPlatform == TargetPlatform.android ||
              defaultTargetPlatform == TargetPlatform.iOS));

  /// One scan: opens the camera, returns the first code read (null = closed).
  static Future<String?> scanOnce(BuildContext context) =>
      Navigator.of(context).push<String>(
        MaterialPageRoute(
          fullscreenDialog: true,
          builder: (_) => const CameraScanPage(),
        ),
      );

  /// Keep scanning: every code read goes to [onCode] (the same code again
  /// only after a pause) until the page is closed.
  static Future<void> scanMany(
    BuildContext context,
    FutureOr<String?> Function(String code) onCode,
  ) => Navigator.of(context).push<void>(
    MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => CameraScanPage(onCode: onCode),
    ),
  );
}

/// The camera page. With [onCode] it stays open and shows what each scan
/// did (the callback's text); without it, it closes with the first code.
class CameraScanPage extends StatefulWidget {
  final FutureOr<String?> Function(String code)? onCode;
  const CameraScanPage({super.key, this.onCode});

  @override
  State<CameraScanPage> createState() => _CameraScanPageState();
}

class _CameraScanPageState extends State<CameraScanPage> {
  final _controller = MobileScannerController(
    formats: productBarcodeFormats,
    detectionSpeed: DetectionSpeed.normal,
    detectionTimeoutMs: 400,
  );
  String? _last;
  DateTime _lastAt = DateTime.fromMillisecondsSinceEpoch(0);
  String? _feedback;
  bool _done = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _onDetect(BarcodeCapture capture) async {
    final code = capture.barcodes
        .map((b) => b.rawValue?.trim() ?? '')
        .firstWhere((c) => c.isNotEmpty, orElse: () => '');
    if (code.isEmpty || _done) return;
    final now = DateTime.now();
    // the same code twice in a row counts again only after a pause
    if (code == _last && now.difference(_lastAt) < const Duration(seconds: 2)) {
      return;
    }
    _last = code;
    _lastAt = now;
    HapticFeedback.mediumImpact();
    final onCode = widget.onCode;
    if (onCode == null) {
      _done = true;
      Navigator.of(context).pop(code);
      return;
    }
    final text = await onCode(code);
    if (mounted) setState(() => _feedback = text ?? code);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(s.scanWithCamera),
        actions: [
          IconButton(
            tooltip: s.torch,
            icon: const Icon(LucideIcons.flashlight),
            onPressed: () => _controller.toggleTorch(),
          ),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          MobileScanner(
            controller: _controller,
            onDetect: _onDetect,
            errorBuilder: (context, error) => Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Text(
                  s.cameraUnavailable,
                  textAlign: TextAlign.center,
                  style: T.text(size: 17, color: Colors.white),
                ),
              ),
            ),
          ),
          // the aiming window
          IgnorePointer(
            child: Center(
              child: Container(
                width: 280,
                height: 150,
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.white, width: 3),
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
            ),
          ),
          Positioned(
            left: 16,
            right: 16,
            bottom: 32,
            child: SafeArea(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 14,
                ),
                decoration: BoxDecoration(
                  color: _feedback == null ? Colors.black54 : c.sage,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Text(
                  _feedback ?? s.pointAtBarcode,
                  textAlign: TextAlign.center,
                  style: T.text(
                    size: 17,
                    weight: FontWeight.w600,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A USB / Bluetooth HID scanner on any screen: a fast burst of keys ending
/// in Enter is a scan (the retail counter's [ScanBurstDetector]); slower keys
/// are a person typing and pass through untouched.
class HidScanListener extends StatefulWidget {
  final Widget child;
  final void Function(String code) onScan;

  /// False while a dialog owns the keyboard.
  final bool enabled;
  const HidScanListener({
    super.key,
    required this.child,
    required this.onScan,
    this.enabled = true,
  });

  @override
  State<HidScanListener> createState() => _HidScanListenerState();
}

class _HidScanListenerState extends State<HidScanListener> {
  final _scanner = HidScanner();

  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_onKey);
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    _scanner.dispose();
    super.dispose();
  }

  bool _onKey(KeyEvent e) {
    if (!widget.enabled || e is KeyUpEvent) return false;
    final key = e.logicalKey;
    if (key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.numpadEnter) {
      final code = _scanner.enter(e.timeStamp);
      if (code == null) return false;
      widget.onScan(code);
      return true;
    }
    final ch = e.character;
    if (ch != null && ch.length == 1 && ch.codeUnitAt(0) >= 0x20) {
      _scanner.char(ch, e.timeStamp);
    }
    return false;
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
