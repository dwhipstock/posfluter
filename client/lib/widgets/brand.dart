import 'package:flutter/material.dart';

import '../design/tokens.dart';

/// The Copper Lantern Pub badge (bundled asset, transparent corners).
/// [ring] sets it on a cream disc so it stays legible on the navy bands.
class BrandLogo extends StatelessWidget {
  final double size;
  final bool ring;
  const BrandLogo({super.key, required this.size, this.ring = false});

  static const asset = 'assets/copper_lantern_logo.png';

  @override
  Widget build(BuildContext context) {
    final image = Image.asset(
      asset,
      width: size,
      height: size,
      fit: BoxFit.contain,
      filterQuality: FilterQuality.medium,
      semanticLabel: 'The Copper Lantern Pub',
    );
    if (!ring) return image;
    final pad = (size * .045).clamp(2.0, 12.0);
    return Container(
      padding: EdgeInsets.all(pad),
      decoration: BoxDecoration(
        color: T.background,
        shape: BoxShape.circle,
        boxShadow: size > 80 ? T.badgeShadow : null,
      ),
      child: image,
    );
  }
}
