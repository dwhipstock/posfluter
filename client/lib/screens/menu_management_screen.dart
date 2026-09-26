import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/ai_photos.dart';
import '../widgets/item_photo.dart';
import '../widgets/pin_pad.dart';
import '../widgets/resume_refresh.dart';

/// Owner-editable menu (M6). Dense item list grouped by category; the 86
/// switch stays inline-PIN-gated so a server can hand the tablet to a manager.
/// Managers additionally get: add item, full edit modal (names, category,
/// abbrev, alcohol, sizes + prices, delete) and the categories editor —
/// all manager-session-gated server-side.
class MenuManagementScreen extends StatefulWidget {
  const MenuManagementScreen({super.key});

  @override
  State<MenuManagementScreen> createState() => _MenuManagementScreenState();
}

class _MenuManagementScreenState extends State<MenuManagementScreen>
    with ResumeRefresh {
  @override
  void onAppResume() => _load();

  List<Item> _items = [];
  List<Category> _categories = [];
  // AI menu photos (paid add-on): hidden until the store says it is on
  AiPhotoStatus _aiStatus = AiPhotoStatus.hidden;
  bool _loaded = false;
  String? _error;

  bool get _isManager => Api.currentUser?.isManager ?? false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Never blocks the menu: a slow or failing check just leaves the buttons off.
  Future<void> _loadAiStatus() async {
    if (!_isManager) return;
    try {
      final st = await Api.aiPhotoStatus();
      if (mounted) setState(() => _aiStatus = st);
    } catch (_) {}
  }

  Future<void> _load() async {
    _loadAiStatus();
    try {
      final results = await Future.wait([
        Api.items(includeInactive: true),
        Api.categories(),
      ]);
      if (mounted) {
        setState(() {
          _items = results[0] as List<Item>;
          _categories = results[1] as List<Category>;
          _loaded = true;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _loaded = true;
          _error = e is SessionExpiredException ? '' : '$e';
        });
      }
    }
  }

  Future<void> _toggle(Item item, bool active) async {
    final l = L.of(context);
    final name = l.name(item.nameFr, item.nameEn);
    final approval = await requireGrant(
      context,
      Perm.editMenu,
      title: active ? l.enableSale(name) : l.disableSale(name),
    );
    if (approval == null) return;
    try {
      await Api.setAvailability(item.id, active, approval.managerPin);
      await _load();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _uploadPhoto(Item item) async {
    final l = L.of(context);
    final pin = await askManagerPin(
      context,
      title: l.uploadPhotoFor(l.name(item.nameFr, item.nameEn)),
    );
    if (pin == null || !mounted) return;
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      maxWidth: 1200,
      maxHeight: 1200,
      imageQuality: 85,
    );
    if (picked == null || !mounted) return;
    try {
      final bytes = await picked.readAsBytes();
      final type = picked.name.toLowerCase().endsWith('.png')
          ? 'image/png'
          : 'image/jpeg';
      await Api.uploadItemPhoto(item.id, bytes, type, pin);
      await _load();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.photoUploaded)));
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _aiGenerate(Item item) async {
    final l = L.of(context);
    final name = l.name(item.nameFr, item.nameEn);
    final pin = await askManagerPin(context, title: l.aiGenerateFor(name));
    if (pin == null || !mounted) return;
    await _runAiDialog(
      title: l.aiGenerateFor(name),
      run: () =>
          Api.aiGeneratePhoto(item.id, pin, count: _aiStatus.defaultCount),
      choose: (id) => Api.aiChoosePhoto(item.id, id, pin),
    );
  }

  Future<void> _aiEnhance(Item item) async {
    final l = L.of(context);
    final name = l.name(item.nameFr, item.nameEn);
    final pin = await askManagerPin(context, title: l.aiEnhanceFor(name));
    if (pin == null || !mounted) return;
    final picker = ImagePicker();
    final canCamera = picker.supportsImageSource(ImageSource.camera);
    final source = canCamera
        ? await showDialog<ImageSource>(
            context: context,
            builder: (context) => SimpleDialog(
              title: Text(l.aiEnhanceFor(name)),
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
      imageQuality: 88,
    );
    if (picked == null || !mounted) return;
    final bytes = await picked.readAsBytes();
    final type = picked.name.toLowerCase().endsWith('.png')
        ? 'image/png'
        : 'image/jpeg';
    if (!mounted) return;
    await _runAiDialog(
      title: l.aiEnhanceFor(name),
      hint: l.aiEnhanceHint,
      run: () => Api.aiEnhancePhoto(
        item.id,
        bytes,
        type,
        pin,
        count: _aiStatus.defaultCount,
      ),
      choose: (id) => Api.aiChoosePhoto(item.id, id, pin),
    );
  }

  Future<void> _runAiDialog({
    required String title,
    String? hint,
    required Future<AiPhotoCandidates> Function() run,
    required Future<void> Function(String) choose,
  }) async {
    final l = L.of(context);
    final saved = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) =>
          AiPhotoDialog(title: title, hint: hint, run: run, choose: choose),
    );
    if (saved == true && mounted) {
      await _load();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.aiPhotoSaved)));
      }
    }
  }

  Future<void> _openEditor([Item? item]) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _ItemEditorDialog(
        item: item,
        categories: _categories,
        onUploadPhoto: item == null ? null : () => _uploadPhoto(item),
        aiStatus: _aiStatus,
        onAiGenerate: item == null ? null : () => _aiGenerate(item),
        onAiEnhance: item == null ? null : () => _aiEnhance(item),
      ),
    );
    if (saved == true) _load();
  }

  Future<void> _openCategoriesEditor() async {
    await showDialog(
      context: context,
      builder: (_) => const _CategoriesEditorDialog(),
    );
    _load(); // names/order/deletions all affect the grouped list
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    String catName(String id) {
      final c = _categories.where((c) => c.id == id).firstOrNull;
      return c == null ? id : l.name(c.nameFr, c.nameEn);
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(l.manageMenuTitle),
        actions: [
          const LangActions(),
          if (_isManager) ...[
            IconButton(
              icon: const Icon(LucideIcons.tags),
              tooltip: l.editCategories,
              onPressed: _openCategoriesEditor,
            ),
            IconButton(
              icon: const Icon(LucideIcons.plus),
              tooltip: l.addItem,
              onPressed: () => _openEditor(),
            ),
          ],
        ],
      ),
      body: !_loaded
          ? const DelayedSpinner()
          : _error != null
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(_error!.isEmpty ? l.cannotReachServer : _error!),
                  const SizedBox(height: 12),
                  FilledButton(onPressed: _load, child: Text(l.retry)),
                ],
              ),
            )
          : ListView(
              padding: const EdgeInsets.symmetric(vertical: 8),
              children: [
                // group in the owner's category order, not map order
                for (final category in _categories)
                  ..._categorySection(category.id, catName(category.id), l),
                // safety net: items whose category vanished still show
                for (final orphan
                    in _items
                        .map((i) => i.category)
                        .toSet()
                        .where((c) => _categories.every((k) => k.id != c)))
                  ..._categorySection(orphan, orphan, l),
              ],
            ),
    );
  }

  List<Widget> _categorySection(String categoryId, String title, L l) {
    final items = _items.where((i) => i.category == categoryId).toList();
    if (items.isEmpty) return const [];
    return [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
        child: SectionLabel(title),
      ),
      for (final item in items)
        Container(
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: T.border)),
          ),
          child: ListTile(
            onTap: () => _isManager ? _openEditor(item) : _uploadPhoto(item),
            leading: AiBadged(
              item: item,
              child: ClipRRect(
                borderRadius: T.radiusSmall,
                child: SizedBox(
                  width: 44,
                  height: 44,
                  child: ItemPhoto(
                    item,
                    width: 128,
                    fallback: AbbrevFallback(item.abbrev, size: 44),
                  ),
                ),
              ),
            ),
            title: Text(
              l.name(item.nameFr, item.nameEn),
              style: T.text(size: 16),
            ),
            subtitle: Text(
              '${item.variants.map((v) => money(v.priceCents)).join(' / ')}'
              '${item.active ? '' : '  ·  ${l.offSale}'}',
              style: T.small(color: item.active ? T.textMuted : T.destructive),
            ),
            trailing: Switch(
              value: item.active,
              onChanged: (v) => _toggle(item, v),
            ),
          ),
        ),
    ];
  }
}

/// One editable size row inside the item editor.
class _VariantEdit {
  final String? id; // null = new, not yet on the server
  final TextEditingController labelFr;
  final TextEditingController labelEn;
  final TextEditingController priceCAD;
  _VariantEdit({this.id, String fr = '', String en = '', int? cents})
    : labelFr = TextEditingController(text: fr),
      labelEn = TextEditingController(text: en),
      priceCAD = TextEditingController(
        text: cents == null ? '' : '${cents ~/ 100}',
      );

  bool get filled =>
      labelFr.text.trim().isNotEmpty &&
      labelEn.text.trim().isNotEmpty &&
      int.tryParse(priceCAD.text) != null;
}

class _ItemEditorDialog extends StatefulWidget {
  final Item? item; // null = add new
  final List<Category> categories;
  final VoidCallback? onUploadPhoto;
  final AiPhotoStatus aiStatus;
  final VoidCallback? onAiGenerate, onAiEnhance;
  const _ItemEditorDialog({
    required this.item,
    required this.categories,
    this.onUploadPhoto,
    this.aiStatus = AiPhotoStatus.hidden,
    this.onAiGenerate,
    this.onAiEnhance,
  });

  @override
  State<_ItemEditorDialog> createState() => _ItemEditorDialogState();
}

class _ItemEditorDialogState extends State<_ItemEditorDialog> {
  late final _nameFr = TextEditingController(text: widget.item?.nameFr ?? '');
  late final _nameEn = TextEditingController(text: widget.item?.nameEn ?? '');
  late final _abbrev = TextEditingController(text: widget.item?.abbrev ?? '');
  late String _categoryId = widget.item?.category ?? widget.categories.first.id;
  late bool _isAlcohol = widget.item?.isAlcohol ?? false;
  late bool _active = widget.item?.active ?? true;
  late final List<_VariantEdit> _variants = widget.item == null
      ? [_VariantEdit()]
      : [
          for (final v in widget.item!.variants)
            _VariantEdit(
              id: v.id,
              fr: v.labelFr,
              en: v.labelEn,
              cents: v.priceCents,
            ),
        ];
  bool _busy = false;

  Future<void> _guard(Future<void> Function() op) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await op();
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _removeVariant(_VariantEdit v) async {
    if (v.id == null) {
      setState(() => _variants.remove(v));
      return;
    }
    // existing size: delete server-side right away (server refuses if a live
    // bill references it, or if it's the last size)
    await _guard(() async {
      final updated = await Api.deleteVariant(widget.item!.id, v.id!);
      if (!mounted) return;
      setState(() {
        _variants.removeWhere((x) => x.id == v.id);
        // keep in sync in case the server view diverged
        for (final sv in updated.variants) {
          if (_variants.every((x) => x.id != sv.id)) {
            _variants.add(
              _VariantEdit(
                id: sv.id,
                fr: sv.labelFr,
                en: sv.labelEn,
                cents: sv.priceCents,
              ),
            );
          }
        }
      });
    });
  }

  Future<void> _save() async {
    final l = L.of(context);
    if (_nameFr.text.trim().isEmpty ||
        _nameEn.text.trim().isEmpty ||
        _abbrev.text.trim().isEmpty ||
        _variants.isEmpty ||
        _variants.any((v) => !v.filled)) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.fillAllFields)));
      return;
    }
    await _guard(() async {
      if (widget.item == null) {
        await Api.createItem({
          'nameFr': _nameFr.text.trim(),
          'nameEn': _nameEn.text.trim(),
          'categoryId': _categoryId,
          'abbrev': _abbrev.text.trim(),
          'isAlcohol': _isAlcohol,
          'variants': [
            for (final v in _variants)
              {
                'labelFr': v.labelFr.text.trim(),
                'labelEn': v.labelEn.text.trim(),
                'priceCents': int.parse(v.priceCAD.text) * 100,
              },
          ],
        });
      } else {
        final item = widget.item!;
        await Api.updateItem(item.id, {
          'nameFr': _nameFr.text.trim(),
          'nameEn': _nameEn.text.trim(),
          'categoryId': _categoryId,
          'abbrev': _abbrev.text.trim(),
          'isAlcohol': _isAlcohol,
          'active': _active,
        });
        for (final v in _variants) {
          if (v.id == null) {
            await Api.addVariant(item.id, {
              'labelFr': v.labelFr.text.trim(),
              'labelEn': v.labelEn.text.trim(),
              'priceCents': int.parse(v.priceCAD.text) * 100,
            });
          } else {
            final orig = item.variants.where((x) => x.id == v.id).firstOrNull;
            final cents = int.parse(v.priceCAD.text) * 100;
            if (orig == null ||
                orig.labelFr != v.labelFr.text.trim() ||
                orig.labelEn != v.labelEn.text.trim() ||
                orig.priceCents != cents) {
              await Api.updateVariant(item.id, v.id!, {
                'labelFr': v.labelFr.text.trim(),
                'labelEn': v.labelEn.text.trim(),
                'priceCents': cents,
              });
            }
          }
        }
      }
      if (!mounted) return;
      Navigator.pop(context, true);
    });
  }

  Future<void> _deleteItem() async {
    final l = L.of(context);
    final item = widget.item!;
    // soft-delete has no restore UI — this one keeps a confirm on purpose
    final sure = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.deleteItem),
        content: Text(l.deleteItemConfirm(l.name(item.nameFr, item.nameEn))),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: T.destructive,
              foregroundColor: T.onDestructive,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: Text(l.deleteItem),
          ),
        ],
      ),
    );
    if (sure != true || !mounted) return;
    await _guard(() async {
      await Api.deleteItem(item.id);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.itemDeleted)));
      Navigator.pop(context, true);
    });
  }

  Widget _field(
    TextEditingController c,
    String label, {
    TextInputType? keyboard,
    int? maxLength,
  }) {
    return TextField(
      controller: c,
      keyboardType: keyboard,
      maxLength: maxLength,
      style: T.text(size: 16),
      decoration: InputDecoration(labelText: label, counterText: ''),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final isNew = widget.item == null;
    return Dialog(
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: const BorderSide(color: T.border),
      ),
      child: SizedBox(
        width: 560,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(isNew ? l.addItem : l.editItem, style: T.headline()),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(child: _field(_nameFr, l.nameFrLabel)),
                    const SizedBox(width: 10),
                    Expanded(child: _field(_nameEn, l.nameEnLabel)),
                  ],
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _categoryId,
                        decoration: InputDecoration(labelText: l.categoryLabel),
                        items: [
                          for (final c in widget.categories)
                            DropdownMenuItem(
                              value: c.id,
                              child: Text(l.name(c.nameFr, c.nameEn)),
                            ),
                        ],
                        onChanged: (v) =>
                            setState(() => _categoryId = v ?? _categoryId),
                      ),
                    ),
                    const SizedBox(width: 10),
                    SizedBox(
                      width: 130,
                      child: _field(_abbrev, l.abbrevLabel, maxLength: 4),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Expanded(
                      child: SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(l.isAlcoholLabel, style: T.text(size: 15)),
                        value: _isAlcohol,
                        onChanged: (v) => setState(() => _isAlcohol = v),
                      ),
                    ),
                    if (!isNew)
                      Expanded(
                        child: SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(l.activeLabel, style: T.text(size: 15)),
                          value: _active,
                          onChanged: (v) => setState(() => _active = v),
                        ),
                      ),
                  ],
                ),
                SectionLabel(l.sizesLabel),
                for (final v in _variants)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Row(
                      children: [
                        Expanded(
                          flex: 3,
                          child: _field(v.labelFr, l.sizeLabelFr),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          flex: 3,
                          child: _field(v.labelEn, l.sizeLabelEn),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          flex: 2,
                          child: _field(
                            v.priceCAD,
                            l.priceCAD,
                            keyboard: TextInputType.number,
                          ),
                        ),
                        IconButton(
                          icon: const Icon(
                            LucideIcons.x,
                            size: 18,
                            color: T.destructive,
                          ),
                          tooltip: l.deleteLine,
                          constraints: const BoxConstraints(
                            minWidth: 44,
                            minHeight: T.minTouch,
                          ),
                          onPressed: _busy ? null : () => _removeVariant(v),
                        ),
                      ],
                    ),
                  ),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    icon: const Icon(LucideIcons.plus, size: 18),
                    label: Text(l.addSize),
                    onPressed: _busy
                        ? null
                        : () => setState(() => _variants.add(_VariantEdit())),
                  ),
                ),
                if (!isNew) ...[
                  const SizedBox(height: 4),
                  OutlinedButton.icon(
                    icon: const Icon(LucideIcons.imagePlus, size: 18),
                    label: Text(l.uploadPhoto),
                    onPressed: _busy
                        ? null
                        : () {
                            Navigator.pop(context, false);
                            widget.onUploadPhoto?.call();
                          },
                  ),
                  if (widget.aiStatus.configured) ...[
                    const SizedBox(height: 8),
                    AiPhotoActions(
                      status: widget.aiStatus,
                      busy: _busy,
                      onGenerate: () {
                        Navigator.pop(context, false);
                        widget.onAiGenerate?.call();
                      },
                      onEnhance: () {
                        Navigator.pop(context, false);
                        widget.onAiEnhance?.call();
                      },
                    ),
                  ],
                ],
                const SizedBox(height: 16),
                Row(
                  children: [
                    if (!isNew)
                      TextButton.icon(
                        icon: const Icon(
                          LucideIcons.trash2,
                          size: 18,
                          color: T.destructive,
                        ),
                        label: Text(
                          l.deleteItem,
                          style: const TextStyle(color: T.destructive),
                        ),
                        onPressed: _busy ? null : _deleteItem,
                      ),
                    const Spacer(),
                    TextButton(
                      onPressed: _busy
                          ? null
                          : () => Navigator.pop(context, false),
                      child: Text(l.cancel),
                    ),
                    const SizedBox(width: 8),
                    FilledButton.icon(
                      icon: const Icon(LucideIcons.save, size: 18),
                      label: Text(l.save),
                      onPressed: _busy ? null : _save,
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

/// Reorderable category list: drag to reorder (persists immediately), tap
/// pencil to rename, X to delete (server refuses while items reference it).
class _CategoriesEditorDialog extends StatefulWidget {
  const _CategoriesEditorDialog();

  @override
  State<_CategoriesEditorDialog> createState() =>
      _CategoriesEditorDialogState();
}

class _CategoriesEditorDialogState extends State<_CategoriesEditorDialog> {
  List<Category> _categories = [];
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final categories = await Api.categories();
      if (mounted) {
        setState(() {
          _categories = categories;
          _loaded = true;
        });
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  // onReorderItem: newIndex is already adjusted for the removed row
  Future<void> _reorder(int oldIndex, int newIndex) async {
    setState(() {
      final moved = _categories.removeAt(oldIndex);
      _categories.insert(newIndex, moved);
    });
    try {
      await Api.reorderCategories([for (final c in _categories) c.id]);
    } catch (e) {
      if (mounted) showApiError(context, e);
      _load(); // fall back to server truth
    }
  }

  Future<void> _edit([Category? category]) async {
    final l = L.of(context);
    final nameFr = TextEditingController(text: category?.nameFr ?? '');
    final nameEn = TextEditingController(text: category?.nameEn ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(category == null ? l.addCategory : l.editCategories),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameFr,
              decoration: InputDecoration(labelText: l.nameFrLabel),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: nameEn,
              decoration: InputDecoration(labelText: l.nameEnLabel),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l.save),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    if (nameFr.text.trim().isEmpty || nameEn.text.trim().isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.fillAllFields)));
      return;
    }
    try {
      if (category == null) {
        await Api.createCategory({
          'nameFr': nameFr.text.trim(),
          'nameEn': nameEn.text.trim(),
        });
      } else {
        await Api.updateCategory(category.id, {
          'nameFr': nameFr.text.trim(),
          'nameEn': nameEn.text.trim(),
        });
      }
      await _load();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _delete(Category category) async {
    try {
      await Api.deleteCategory(category.id);
      await _load();
    } catch (e) {
      if (mounted) showApiError(context, e); // conflict → translated message
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Dialog(
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: const BorderSide(color: T.border),
      ),
      child: SizedBox(
        width: 480,
        height: 560,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l.editCategories, style: T.headline()),
              Text(l.dragToReorder, style: T.small()),
              const SizedBox(height: 8),
              Expanded(
                child: !_loaded
                    ? const DelayedSpinner()
                    : ReorderableListView(
                        buildDefaultDragHandles: false,
                        onReorderItem: _reorder,
                        children: [
                          for (var i = 0; i < _categories.length; i++)
                            Container(
                              key: ValueKey(_categories[i].id),
                              decoration: const BoxDecoration(
                                border: Border(
                                  bottom: BorderSide(color: T.border),
                                ),
                              ),
                              child: Row(
                                children: [
                                  ReorderableDragStartListener(
                                    index: i,
                                    child: const Padding(
                                      padding: EdgeInsets.all(14),
                                      child: Icon(
                                        LucideIcons.gripVertical,
                                        size: 20,
                                        color: T.textMuted,
                                      ),
                                    ),
                                  ),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Text(
                                          l.name(
                                            _categories[i].nameFr,
                                            _categories[i].nameEn,
                                          ),
                                          style: T.text(size: 16),
                                        ),
                                        Text(
                                          l.nameAlt(
                                            _categories[i].nameFr,
                                            _categories[i].nameEn,
                                          ),
                                          style: T.small(),
                                        ),
                                      ],
                                    ),
                                  ),
                                  IconButton(
                                    icon: const Icon(
                                      LucideIcons.pencil,
                                      size: 18,
                                    ),
                                    constraints: const BoxConstraints(
                                      minWidth: 44,
                                      minHeight: T.minTouch,
                                    ),
                                    onPressed: () => _edit(_categories[i]),
                                  ),
                                  IconButton(
                                    icon: const Icon(
                                      LucideIcons.x,
                                      size: 18,
                                      color: T.destructive,
                                    ),
                                    tooltip: l.deleteCategory,
                                    constraints: const BoxConstraints(
                                      minWidth: 44,
                                      minHeight: T.minTouch,
                                    ),
                                    onPressed: () => _delete(_categories[i]),
                                  ),
                                ],
                              ),
                            ),
                        ],
                      ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  TextButton.icon(
                    icon: const Icon(LucideIcons.plus, size: 18),
                    label: Text(l.addCategory),
                    onPressed: () => _edit(),
                  ),
                  const Spacer(),
                  FilledButton(
                    onPressed: () => Navigator.pop(context),
                    child: Text(l.done),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
