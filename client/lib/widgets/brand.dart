import 'package:flutter/material.dart';

import '../api.dart';
import '../design/tokens.dart';

/// The store's badge (bundled asset, transparent corners): the Copper
/// Lantern Pub's, or Sage & Poppy Bottle Shop's when the store says so.
/// [ring] sets it on a cream disc so it stays legible on the dark bands.
class BrandLogo extends StatelessWidget {
  final double size;
  final bool ring;
  const BrandLogo({super.key, required this.size, this.ring = false});

  static const asset = 'assets/copper_lantern_logo.png';
  static const sagePoppyAsset = 'assets/sage_poppy_mark.png';
  static const pronghornAsset = 'assets/pronghorn_mark.png';

  @override
  Widget build(BuildContext context) {
    final profile = StoreProfile.current;
    final sagePoppy = profile.isSagePoppy;
    final image = Image.asset(
      profile.isPronghorn
          ? pronghornAsset
          : sagePoppy
          ? sagePoppyAsset
          : asset,
      width: size,
      height: size,
      fit: BoxFit.contain,
      filterQuality: FilterQuality.medium,
      semanticLabel: profile.isPronghorn
          ? 'Pronghorn Fuel & Market'
          : sagePoppy
          ? 'Sage & Poppy Bottle Shop'
          : 'The Copper Lantern Pub',
    );
    // the Pronghorn badge is its own dark rounded square: no disc
    if (profile.isPronghorn) return image;
    if (!ring) return image;
    final pad = (size * .045).clamp(2.0, 12.0);
    return Container(
      padding: EdgeInsets.all(pad),
      decoration: BoxDecoration(
        color: sagePoppy ? const Color(0xFFFBF7EF) : T.background,
        shape: BoxShape.circle,
        boxShadow: size > 80 ? T.badgeShadow : null,
      ),
      child: image,
    );
  }
}
