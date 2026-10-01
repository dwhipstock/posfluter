import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show SystemChannels;
import 'package:image_picker/image_picker.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import '../widgets/ai_working.dart';
import '../widgets/custom_object_dialog.dart';
import '../widgets/floor_object_icons.dart';
import '../widgets/floor_plan.dart';
import '../widgets/mic_button.dart';
import '../widgets/room_layout_preview.dart';

/// Manager floor-plan editor. Geometry edits (drag / resize / rotate / shape /
/// seats) are LOCAL until "Save layout" does one batch write; add / delete /
/// rename hit the server immediately (they're identity, not layout). The
/// manager PIN collected at the edit-mode unlock travels with every request,
/// which re-verifies it server-side — same trust model as void/86.
class FloorPlanEditScreen extends StatefulWidget {
  final Zone zone;
  final String managerPin;

  /// null = the device microphone; tests pass a fake.
  final VoiceRecorder? recorder;

  /// null = the camera / gallery / file picker for "set up from picture";
  /// tests pass the pictures directly (null or empty = the manager backed out).
  final Future<List<RoomPhoto>?> Function()? pickRoomPhotos;
  const FloorPlanEditScreen({
    super.key,
    required this.zone,
    required this.managerPin,
    this.recorder,
    this.pickRoomPhotos,
  });

  @override
  State<FloorPlanEditScreen> createState() => _FloorPlanEditScreenState();
}

class _FloorPlanEditScreenState extends State<FloorPlanEditScreen> {
  static const _grid = 20; // snap step, logical units
  static const _shapes = ['ROUND', 'SQUARE', 'RECT', 'BAR'];

  /// Largest side a table may be resized to, by shape (the room is 1000 units):
  /// a table stays table-sized; round and square ones keep their shape.
  static int _maxSide(String shape) => switch (shape) {
    'ROUND' || 'SQUARE' => 260,
    'BAR' => 600,
    _ => 420,
  };

  late List<TableInfo> _tables = List.of(widget.zone.tables);
  // structural props (pool/bar/pillar): geometry-editable like tables, but inert
  late List<FloorObject> _objects = List.of(widget.zone.objects);
  // selection is mutually exclusive across the two layers
  String? _selectedId; // selected table
  String? _selectedObjectId; // selected object
  bool _dirty = false;
  bool _busy = false;
  // "Add from photo" rides the AI menu add-on; off/offline → disabled + a note
  AiPhotoStatus _ai = AiPhotoStatus.hidden;
  // "Set up from picture": the AI's layout, previewed as a ghost until Apply
  RoomLayoutProposal? _proposal;
  // non-null while "Ask AI" or "set up from picture" waits on the model: the
  // working card shows over the (dimmed) canvas instead of a banner. Cancel
  // just flips [_AiRequest.cancelled] so a result that arrives late is dropped.
  _AiRequest? _asking;

  // The one snackbar this screen may have up (add-from-photo's "working" one,
  // or the applied/Revert one): tracked so it can be closed explicitly before
  // showing the next one and on dispose. A Material snackbar with an action
  // does not reliably auto-dismiss on its own, and one left up after this
  // screen is popped would call back into a disposed State when tapped.
  ScaffoldFeatureController<SnackBar, SnackBarClosedReason>? _snack;
  bool _addingFromPhoto = false;

  void _closeSnack() {
    _snack?.close();
    _snack = null;
  }

  /// Show an AI proposal ("Ask AI" or "set up from picture") as the ghost
  /// preview. Any leftover applied/Revert snackbar is closed first: it would
  /// otherwise sit over the preview panel's Apply / Cancel buttons.
  void _showProposal(RoomLayoutProposal proposal) {
    _closeSnack();
    _hideKeyboard();
    setState(() {
      _proposal = proposal;
      _selectedId = null;
      _selectedObjectId = null;
    });
  }

  /// Leave AI-proposal mode completely — after Apply, Cancel, an error, a
  /// revert or leaving — for both "Ask AI" and "set up from picture": no
  /// ghost, no preview panel, no pending request, and the canvas redrawn from
  /// the room as the store has it now ([applied] shows at once, the reload
  /// then confirms it).
  Future<void> _endProposal({RoomLayoutApplyResult? applied}) async {
    _asking?.cancelled = true;
    setState(() {
      _proposal = null;
      _asking = null;
      _selectedId = null;
      _selectedObjectId = null;
      if (applied != null) {
        _tables = List.of(applied.tables);
        _objects = List.of(applied.objects);
        _dirty = false;
        _undo.clear();
      }
    });
    await _reloadRoom();
  }

  /// This room's saved tables and objects, fresh from the store. Unsaved
  /// geometry is kept (nothing AI-driven runs while the layout is dirty).
  Future<void> _reloadRoom() async {
    try {
      final zones = await Api.zones();
      final zone = zones.where((z) => z.id == widget.zone.id).firstOrNull;
      if (zone == null || !mounted || _dirty || _proposal != null) return;
      setState(() {
        _tables = List.of(zone.tables);
        _objects = List.of(zone.objects);
        _undo.clear();
      });
    } catch (_) {
      // offline: keep what the apply returned
    }
  }

  /// Drops focus AND tells the platform to put its on-screen keyboard away.
  /// FocusScope/FocusManager alone can leave a real software keyboard (e.g.
  /// on Windows touch) showing over a screen with no text field at all,
  /// because the platform text-input connection stays open until told to
  /// close — not just unfocused.
  void _hideKeyboard() {
    FocusManager.instance.primaryFocus?.unfocus();
    SystemChannels.textInput.invokeMethod('TextInput.hide');
  }

  @override
  void dispose() {
    _asking?.cancelled = true; // a late result must not land on a dead screen
    _closeSnack();
    _hideKeyboard();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    Api.menuAiStatus().then((s) {
      if (mounted) setState(() => _ai = s);
    }, onError: (_) {});
  }

  // geometry-only undo: snapshots of BOTH layers before each drag/resize/toolbar
  // tweak. Cleared on add/delete/rename — those already live on the server.
  final List<_Snapshot> _undo = [];

  // Editor toggles, hydrated from device-local prefs (Prefs.load ran at startup)
  // so they stick between edit sessions. Grid = show the dot grid (+ its dot
  // spacing); snap = snap move/resize to the 20u step. All independent.
  bool _showGrid = Prefs.instance.editorShowGrid;
  bool _snapEnabled = Prefs.instance.editorSnap;
  int _gridStep =
      Prefs.instance.editorGridStep; // visual dot spacing (logical units)

  // Grid dropdown sizes, big → small; 100 is the original (largest) spacing.
  static const _gridSizes = [100, 50, 25];

  void _selectGrid(String value) {
    setState(() {
      if (value == 'off') {
        _showGrid = false;
      } else {
        _showGrid = true;
        _gridStep = int.parse(value);
      }
    });
    Prefs.instance.setEditorShowGrid(_showGrid);
    Prefs.instance.setEditorGridStep(_gridStep);
  }

  void _toggleSnap() {
    setState(() => _snapEnabled = !_snapEnabled);
    Prefs.instance.setEditorSnap(_snapEnabled);
  }

  TableInfo? get _selected {
    for (final t in _tables) {
      if (t.id == _selectedId) return t;
    }
    return null;
  }

  FloorObject? get _selectedObject {
    for (final o in _objects) {
      if (o.id == _selectedObjectId) return o;
    }
    return null;
  }

  void _pushUndo() {
    _undo.add(_Snapshot(List.of(_tables), List.of(_objects)));
    if (_undo.length > 50) _undo.removeAt(0);
  }

  void _mutateSelected(
    TableInfo Function(TableInfo) fn, {
    bool snapshot = true,
  }) {
    final t = _selected;
    if (t == null) return;
    if (snapshot) _pushUndo();
    setState(() {
      _tables = [for (final e in _tables) e.id == t.id ? fn(e) : e];
      _dirty = true;
    });
  }

  /// A resized table, kept table-sized ([_maxSide]) and inside the room;
  /// round and square tables stay as wide as they are tall.
  TableInfo _sized(TableInfo e, int w, int h) {
    final max = _maxSide(e.shape);
    if (e.shape == 'ROUND' || e.shape == 'SQUARE') {
      final side = (w > h ? w : h)
          .clamp(
            40,
            [max, 1000 - e.x, 1000 - e.y].reduce((a, b) => a < b ? a : b),
          )
          .toInt();
      return e.copyWith(width: side, height: side);
    }
    return e.copyWith(
      width: w.clamp(40, max < 1000 - e.x ? max : 1000 - e.x).toInt(),
      height: h.clamp(40, max < 1000 - e.y ? max : 1000 - e.y).toInt(),
    );
  }

  void _mutateObject(
    FloorObject Function(FloorObject) fn, {
    bool snapshot = true,
  }) {
    final o = _selectedObject;
    if (o == null) return;
    if (snapshot) _pushUndo();
    setState(() {
      _objects = [for (final e in _objects) e.id == o.id ? fn(e) : e];
      _dirty = true;
    });
  }

  // Snap to the grid step when enabled; otherwise just settle to a whole unit
  // (positions/sizes are ints) so free placement survives the drag/resize end.
  int _snap(num v) => _snapEnabled ? (v / _grid).round() * _grid : v.round();

  Future<void> _save() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l = L.of(context);
    try {
      await Api.saveZoneLayout(widget.zone.id, [
        for (final t in _tables) t.geometryJson,
      ], widget.managerPin);
      await Api.saveZoneObjects(widget.zone.id, [
        for (final o in _objects) o.geometryJson,
      ], widget.managerPin);
      if (!mounted) return;
      setState(() {
        _dirty = false;
        _undo.clear();
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.layoutSaved)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _addTable() async {
    final l = L.of(context);
    final labelCtl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.addTable),
        content: SizedBox(
          width: 360,
          child: TextField(
            controller: labelCtl,
            autofocus: true,
            decoration: InputDecoration(
              labelText: l.tableLabelField,
              // labels are server-enforced to the zone prefix; blank = next free
              helperText: l.autoLabelHint(widget.zone.labelPrefix),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.addTable),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      // drop it near the canvas center, nudged so repeated adds don't stack
      final nudge = (_tables.length % 5) * 30;
      // blank label → server auto-assigns the next "{prefix}-{n}"; a typed one is
      // coerced to the zone prefix server-side either way.
      final typed = labelCtl.text.trim();
      final created = await Api.addTable(widget.zone.id, {
        if (typed.isNotEmpty) 'label': typed,
        'x': 420 + nudge,
        'y': 420 + nudge,
      }, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _tables = [..._tables, created];
        _selectedId = created.id;
        _undo
            .clear(); // snapshots from before the add would resurrect stale state
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _deleteSelected() async {
    final t = _selected;
    if (t == null) return;
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.deleteTable),
        content: Text(l.deleteTableConfirm(t.displayLabel)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: T.destructive,
              foregroundColor: T.onDestructive,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.deleteTable),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await Api.deleteTable(t.id, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _tables = [
          for (final e in _tables)
            if (e.id != t.id) e,
        ];
        _selectedId = null;
        _undo.clear();
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.tableDeleted)));
    } catch (e) {
      // table_in_use / has_sub_tables come back as clear localized errors
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _renameSelected() async {
    final t = _selected;
    if (t == null) return;
    final l = L.of(context);
    final labelCtl = TextEditingController(text: t.label);
    final vipCtl = TextEditingController(text: t.nameOverride ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.renameTable),
        content: SizedBox(
          width: 360,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: labelCtl,
                autofocus: true,
                decoration: InputDecoration(labelText: l.tableLabelField),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: vipCtl,
                decoration: InputDecoration(labelText: l.vipNameField),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.save),
          ),
        ],
      ),
    );
    if (ok != true || labelCtl.text.trim().isEmpty || !mounted) return;
    try {
      // empty VIP field = clear the override (server treats "" as clear)
      final updated = await Api.renameTable(
        t.id,
        label: labelCtl.text.trim(),
        nameOverride: vipCtl.text.trim(),
        managerPin: widget.managerPin,
      );
      if (!mounted) return;
      setState(() {
        _tables = [
          for (final e in _tables)
            e.id == t.id
                ? e.copyWith(
                    label: updated.label,
                    nameOverride: updated.nameOverride,
                    clearNameOverride: updated.nameOverride == null,
                  )
                : e,
        ];
        _undo.clear();
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// Type-appropriate default footprint (logical units) for a fresh object.
  (int, int) _defaultObjectSize(String type) => switch (type) {
    'BAR_FRONT' => (320, 60), // long, shallow counter
    'POOL' => (200, 120), // a plain table slab
    'ENTRANCE' || 'KITCHEN' => (120, 40), // a doorway / the pass
    'HOST_STAND' => (80, 60),
    'RESTROOMS' => (120, 80),
    'STAGE' => (240, 140),
    _ => (80, 80), // PILLAR — a small block
  };

  /// Drop a structural prop near the canvas centre. Like _addTable, add hits the
  /// server immediately (identity); geometry then edits locally until save.
  Future<void> _addObject(String type, [Map<String, dynamic>? custom]) async {
    if (type == 'CUSTOM' && custom == null) return _addCustom();
    if (type == 'PHOTO') return _addFromPhoto();
    try {
      final nudge = ((_tables.length + _objects.length) % 5) * 30;
      var (w, h) = _defaultObjectSize(type);
      if (custom != null) (w, h) = (custom['width'], custom['height']);
      final created = await Api.addObject(widget.zone.id, {
        'type': type,
        ...?custom,
        'x': (400 + nudge).clamp(0, 1000 - w),
        'y': (400 + nudge).clamp(0, 1000 - h),
        'width': w,
        'height': h,
      }, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _objects = [..._objects, created];
        _selectedObjectId = created.id;
        _selectedId = null;
        _undo
            .clear(); // snapshots from before the add would resurrect stale state
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// A CUSTOM object by hand (name + icon + shape), or from an AI [suggestion].
  Future<void> _addCustom([RoomObjectSuggestion? suggestion]) async {
    final body = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => CustomObjectDialog(suggestion: suggestion),
    );
    if (body != null && mounted) await _addObject('CUSTOM', body);
  }

  /// "Add from photo": camera or a picked file (Windows) → the AI suggests a
  /// name / icon / shape / size, the manager edits it, then places it. The
  /// photo is sent once and kept nowhere.
  Future<void> _addFromPhoto() async {
    if (_addingFromPhoto) return; // a double tap must not fire this twice
    _addingFromPhoto = true;
    try {
      await _addFromPhotoImpl();
    } finally {
      _addingFromPhoto = false;
    }
  }

  Future<void> _addFromPhotoImpl() async {
    final l = L.of(context);
    final picker = ImagePicker();
    final source = picker.supportsImageSource(ImageSource.camera)
        ? await showDialog<ImageSource>(
            context: context,
            builder: (context) => SimpleDialog(
              title: Text(l.objectFromPhoto),
              children: [
                SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, ImageSource.camera),
                  child: Text(l.aiTakePhoto),
                ),
                SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, ImageSource.gallery),
                  child: Text(l.aiChooseFromGallery),
                ),
              ],
            ),
          )
        : ImageSource.gallery;
    if (source == null || !mounted) return;
    final picked = await picker.pickImage(
      source: source,
      maxWidth: 1600,
      maxHeight: 1600,
      imageQuality: 85,
    );
    if (picked == null || !mounted) return;
    _closeSnack(); // never queue behind a leftover applied/Revert snackbar
    _snack = ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(l.objectFromPhotoWorking),
        duration: const Duration(minutes: 3),
      ),
    );
    RoomObjectSuggestion suggestion;
    try {
      suggestion = await Api.suggestRoomObject(
        await picked.readAsBytes(),
        picked.name.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg',
        widget.managerPin,
      );
    } catch (e) {
      _closeSnack();
      if (mounted) showApiError(context, e);
      return;
    }
    _closeSnack();
    if (mounted) await _addCustom(suggestion);
  }

  /// "Set up from picture": 1–4 pictures (camera, gallery, or a picked file
  /// on Windows) → the AI's layout, previewed as a ghost over the room.
  Future<void> _setUpFromPicture() async {
    final l = L.of(context);
    if (_dirty) await _save(); // the preview draws the room as saved
    if (_dirty || !mounted) return;
    final photos = await (widget.pickRoomPhotos ?? _pickRoomPhotos)();
    if (photos == null || photos.isEmpty || !mounted) return;
    _closeSnack(); // never leave a Revert snackbar over the card or panel
    // several pictures for the model to read: the slowest of the AI asks
    final req = _AiRequest(const Duration(seconds: 40));
    setState(() => _asking = req);
    RoomLayoutProposal proposal;
    try {
      proposal = await Api.roomLayoutFromPhotos(
        widget.zone.id,
        photos,
        widget.managerPin,
      );
    } catch (e) {
      if (req.cancelled || !mounted) return;
      setState(() => _asking = null);
      showApiError(context, e);
      return;
    }
    if (req.cancelled || !mounted) return;
    setState(() => _asking = null);
    if (proposal.refusal != null) {
      // the store's fixed reply, never the model's words
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(l.roomFromPicture),
          content: Text(proposal.message ?? ''),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('OK'),
            ),
          ],
        ),
      );
      return;
    }
    if (!mounted) return;
    _showProposal(proposal);
  }

  /// 1–4 room pictures from the camera, gallery or a picked file.
  Future<List<RoomPhoto>?> _pickRoomPhotos() async {
    final l = L.of(context);
    final picker = ImagePicker();
    final source = picker.supportsImageSource(ImageSource.camera)
        ? await showDialog<ImageSource>(
            context: context,
            builder: (context) => SimpleDialog(
              title: Text(l.roomFromPicture),
              children: [
                SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, ImageSource.camera),
                  child: Text(l.aiTakePhoto),
                ),
                SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, ImageSource.gallery),
                  child: Text(l.aiChooseFromGallery),
                ),
              ],
            ),
          )
        : ImageSource.gallery;
    if (source == null || !mounted) return null;
    final files = source == ImageSource.camera
        ? [
            ?await picker.pickImage(
              source: source,
              maxWidth: 2048,
              maxHeight: 2048,
              imageQuality: 88,
            ),
          ]
        : await picker.pickMultiImage(
            maxWidth: 2048,
            maxHeight: 2048,
            imageQuality: 88,
            limit: 4,
          );
    if (files.isEmpty || !mounted) return null;
    final photos = [
      for (final f in files.take(4))
        (
          bytes: await f.readAsBytes(),
          contentType: f.name.toLowerCase().endsWith('.png')
              ? 'image/png'
              : 'image/jpeg',
        ),
    ];
    return photos;
  }

  /// Cancel on the AI working card: the request keeps running (nothing to
  /// abort it with), but its result — or error — is dropped when it arrives.
  void _cancelAsking() {
    _asking?.cancelled = true;
    setState(() => _asking = null);
  }

  /// Floor-plan "Ask AI": type or say a change to THIS room ("add four
  /// 2-tops along the window", "remove the pool table") → the changes,
  /// previewed as a ghost with a list, then Apply (revertable) or Cancel.
  Future<void> _askAi() async {
    final l = L.of(context);
    if (_dirty) await _save(); // the assistant edits the room as saved
    if (_dirty || !mounted) return;
    final text = TextEditingController();
    final ask = await showDialog<({String? text, VoiceClip? clip})>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.floorAskAi),
        content: SizedBox(
          width: 520,
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  key: const Key('floor-ai-text'),
                  controller: text,
                  // no autofocus: on a touch Surface it pops the on-screen
                  // keyboard over the canvas before the manager asked for it
                  minLines: 1,
                  maxLines: 3,
                  textInputAction: TextInputAction.send,
                  onSubmitted: (v) => v.trim().isEmpty
                      ? null
                      : Navigator.pop(ctx, (text: v.trim(), clip: null)),
                  decoration: InputDecoration(hintText: l.floorAskAiHint),
                ),
              ),
              const SizedBox(width: 8),
              MicButton(
                key: const Key('floor-ai-mic'),
                recorder: widget.recorder,
                onClip: (clip) async =>
                    Navigator.pop(ctx, (text: null, clip: clip)),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l.cancel),
          ),
          FilledButton(
            key: const Key('floor-ai-ask'),
            onPressed: () => text.text.trim().isEmpty
                ? null
                : Navigator.pop(ctx, (text: text.text.trim(), clip: null)),
            child: Text(l.aiMenuAsk),
          ),
        ],
      ),
    );
    // not text.dispose() here: the dialog's exit transition can still be
    // animating a frame or two after showDialog resolves (as the other
    // dialogs in this screen already assume by never disposing their own
    // short-lived controllers either)
    if (ask == null || !mounted) return;
    // sent (text or voice): drop focus so the on-screen keyboard doesn't sit
    // over the canvas/panel while the request runs, or once the result shows
    _hideKeyboard();
    _closeSnack(); // never leave a Revert snackbar over the card or panel
    final req = _AiRequest(const Duration(seconds: 15));
    setState(() => _asking = req);
    RoomLayoutProposal proposal;
    try {
      final clip = ask.clip;
      proposal = clip != null
          ? await Api.floorEditVoice(
              widget.zone.id,
              clip.bytes,
              clip.contentType,
              widget.managerPin,
            )
          : await Api.floorEdit(widget.zone.id, ask.text!, widget.managerPin);
    } catch (e) {
      if (req.cancelled || !mounted) return;
      setState(() => _asking = null);
      showApiError(context, e);
      return;
    }
    if (req.cancelled || !mounted) return;
    setState(() => _asking = null);
    if (proposal.refusal != null) {
      // the store's fixed reply, never the model's words
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(l.floorAskAi),
          content: Text(
            [
              if ((proposal.transcript ?? '').isNotEmpty)
                l.aiHeard(proposal.transcript!),
              proposal.message ?? '',
            ].join('\n\n'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('OK'),
            ),
          ],
        ),
      );
      return;
    }
    if (!mounted) return;
    _showProposal(proposal);
  }

  Future<void> _applyRoom(
    String mode,
    List<TableInfo> tables,
    List<FloorObject> objects,
  ) async {
    final p = _proposal;
    if (p == null) return;
    final l = L.of(context);
    // many removals ("remove every table"): one more explicit yes
    if (p.isEdit && p.bulk) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          content: Text(
            l.floorEditBulkConfirm(
              p.removedTables.length + p.removedObjects.length,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(l.cancel),
            ),
            FilledButton(
              key: const Key('floor-edit-bulk-confirm'),
              onPressed: () => Navigator.pop(context, true),
              child: Text(l.floorEditApplyAnyway),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    try {
      final r = p.isEdit
          ? await Api.floorEditApply(
              widget.zone.id,
              p.proposalId,
              widget.managerPin,
              confirmed: p.bulk,
            )
          : await Api.roomLayoutApply(
              widget.zone.id,
              p.proposalId,
              mode,
              tables,
              objects,
              widget.managerPin,
            );
      if (!mounted) return;
      _closeSnack();
      unawaited(_endProposal(applied: r));
      _snack = ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            p.isEdit ? l.floorEditApplied(r.added) : l.roomApplied(r.added),
          ),
          // a snackbar with an action can otherwise sit forever (Flutter
          // 3.44) and block the next one from ever showing
          duration: const Duration(seconds: 8),
          action: SnackBarAction(
            label: l.aiMenuRevert,
            onPressed: () {
              _closeSnack();
              _revertRoom(r.changeSetId);
            },
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      showApiError(context, e);
      // expired / already applied / refused: out of preview, the room as saved
      await _endProposal();
    }
  }

  /// Put a "set up from picture" back: the tables it removed come back, the
  /// ones it added go (the store refuses while one has an open bill).
  Future<void> _revertRoom(String setId, {bool force = false}) async {
    final l = L.of(context);
    try {
      await Api.menuAiRevert(setId, widget.managerPin, force: force);
      final zones = await Api.zones();
      final zone = zones.where((z) => z.id == widget.zone.id).firstOrNull;
      if (!mounted) return;
      _asking?.cancelled = true;
      setState(() {
        // the room as the store has it now, and never a stale preview over it
        _proposal = null;
        _asking = null;
        _selectedId = null;
        _selectedObjectId = null;
        if (zone != null) {
          _tables = List.of(zone.tables);
          _objects = List.of(zone.objects);
        }
        _dirty = false;
        _undo.clear();
      });
      _closeSnack();
      _snack = ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.aiMenuRevertDone)));
    } on MenuRevertConflict catch (c) {
      if (!mounted) return;
      final again = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          content: Text(l.aiMenuRevertConflict(c.titles.join(', '))),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(l.cancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(l.aiMenuRevertAnyway),
            ),
          ],
        ),
      );
      if (again == true) await _revertRoom(setId, force: true);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  static String _when(DateTime t) =>
      '${t.year}-${t.month.toString().padLeft(2, '0')}-${t.day.toString().padLeft(2, '0')} '
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  /// The applied "set up from picture" changes, each with Revert.
  Future<void> _roomHistory() async {
    final l = L.of(context);
    List<MenuChangeSet> sets;
    try {
      sets = await Api.roomLayoutHistory();
    } catch (e) {
      if (mounted) showApiError(context, e);
      return;
    }
    if (!mounted) return;
    final setId = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.roomLayoutHistory),
        content: SizedBox(
          width: 480,
          child: sets.isEmpty
              ? Text(l.aiMenuNoHistory)
              : ListView(
                  shrinkWrap: true,
                  children: [
                    for (final s in sets)
                      ListTile(
                        title: Text(s.summary),
                        subtitle: Text(
                          [
                            s.appliedBy,
                            if (s.createdAt != null) _when(s.createdAt!),
                          ].join(' · '),
                        ),
                        trailing: s.reverted
                            ? Text(l.aiMenuReverted, style: T.small())
                            : TextButton(
                                onPressed: () => Navigator.pop(ctx, s.id),
                                child: Text(l.aiMenuRevert),
                              ),
                      ),
                  ],
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l.cancel),
          ),
        ],
      ),
    );
    if (setId != null && mounted) await _revertRoom(setId);
  }

  Future<void> _deleteSelectedObject() async {
    final o = _selectedObject;
    if (o == null) return;
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.deleteObject),
        content: Text(l.deleteObjectConfirm(_objectName(o, l))),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: T.destructive,
              foregroundColor: T.onDestructive,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.deleteObject),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await Api.deleteObject(o.id, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _objects = [
          for (final e in _objects)
            if (e.id != o.id) e,
        ];
        _selectedObjectId = null;
        _undo.clear();
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.objectDeleted)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  String _objectName(FloorObject o, L l) => switch (o.type) {
    'POOL' => l.objectPool,
    'BAR_FRONT' => l.objectBarFront,
    'PILLAR' => l.objectPillar,
    _ =>
      l.objectTypeName(o.type) ??
          l.name(
            o.labelFr ?? o.labelEn ?? '',
            o.labelEn ?? o.labelFr ?? '',
            o.names,
          ),
  };

  void _undoLast() {
    if (_undo.isEmpty) return;
    final s = _undo.removeLast();
    setState(() {
      _tables = s.tables;
      _objects = s.objects;
      _dirty = true;
    });
  }

  Future<void> _confirmLeave(bool didPop, Object? result) async {
    if (didPop) return;
    if (_proposal != null) return _endProposal();
    if (_asking != null) return _cancelAsking();
    final l = L.of(context);
    final leave = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.unsavedLayoutTitle),
        content: Text(l.unsavedLayoutBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(
              l.discard,
              style: const TextStyle(color: T.destructive),
            ),
          ),
          FilledButton(
            onPressed: () async {
              await _save();
              if (ctx.mounted) Navigator.pop(ctx, !_dirty);
            },
            child: Text(l.saveLayout),
          ),
        ],
      ),
    );
    if (leave == true && mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final selected = _selected;
    return PopScope(
      canPop: !_dirty && _proposal == null && _asking == null,
      onPopInvokedWithResult: _confirmLeave,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            l.editLayoutTitle(
              l.name(widget.zone.nameFr, widget.zone.nameEn, widget.zone.names),
            ),
          ),
          actions: _proposal != null || _asking != null
              ? null
              : [
                  // independent editor controls: dot-grid dropdown (off + 3 sizes) +
                  // snap-to-grid toggle. accent when on, muted when off.
                  _gridMenu(l),
                  _toggleAction(
                    LucideIcons.magnet,
                    l.editorSnap,
                    _snapEnabled,
                    _toggleSnap,
                  ),
                  IconButton(
                    icon: const Icon(LucideIcons.undo2),
                    tooltip: l.undo,
                    onPressed: _undo.isEmpty ? null : _undoLast,
                  ),
                  IconButton(
                    icon: const Icon(LucideIcons.plus),
                    tooltip: l.addTable,
                    onPressed: _addTable,
                  ),
                  // palette: drop a structural prop, a custom one, or one from a photo
                  PopupMenuButton<String>(
                    icon: const Icon(LucideIcons.shapes),
                    tooltip: l.addObject,
                    onSelected: _addObject,
                    itemBuilder: (ctx) => [
                      _objectMenuItem(
                        'POOL',
                        LucideIcons.circleDot,
                        l.objectPool,
                      ),
                      _objectMenuItem(
                        'BAR_FRONT',
                        LucideIcons.wine,
                        l.objectBarFront,
                      ),
                      _objectMenuItem(
                        'PILLAR',
                        LucideIcons.columns2,
                        l.objectPillar,
                      ),
                      for (final type in const [
                        'ENTRANCE',
                        'HOST_STAND',
                        'KITCHEN',
                        'RESTROOMS',
                        'STAGE',
                      ])
                        _objectMenuItem(
                          type,
                          floorObjectTypeIcon(type, null)!,
                          l.objectTypeName(type)!,
                        ),
                      const PopupMenuDivider(),
                      _objectMenuItem(
                        'CUSTOM',
                        LucideIcons.pencil,
                        l.customObject,
                      ),
                      // hidden when the store has no AI menu route at all
                      if (_ai.configured || _ai.available)
                        _objectMenuItem(
                          'PHOTO',
                          LucideIcons.camera,
                          l.objectFromPhoto,
                          enabled: _ai.available,
                          note: _ai.available ? null : l.objectFromPhotoOffNote,
                        ),
                    ],
                  ),
                  // "Set up from picture" + its history; hidden without the AI add-on
                  if (_ai.configured || _ai.available)
                    PopupMenuButton<String>(
                      key: const Key('room-ai-menu'),
                      icon: const Icon(LucideIcons.sparkles),
                      tooltip: l.roomFromPicture,
                      onSelected: (v) => switch (v) {
                        'history' => _roomHistory(),
                        'ASK' => _askAi(),
                        _ => _setUpFromPicture(),
                      },
                      itemBuilder: (ctx) => [
                        _objectMenuItem(
                          'ASK',
                          LucideIcons.messageCircle,
                          l.floorAskAi,
                          enabled: _ai.available,
                          note: _ai.available ? null : l.roomFromPictureOffNote,
                        ),
                        _objectMenuItem(
                          'ROOM_PHOTO',
                          LucideIcons.imagePlus,
                          l.roomFromPicture,
                          enabled: _ai.available,
                          note: _ai.available ? null : l.roomFromPictureOffNote,
                        ),
                        _objectMenuItem(
                          'history',
                          LucideIcons.history,
                          l.roomLayoutHistory,
                        ),
                      ],
                    ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    child: FilledButton.icon(
                      icon: const Icon(LucideIcons.check, size: 18),
                      label: Text(l.saveLayout),
                      onPressed: _dirty && !_busy ? _save : null,
                    ),
                  ),
                ],
        ),
        body: _proposal != null
            ? RoomLayoutPreview(
                // a new proposal never inherits the last one's ghost/mode
                key: ValueKey(_proposal!.proposalId),
                existingTables: _tables,
                existingObjects: _objects,
                proposal: _proposal!,
                onApply: _applyRoom,
                onCancel: _endProposal,
              )
            : AiWorkingOverlay(
                active: _asking != null,
                expected: _asking?.expected ?? const Duration(seconds: 15),
                onCancel: _cancelAsking,
                child: Column(
                  children: [
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
                        child: _canvas(l),
                      ),
                    ),
                    _toolbar(selected, _selectedObject, l),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _canvas(L l) {
    return FloorPlanViewport(
      interactive: false, // drags own the gesture arena in edit mode
      builder: (scale) => [
        // faint dot grid — the visual for snap-to-grid
        Positioned.fill(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => setState(() {
              _selectedId = null;
              _selectedObjectId = null;
            }),
            // keep the full-bleed tap target for deselect; drop only the dots
            child: CustomPaint(
              painter: _showGrid
                  ? _GridPainter(scale: scale, step: _gridStep)
                  : null,
            ),
          ),
        ),
        if (_tables.isEmpty && _objects.isEmpty)
          Center(
            child: Text(
              l.emptyZoneOnboarding,
              style: T.small(),
              textAlign: TextAlign.center,
            ),
          ),
        // structural props sit beneath the tables, same as service mode
        for (final o in _objects)
          placedObject(o, scale, child: _editableObject(o, scale)),
        for (final t in _tables)
          placedTable(t, scale, child: _editableTable(t, scale)),
      ],
    );
  }

  Widget _editableObject(FloorObject o, double scale) {
    final isSelected = o.id == _selectedObjectId;
    void select() => setState(() {
      _selectedObjectId = o.id;
      _selectedId = null;
    });
    return GestureDetector(
      onTap: select,
      onPanStart: (_) {
        _pushUndo();
        select();
      },
      onPanUpdate: (d) => _mutateObject(
        snapshot: false,
        (e) => e.copyWith(
          x: (e.x + d.delta.dx / scale)
              .round()
              .clamp(0, 1000 - e.width)
              .toInt(),
          y: (e.y + d.delta.dy / scale)
              .round()
              .clamp(0, 1000 - e.height)
              .toInt(),
        ),
      ),
      onPanEnd: (_) => _mutateObject(
        snapshot: false,
        (e) => e.copyWith(
          x: _snap(e.x).clamp(0, 1000 - e.width).toInt(),
          y: _snap(e.y).clamp(0, 1000 - e.height).toInt(),
        ),
      ),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: FloorObjectShape(
              object: o,
              scale: scale,
              selected: isSelected,
            ),
          ),
          if (isSelected)
            Positioned(
              // inside the corner: a child's part outside its parent can't be
              // hit, so the old off-corner handle was a tiny target for a finger
              right: 0,
              bottom: 0,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                dragStartBehavior: DragStartBehavior.down,
                onPanStart: (_) => _pushUndo(),
                onPanUpdate: (d) => _mutateObject(
                  snapshot: false,
                  (e) => e.copyWith(
                    width: (e.width + d.delta.dx / scale)
                        .round()
                        .clamp(40, 1000 - e.x)
                        .toInt(),
                    height: (e.height + d.delta.dy / scale)
                        .round()
                        .clamp(40, 1000 - e.y)
                        .toInt(),
                  ),
                ),
                onPanEnd: (_) => _mutateObject(
                  snapshot: false,
                  (e) => e.copyWith(
                    width: _snap(e.width).clamp(40, 1000 - e.x).toInt(),
                    height: _snap(e.height).clamp(40, 1000 - e.y).toInt(),
                  ),
                ),
                child: SizedBox(
                  width: 44,
                  height: 44,
                  child: Align(
                    alignment: Alignment.bottomRight,
                    child: Container(
                      width: 30,
                      height: 30,
                      decoration: BoxDecoration(
                        color: T.surfaceAlt,
                        borderRadius: T.radiusSmall,
                        border: Border.all(color: T.textPrimary, width: 2),
                      ),
                      child: const Icon(
                        LucideIcons.moveDiagonal2,
                        size: 16,
                        color: T.textPrimary,
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _editableTable(TableInfo t, double scale) {
    final isSelected = t.id == _selectedId;
    void select() => setState(() {
      _selectedId = t.id;
      _selectedObjectId = null;
    });
    return GestureDetector(
      onTap: select,
      onPanStart: (_) {
        _pushUndo();
        select();
      },
      onPanUpdate: (d) => _mutateSelected(
        snapshot: false,
        (e) => e.copyWith(
          x: (e.x + d.delta.dx / scale)
              .round()
              .clamp(0, 1000 - e.width)
              .toInt(),
          y: (e.y + d.delta.dy / scale)
              .round()
              .clamp(0, 1000 - e.height)
              .toInt(),
        ),
      ),
      onPanEnd: (_) => _mutateSelected(
        snapshot: false,
        (e) => e.copyWith(
          x: _snap(e.x).clamp(0, 1000 - e.width).toInt(),
          y: _snap(e.y).clamp(0, 1000 - e.height).toInt(),
        ),
      ),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: TableShape(table: t, scale: scale, selected: isSelected),
          ),
          // resize handle: bottom-right corner of the selected table.
          // TODO: deltas are screen-axis, so resizing a rotated table drifts.
          if (isSelected)
            Positioned(
              // inside the corner: a child's part outside its parent can't be
              // hit, so the old off-corner handle was a tiny target for a finger
              right: 0,
              bottom: 0,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                dragStartBehavior: DragStartBehavior.down,
                onPanStart: (_) => _pushUndo(),
                onPanUpdate: (d) => _mutateSelected(
                  snapshot: false,
                  (e) => _sized(
                    e,
                    (e.width + d.delta.dx / scale).round(),
                    (e.height + d.delta.dy / scale).round(),
                  ),
                ),
                onPanEnd: (_) => _mutateSelected(
                  snapshot: false,
                  (e) => _sized(e, _snap(e.width), _snap(e.height)),
                ),
                child: SizedBox(
                  width: 44,
                  height: 44,
                  child: Align(
                    alignment: Alignment.bottomRight,
                    child: Container(
                      width: 30,
                      height: 30,
                      decoration: BoxDecoration(
                        color: T.surfaceAlt,
                        borderRadius: T.radiusSmall,
                        border: Border.all(color: T.textPrimary, width: 2),
                      ),
                      child: const Icon(
                        LucideIcons.moveDiagonal2,
                        size: 16,
                        color: T.textPrimary,
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// Bottom toolbar for the selected table: shape / rotate / seats / rename /
  /// delete. Hidden (hint shown) while nothing is selected.
  Widget _toolbar(TableInfo? t, FloorObject? o, L l) {
    final Widget body;
    if (o != null) {
      body = _objectToolbar(o, l);
    } else if (t != null) {
      body = _tableToolbar(t, l);
    } else {
      body = SizedBox(
        height: T.minTouch,
        child: Center(child: Text(l.tapTableToEditHint, style: T.small())),
      );
    }
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: T.surface,
        border: Border(top: BorderSide(color: T.border)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: body,
    );
  }

  /// Object controls: rotate + delete. No shape/seats — objects are inert.
  Widget _objectToolbar(FloorObject o, L l) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          Text(_objectName(o, l), style: T.headline()),
          const SizedBox(width: 16),
          OutlinedButton.icon(
            icon: const Icon(LucideIcons.rotateCw, size: 18),
            label: Text('${o.rotation}°'),
            onPressed: () => _mutateObject(
              (e) => e.copyWith(rotation: (e.rotation + 45) % 360),
            ),
          ),
          const SizedBox(width: 12),
          OutlinedButton.icon(
            icon: const Icon(
              LucideIcons.trash2,
              size: 18,
              color: T.destructive,
            ),
            label: Text(
              l.deleteObject,
              style: const TextStyle(color: T.destructive),
            ),
            onPressed: _deleteSelectedObject,
          ),
        ],
      ),
    );
  }

  /// Bottom toolbar for the selected table: shape / rotate / seats / rename /
  /// delete.
  Widget _tableToolbar(TableInfo t, L l) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          Text(t.displayLabel, style: T.headline()),
          const SizedBox(width: 16),
          // shape picker
          for (final s in _shapes) ...[
            _shapeButton(s, active: t.shape == s),
            const SizedBox(width: 6),
          ],
          const SizedBox(width: 12),
          OutlinedButton.icon(
            icon: const Icon(LucideIcons.rotateCw, size: 18),
            label: Text('${t.rotation}°'),
            onPressed: () => _mutateSelected(
              (e) => e.copyWith(rotation: (e.rotation + 45) % 360),
            ),
          ),
          const SizedBox(width: 12),
          Text(l.seatsLabel, style: T.small()),
          IconButton(
            icon: const Icon(LucideIcons.minus),
            onPressed: t.seats > 0
                ? () => _mutateSelected((e) => e.copyWith(seats: e.seats - 1))
                : null,
          ),
          Text('${t.seats}', style: T.price()),
          IconButton(
            icon: const Icon(LucideIcons.plus),
            onPressed: t.seats < 50
                ? () => _mutateSelected((e) => e.copyWith(seats: e.seats + 1))
                : null,
          ),
          const SizedBox(width: 12),
          OutlinedButton.icon(
            icon: const Icon(LucideIcons.pencil, size: 18),
            label: Text(l.renameTable),
            onPressed: _renameSelected,
          ),
          const SizedBox(width: 8),
          OutlinedButton.icon(
            icon: const Icon(
              LucideIcons.trash2,
              size: 18,
              color: T.destructive,
            ),
            label: Text(
              l.deleteTable,
              style: const TextStyle(color: T.destructive),
            ),
            onPressed: _deleteSelected,
          ),
        ],
      ),
    );
  }

  /// App-bar toggle: tooltip names the setting, colour shows on/off state.
  Widget _toggleAction(
    IconData icon,
    String label,
    bool on,
    VoidCallback onTap,
  ) => IconButton(
    icon: Icon(icon, color: on ? T.primary : T.textMuted),
    tooltip: on ? '$label ✓' : label,
    onPressed: onTap,
  );

  /// Grid control: a dropdown of Off + three dot spacings (Large/Medium/Small).
  /// Icon reads accent when the grid is on, muted when off — same colour
  /// language as the Snap toggle.
  Widget _gridMenu(L l) => PopupMenuButton<String>(
    icon: Icon(LucideIcons.grid3x3, color: _showGrid ? T.primary : T.textMuted),
    tooltip: l.editorGrid,
    onSelected: _selectGrid,
    itemBuilder: (ctx) => [
      _gridMenuItem('off', l.gridOff, selected: !_showGrid),
      for (final s in _gridSizes)
        _gridMenuItem(
          '$s',
          _gridSizeLabel(s, l),
          selected: _showGrid && _gridStep == s,
        ),
    ],
  );

  String _gridSizeLabel(int step, L l) => switch (step) {
    100 => l.gridLarge,
    50 => l.gridMedium,
    _ => l.gridSmall,
  };

  /// One grid-menu row: a leading check on the active choice, then the label.
  PopupMenuItem<String> _gridMenuItem(
    String value,
    String label, {
    required bool selected,
  }) => PopupMenuItem<String>(
    value: value,
    child: Row(
      children: [
        SizedBox(
          width: 26,
          child: selected
              ? const Icon(LucideIcons.check, size: 16, color: T.primary)
              : null,
        ),
        Text(label),
      ],
    ),
  );

  PopupMenuItem<String> _objectMenuItem(
    String type,
    IconData icon,
    String label, {
    bool enabled = true,
    String? note,
  }) => PopupMenuItem<String>(
    key: Key('object-menu-$type'),
    value: type,
    enabled: enabled,
    child: Row(
      children: [
        Icon(icon, size: 18, color: T.textMuted),
        const SizedBox(width: 12),
        // Expanded either way: a long label (or its note) must shrink to
        // the menu's own width, never force the Row wider than it.
        if (note == null)
          Expanded(child: Text(label, overflow: TextOverflow.ellipsis))
        else
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label),
                Text(note, style: T.small(color: T.textMuted)),
              ],
            ),
          ),
      ],
    ),
  );

  Widget _shapeButton(String shape, {required bool active}) {
    final icon = switch (shape) {
      'ROUND' => LucideIcons.circle,
      'SQUARE' => LucideIcons.square,
      'RECT' => LucideIcons.rectangleHorizontal,
      _ => LucideIcons.minus, // BAR
    };
    return SizedBox(
      width: T.minTouch,
      height: T.minTouch,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          padding: EdgeInsets.zero,
          backgroundColor: active ? T.surfaceAlt : null,
          side: BorderSide(color: active ? T.primary : T.border),
        ),
        // RECT/BAR presets widen the footprint so the shape reads immediately
        onPressed: () => _mutateSelected(
          (e) => e.copyWith(
            shape: shape,
            width: switch (shape) {
              'RECT' when e.width == e.height => math.min(
                e.width * 2,
                1000 - e.x,
              ),
              'BAR' => math.min(math.max(e.width, 240), 1000 - e.x),
              _ => e.width,
            },
            height: shape == 'BAR' ? 60 : e.height,
          ),
        ),
        child: Icon(icon, size: 20, color: active ? T.primary : T.textMuted),
      ),
    );
  }
}

/// One undo frame: both editable layers captured together so undo restores the
/// canvas exactly, whichever layer the last tweak touched.
class _Snapshot {
  final List<TableInfo> tables;
  final List<FloorObject> objects;
  _Snapshot(this.tables, this.objects);
}

/// One in-flight AI request: [expected] paces the working card, and Cancel
/// only sets [cancelled] — the request itself is never aborted, its answer
/// (or error) is just ignored when it arrives.
class _AiRequest {
  final Duration expected;
  bool cancelled = false;
  _AiRequest(this.expected);
}

/// Dot grid so snap-to-grid has a visual anchor. T.border is nearly the canvas
/// colour, so key off T.textMuted — the dots have to actually read on the dark
/// theme, else the Grid toggle looks like it does nothing.
class _GridPainter extends CustomPainter {
  final double scale;
  final int step; // logical units between dots
  _GridPainter({required this.scale, required this.step});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = T.textMuted.withValues(alpha: .7);
    for (var x = step; x < kCanvasUnits; x += step) {
      for (var y = step; y < kCanvasUnits; y += step) {
        canvas.drawCircle(Offset(x * scale, y * scale), 2.0, paint);
      }
    }
  }

  @override
  bool shouldRepaint(_GridPainter old) =>
      old.scale != scale || old.step != step;
}
