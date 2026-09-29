import 'package:flutter/material.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'floor_object_icons.dart';

/// Name + icon + shape + size for a CUSTOM floor object, made by hand or
/// pre-filled from an AI [suggestion] the manager can change before placing.
/// Pops the create body (type, labels, icon, shape, width, height) or null.
class CustomObjectDialog extends StatefulWidget {
  final RoomObjectSuggestion? suggestion;
  const CustomObjectDialog({super.key, this.suggestion});

  @override
  State<CustomObjectDialog> createState() => _CustomObjectDialogState();
}

class _CustomObjectDialogState extends State<CustomObjectDialog> {
  late final _fr = TextEditingController(text: widget.suggestion?.labelFr);
  late final _en = TextEditingController(text: widget.suggestion?.labelEn);
  late String _icon = floorObjectIcons.containsKey(widget.suggestion?.icon)
      ? widget.suggestion!.icon
      : 'star';
  late String _shape = widget.suggestion?.shape == 'ROUND' ? 'ROUND' : 'RECT';
  late double _w = (widget.suggestion?.width ?? 100).clamp(40, 400).toDouble();
  late double _h = (widget.suggestion?.height ?? 100).clamp(40, 400).toDouble();

  @override
  void dispose() {
    _fr.dispose();
    _en.dispose();
    super.dispose();
  }

  bool get _named => _fr.text.trim().isNotEmpty || _en.text.trim().isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return AlertDialog(
      title: Text(l.customObject.replaceAll('…', '')),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                key: const Key('custom-object-fr'),
                controller: _fr,
                maxLength: 64,
                decoration: InputDecoration(labelText: l.objectNameFr),
                onChanged: (_) => setState(() {}),
              ),
              TextField(
                key: const Key('custom-object-en'),
                controller: _en,
                maxLength: 64,
                decoration: InputDecoration(labelText: l.objectNameEn),
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 8),
              Text(l.objectIcon, style: T.small()),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final e in floorObjectIcons.entries)
                    IconButton(
                      key: Key('custom-object-icon-${e.key}'),
                      isSelected: _icon == e.key,
                      style: IconButton.styleFrom(
                        backgroundColor: _icon == e.key
                            ? T.surfaceAlt
                            : Colors.transparent,
                        side: BorderSide(
                          color: _icon == e.key ? T.textPrimary : T.border,
                        ),
                      ),
                      icon: Icon(e.value, size: 22),
                      onPressed: () => setState(() => _icon = e.key),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              SegmentedButton<String>(
                segments: [
                  ButtonSegment(value: 'RECT', label: Text(l.objectShapeRect)),
                  ButtonSegment(
                    value: 'ROUND',
                    label: Text(l.objectShapeRound),
                  ),
                ],
                selected: {_shape},
                onSelectionChanged: (s) => setState(() => _shape = s.first),
              ),
              const SizedBox(height: 12),
              Text(
                '${l.objectSize}: ${_w.round()} × ${_h.round()}',
                style: T.small(),
              ),
              Slider(
                value: _w,
                min: 40,
                max: 400,
                divisions: 36,
                onChanged: (v) => setState(() => _w = v),
              ),
              Slider(
                value: _h,
                min: 40,
                max: 400,
                divisions: 36,
                onChanged: (v) => setState(() => _h = v),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(l.cancel),
        ),
        FilledButton(
          key: const Key('custom-object-place'),
          onPressed: _named
              ? () => Navigator.pop(context, <String, dynamic>{
                  'type': 'CUSTOM',
                  'labelFr': _fr.text.trim(),
                  'labelEn': _en.text.trim(),
                  'icon': _icon,
                  'shape': _shape,
                  'width': _w.round(),
                  'height': _h.round(),
                })
              : null,
          child: Text(l.placeObject),
        ),
      ],
    );
  }
}
