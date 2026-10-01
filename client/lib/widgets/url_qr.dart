import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../design/tokens.dart';

/// A URL a person should open, shown two ways: a scannable QR code (dark on a
/// white card, at least 200px) and the URL itself as selectable text, so it
/// can be read off the screen and typed or copied too. The QR always encodes
/// exactly [url], the same string printed under it.
///
/// Every place the app shows a link for someone to open (pickup board,
/// kitchen screen, staff phone app, owner portal, a table's menu) uses this.
/// Not for internal API addresses.
///
/// Safe inside an [AlertDialog]: the dialog sizes its content with an
/// [IntrinsicWidth], and [QrImageView]'s root is a LayoutBuilder, which throws
/// on intrinsic queries (the "invisible dialog" bug). The tight [SizedBox]
/// around the QR answers those queries itself. A payload that cannot be
/// encoded shows the URL in the square instead of a blank box.
class UrlQr extends StatelessWidget {
  const UrlQr(
    this.url, {
    super.key,
    this.size = 220,
    this.textColor,
    this.textSize = 16,
  }) : assert(size >= 200, 'a phone camera needs at least 200px');

  /// The link: both the QR payload and the text shown under it.
  final String url;

  /// The QR's side, in logical pixels (200 or more).
  final double size;

  /// The URL text colour; defaults to the theme's text colour. Pass a dark
  /// colour on a light surface (the receipt-paper dialog).
  final Color? textColor;
  final double textSize;

  /// The key on the QR square, so a test can tell which payload it carries.
  static Key qrKey(String url) => ValueKey('url-qr:$url');

  @override
  Widget build(BuildContext context) {
    final valid =
        QrValidator.validate(
          data: url,
          version: QrVersions.auto,
          errorCorrectionLevel: QrErrorCorrectLevel.M,
        ).status ==
        QrValidationStatus.valid;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          color: Colors.white,
          padding: const EdgeInsets.all(12),
          child: SizedBox(
            key: qrKey(url),
            width: size,
            height: size,
            child: valid
                ? QrImageView(
                    data: url,
                    size: size,
                    padding: EdgeInsets.zero,
                    version: QrVersions.auto,
                    errorCorrectionLevel: QrErrorCorrectLevel.M,
                    backgroundColor: Colors.white,
                    eyeStyle: const QrEyeStyle(
                      eyeShape: QrEyeShape.square,
                      color: Colors.black,
                    ),
                    dataModuleStyle: const QrDataModuleStyle(
                      dataModuleShape: QrDataModuleShape.square,
                      color: Colors.black,
                    ),
                    semanticsLabel: url,
                    errorStateBuilder: (_, _) => _fallback(),
                  )
                : _fallback(),
          ),
        ),
        const SizedBox(height: 8),
        ConstrainedBox(
          constraints: BoxConstraints(maxWidth: size < 340 ? 400 : size + 60),
          child: SelectableText(
            url,
            textAlign: TextAlign.center,
            style: T.text(
              size: textSize,
              weight: FontWeight.w700,
              color: textColor ?? T.textPrimary,
            ),
          ),
        ),
      ],
    );
  }

  Widget _fallback() => Center(
    child: Padding(
      padding: const EdgeInsets.all(12),
      child: Text(
        url,
        style: const TextStyle(color: Colors.black),
        textAlign: TextAlign.center,
      ),
    ),
  );
}
