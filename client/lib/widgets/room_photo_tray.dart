import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';

/// "Set up from picture": the manager gathers 1–[max] pictures of ONE room
/// (other corners, other angles) — take one, take another, pick files on
/// Windows or the gallery on Android, remove one — then "Set up room" sends
/// them all in one request. One photo is just: take it, Set up room.
/// Pops the pictures, or null when the manager backs out.
class RoomPhotoTray extends StatefulWidget {
  /// The camera (null when the device has none): one picture, or null if cancelled.
  final Future<RoomPhoto?> Function()? takePhoto;

  /// Files / the gallery: up to [limit] pictures (empty if cancelled).
  final Future<List<RoomPhoto>> Function(int limit) pickPhotos;

  /// Files on Windows, the gallery elsewhere: only changes the button's words.
  final bool filesLabel;
  final int max;

  const RoomPhotoTray({
    super.key,
    required this.takePhoto,
    required this.pickPhotos,
    this.filesLabel = false,
    this.max = 4,
  });

  @override
  State<RoomPhotoTray> createState() => _RoomPhotoTrayState();
}

class _RoomPhotoTrayState extends State<RoomPhotoTray> {
  final _photos = <({RoomPhoto photo, Uint8List bytes})>[];
  bool _busy = false; // the camera or picker is open: no second one

  int get _left => widget.max - _photos.length;

  void _add(Iterable<RoomPhoto> photos) {
    for (final p in photos.take(_left)) {
      _photos.add((photo: p, bytes: Uint8List.fromList(p.bytes)));
    }
  }

  Future<void> _run(Future<void> Function() job) async {
    if (_busy || _left <= 0) return;
    setState(() => _busy = true);
    try {
      await job();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _take() => _run(() async {
    final p = await widget.takePhoto!();
    if (p != null && mounted) _add([p]);
  });

  Future<void> _pick() => _run(() async {
    final ps = await widget.pickPhotos(_left);
    if (mounted) _add(ps);
  });

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final full = _left <= 0;
    final has = _photos.isNotEmpty;
    return Dialog(
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: const BorderSide(color: T.border),
      ),
      child: SizedBox(
        width: 620,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 18, 24, 12),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(l.roomFromPicture, style: T.headline()),
                const SizedBox(height: 4),
                Text(l.roomPhotosHint(widget.max), style: T.small()),
                const SizedBox(height: 14),
                if (has) ...[
                  // fixed-size thumbnails: the tray fits a small landscape window
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final (i, p) in _photos.indexed)
                        SizedBox.square(
                          dimension: 96,
                          child: _Thumb(
                            key: Key('room-photo-$i'),
                            bytes: p.bytes,
                            removeLabel: l.roomPhotoRemove,
                            onRemove: _busy
                                ? null
                                : () => setState(() => _photos.removeAt(i)),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    l.roomPhotosCount(_photos.length, widget.max),
                    key: const Key('room-photo-count'),
                    style: T.small(),
                  ),
                  const SizedBox(height: 12),
                ],
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    if (widget.takePhoto != null)
                      OutlinedButton.icon(
                        key: const Key('room-photo-take'),
                        icon: const Icon(LucideIcons.camera),
                        label: Text(
                          has ? l.roomPhotoTakeAnother : l.aiTakePhoto,
                        ),
                        onPressed: _busy || full ? null : _take,
                      ),
                    OutlinedButton.icon(
                      key: const Key('room-photo-pick'),
                      icon: const Icon(LucideIcons.imagePlus),
                      label: Text(
                        widget.filesLabel
                            ? l.roomPhotoChooseFiles
                            : l.aiChooseFromGallery,
                      ),
                      onPressed: _busy || full ? null : _pick,
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: Text(l.cancel),
                    ),
                    const SizedBox(width: 8),
                    FilledButton.icon(
                      key: const Key('room-photo-go'),
                      icon: const Icon(LucideIcons.sparkles),
                      label: Text(l.roomPhotosSetUp),
                      onPressed: !has || _busy
                          ? null
                          : () => Navigator.pop(context, [
                              for (final p in _photos) p.photo,
                            ]),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  final Uint8List bytes;
  final String removeLabel;
  final VoidCallback? onRemove;
  const _Thumb({
    super.key,
    required this.bytes,
    required this.removeLabel,
    required this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: T.radiusMedium,
            border: Border.all(color: T.border),
          ),
          child: ClipRRect(
            borderRadius: T.radiusMedium,
            child: Image.memory(
              bytes,
              fit: BoxFit.cover,
              gaplessPlayback: true,
              errorBuilder: (_, _, _) => const ColoredBox(
                color: T.surfaceAlt,
                child: Icon(LucideIcons.image, color: T.textMuted),
              ),
            ),
          ),
        ),
        Positioned(
          top: 2,
          right: 2,
          child: Material(
            color: T.textPrimary.withValues(alpha: 0.75),
            shape: const CircleBorder(),
            child: IconButton(
              tooltip: removeLabel,
              iconSize: 18,
              visualDensity: VisualDensity.compact,
              color: Colors.white,
              icon: const Icon(LucideIcons.x),
              onPressed: onRemove,
            ),
          ),
        ),
      ],
    );
  }
}
