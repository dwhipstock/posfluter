import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';

/// Disk + memory cache for item photos.
///
/// Photo URLs carry the server's photoVersion (?v=), so a cached entry is
/// immutable — a replaced photo arrives under a new URL and the old entry just
/// ages out. That makes a long stalePeriod safe, and it is exactly what
/// offline-first needs: a terminal that loses the network still paints its
/// menu grid from disk instead of refetching ~60 images per app start.
class ItemPhotoCache {
  ItemPhotoCache._();

  static final CacheManager manager = CacheManager(
    Config(
      'itemPhotos',
      stalePeriod: const Duration(days: 365),
      maxNrOfCacheObjects: 400,
    ),
  );
}

/// An item photo tile: cached (memory + disk), decoded at display size, with
/// the abbrev badge as both the no-photo and load-failure fallback.
class ItemPhoto extends StatelessWidget {
  final Item item;

  /// Server downscale bucket AND decode width. The originals are ~1000px;
  /// decoding one costs a ~4MB texture, and sixty of those thrash the
  /// framework's 100MB image cache — this keeps both transfer and decode at
  /// tile size.
  final int width;
  final BoxFit fit;

  /// Rendered when the item has no photo or it fails to load.
  final Widget fallback;

  const ItemPhoto(
    this.item, {
    super.key,
    required this.width,
    required this.fallback,
    this.fit = BoxFit.cover,
  });

  @override
  Widget build(BuildContext context) {
    final url = item.photoUrl(width: width);
    if (url == null) {
      return ColoredBox(color: T.surfaceAlt, child: fallback);
    }
    return CachedNetworkImage(
      imageUrl: url,
      cacheManager: ItemPhotoCache.manager,
      httpHeaders: Api.mediaHeaders,
      fit: fit,
      memCacheWidth:
          width, // belt-and-braces: also right-sizes originals from an old server
      fadeInDuration:
          Duration.zero, // cached tiles must paint instantly, no fade churn
      placeholder: (_, _) => const ColoredBox(color: T.surfaceAlt),
      errorWidget: (_, _, _) =>
          ColoredBox(color: T.surfaceAlt, child: fallback),
    );
  }
}

/// Standard grid-tile fallback: the abbrev badge, centered.
class AbbrevFallback extends StatelessWidget {
  final String abbrev;
  final double size;
  const AbbrevFallback(this.abbrev, {super.key, this.size = 48});

  @override
  Widget build(BuildContext context) =>
      Center(child: AbbrevSquare(abbrev, size: size));
}
