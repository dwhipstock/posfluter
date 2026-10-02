import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'mic_button.dart';

typedef MenuPhoto = ({List<int> bytes, String contentType});

/// What the AI menu dialog did, for the menu screen: the items it created
/// (to offer AI photos) and the manager PIN already given (not asked twice).
class AiMenuOutcome {
  final List<String> createdItemIds;
  final String? managerPin;
  final bool changed;
  const AiMenuOutcome(this.createdItemIds, this.managerPin, this.changed);
}

/// The store calls behind the dialog (tests pass fakes).
class AiMenuBackend {
  final Future<MenuProposal> Function(String text, String pin) chat;
  final Future<MenuProposal> Function(List<MenuPhoto> photos, String pin)
  fromPhotos;
  final Future<MenuApplyResult> Function(
    String proposalId,
    List<String> changeIds,
    String pin,
    bool confirmed,
  )
  apply;
  final Future<List<MenuChangeSet>> Function() history;
  final Future<void> Function(String setId, String pin, bool force) revert;

  /// "Translate menu" (a store with languages beyond fr / en); null = no button.
  final Future<MenuProposal> Function(String pin)? translate;

  /// The request spoken instead of typed; null = no mic button.
  final Future<MenuProposal> Function(VoiceClip clip, String pin)? chatVoice;
  final VoiceRecorder? recorder;
  const AiMenuBackend({
    required this.chat,
    required this.fromPhotos,
    required this.apply,
    required this.history,
    required this.revert,
    this.translate,
    this.chatVoice,
    this.recorder,
  });

  static final AiMenuBackend store = AiMenuBackend(
    chat: Api.menuAiChat,
    fromPhotos: (photos, pin) => Api.menuAiFromPhotos(photos, pin),
    apply: Api.menuAiApply,
    history: Api.menuAiHistory,
    revert: (id, pin, force) => Api.menuAiRevert(id, pin, force: force),
    translate: Api.menuAiTranslate,
    chatVoice: (clip, pin) =>
        Api.menuAiChatVoice(clip.bytes, clip.contentType, pin),
  );
}

/// AI menu setup: type (or say, with the mic button) a change, or read
/// photos of a paper menu. The AI's answer is only a proposal: a list of
/// new / changed / removed things the manager ticks, and nothing changes until
/// Apply. The history tab lists every applied AI update with Revert.
class AiMenuDialog extends StatefulWidget {
  final AiPhotoStatus status;
  final Future<String?> Function() askPin;
  final Future<List<MenuPhoto>?> Function() pickPhotos;
  final AiMenuBackend backend;
  const AiMenuDialog({
    super.key,
    required this.status,
    required this.askPin,
    required this.pickPhotos,
    required this.backend,
  });

  @override
  State<AiMenuDialog> createState() => _AiMenuDialogState();
}

class _AiMenuDialogState extends State<AiMenuDialog> {
  final _text = TextEditingController();
  String? _pin;
  bool _busy = false;
  String Function(L) _working = _thinking;
  static String _thinking(L l) => l.aiMenuThinking;
  Object? _error;
  MenuProposal? _proposal;
  final Set<String> _ticked = {};
  final List<String> _created = [];
  bool _changed = false;
  bool _showHistory = false;
  List<MenuChangeSet>? _history;

  bool get _available => widget.status.available;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<String?> _managerPin() async {
    _pin ??= await widget.askPin();
    return _pin;
  }

  /// Runs [call] with the spinner; a wrong PIN is forgotten so the next try asks again.
  /// [working] is what the spinner says meanwhile (reading a request,
  /// reading photos, translating, applying): never "Making photos" for text.
  Future<R?> _run<R>(
    Future<R> Function(String pin) call, {
    required String Function(L) working,
  }) async {
    final pin = await _managerPin();
    if (pin == null || !mounted) return null;
    setState(() {
      _busy = true;
      _working = working;
      _error = null;
    });
    try {
      return await call(pin);
    } on ApiException catch (e) {
      if (e.status == 403) _pin = null;
      if (mounted) setState(() => _error = e);
    } on MenuRevertConflict {
      rethrow;
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    return null;
  }

  void _show(MenuProposal? p) {
    if (p == null || !mounted) return;
    setState(() {
      _proposal = p;
      _ticked
        ..clear()
        ..addAll(p.changes.map((c) => c.id));
    });
  }

  Future<void> _ask() async {
    final text = _text.text.trim();
    if (text.isEmpty) return;
    // sent: drop focus so a touch keyboard doesn't sit over the dialog
    FocusManager.instance.primaryFocus?.unfocus();
    _show(
      await _run((pin) => widget.backend.chat(text, pin), working: _thinking),
    );
  }

  Future<void> _voice(VoiceClip clip) async {
    final call = widget.backend.chatVoice;
    if (call == null) return;
    FocusManager.instance.primaryFocus?.unfocus();
    _show(await _run((pin) => call(clip, pin), working: _thinking));
  }

  Future<void> _photos() async {
    final photos = await widget.pickPhotos();
    if (photos == null || photos.isEmpty || !mounted) return;
    _show(
      await _run(
        (pin) => widget.backend.fromPhotos(photos, pin),
        working: (l) => l.aiMenuReadingPhotos,
      ),
    );
  }

  /// The store's languages beyond the fr / en catalog slots (Copper Lantern: es, de).
  List<String> get _extraLangs => StoreProfile.current.locales
      .where((c) => c != 'fr' && c != 'en')
      .toList();

  Future<void> _translate() async {
    final call = widget.backend.translate;
    if (call == null) return;
    _show(await _run(call, working: (l) => l.aiMenuTranslating));
  }

  Future<void> _apply() async {
    final p = _proposal;
    if (p == null || _ticked.isEmpty) return;
    final l = L.of(context);
    final ids = p.changes.map((c) => c.id).where(_ticked.contains).toList();
    // "remove all" / "everything 20% off": one more explicit yes
    final picked = p.changes.where((c) => _ticked.contains(c.id));
    final removes = picked.where((c) => c.isRemoved).length;
    final prices = picked
        .where((c) => c.details.any((d) => d.field == 'price') && !c.isNew)
        .length;
    // the store decides what is big: many removals or price changes, "86
    // everything", or a price cut by half or more / to near zero
    final bulk = p.bulk;
    if (bulk) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          content: Text(
            removes > 10 || prices > 10
                ? l.aiMenuBulkConfirm(removes, prices)
                : l.aiMenuBigChangeConfirm,
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(l.cancel),
            ),
            FilledButton(
              key: const Key('ai-menu-bulk-confirm'),
              onPressed: () => Navigator.pop(context, true),
              child: Text(l.aiMenuApply(ids.length)),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    final r = await _run(
      (pin) => widget.backend.apply(p.proposalId, ids, pin, bulk),
      working: (l) => l.aiMenuApplying,
    );
    if (r == null || !mounted) return;
    setState(() {
      _created.addAll(r.createdItemIds);
      _changed = true;
      _proposal = null;
      _ticked.clear();
      _text.clear();
    });
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(l.aiMenuApplied(r.applied))));
  }

  Future<void> _openHistory() async {
    setState(() {
      _showHistory = true;
      _error = null;
    });
    try {
      final h = await widget.backend.history();
      if (mounted) setState(() => _history = h);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _revert(MenuChangeSet set) async {
    final l = L.of(context);
    try {
      await _run((pin) async {
        await widget.backend.revert(set.id, pin, false);
        return true;
      }, working: (l) => l.aiMenuApplying);
    } on MenuRevertConflict catch (c) {
      if (!mounted) return;
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          content: Text(l.aiMenuRevertConflict(c.titles.join(', '))),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(l.cancel),
            ),
            FilledButton(
              key: const Key('ai-menu-revert-anyway'),
              onPressed: () => Navigator.pop(context, true),
              child: Text(l.aiMenuRevertAnyway),
            ),
          ],
        ),
      );
      if (ok != true) return;
      await _run((pin) async {
        await widget.backend.revert(set.id, pin, true);
        return true;
      }, working: (l) => l.aiMenuApplying);
    }
    if (_error != null || !mounted) return;
    _changed = true;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(l.aiMenuRevertDone)));
    await _openHistory();
  }

  void _close() =>
      Navigator.pop(context, AiMenuOutcome(List.of(_created), _pin, _changed));

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Dialog(
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: const BorderSide(color: T.border),
      ),
      child: SizedBox(
        width: 720,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Icon(LucideIcons.sparkles, size: 22),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _showHistory ? l.aiMenuHistory : l.aiMenuTitle,
                      style: T.headline(),
                    ),
                  ),
                  TextButton.icon(
                    key: const Key('ai-menu-history'),
                    icon: Icon(
                      _showHistory
                          ? LucideIcons.arrowLeft
                          : LucideIcons.history,
                      size: 18,
                    ),
                    label: Text(_showHistory ? l.aiMenuTitle : l.aiMenuHistory),
                    onPressed: _busy
                        ? null
                        : _showHistory
                        ? () => setState(() => _showHistory = false)
                        : _openHistory,
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Flexible(
                child: SingleChildScrollView(
                  child: _showHistory ? _historyView(l) : _setupView(l),
                ),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Text(
                    '$_error',
                    key: const Key('ai-menu-error'),
                    style: T.small(color: T.destructive),
                  ),
                ),
              const SizedBox(height: 14),
              Wrap(
                alignment: WrapAlignment.end,
                spacing: 8,
                children: [
                  TextButton(
                    onPressed: _busy ? null : _close,
                    child: Text(l.close),
                  ),
                  if (!_showHistory && _proposal != null)
                    FilledButton.icon(
                      key: const Key('ai-menu-apply'),
                      icon: const Icon(LucideIcons.check, size: 18),
                      label: Text(l.aiMenuApply(_ticked.length)),
                      onPressed: _busy || _ticked.isEmpty ? null : _apply,
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _setupView(L l) {
    final enabled = _available && !_busy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: TextField(
                key: const Key('ai-menu-text'),
                controller: _text,
                enabled: enabled,
                minLines: 1,
                maxLines: 3,
                textInputAction: TextInputAction.send,
                onSubmitted: (_) => _ask(),
                // a new request: the last answer (or refusal) no longer applies
                onChanged: (_) {
                  if (_error != null || _proposal != null) {
                    setState(() {
                      _error = null;
                      _proposal = null;
                      _ticked.clear();
                    });
                  }
                },
                decoration: InputDecoration(hintText: l.aiMenuChatHint),
              ),
            ),
            if (widget.backend.chatVoice != null) ...[
              const SizedBox(width: 8),
              MicButton(
                key: const Key('ai-menu-mic'),
                enabled: enabled,
                recorder: widget.backend.recorder,
                onClip: _voice,
              ),
            ],
            const SizedBox(width: 8),
            FilledButton(
              key: const Key('ai-menu-ask'),
              onPressed: enabled ? _ask : null,
              child: Text(l.aiMenuAsk),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              key: const Key('ai-menu-photos'),
              icon: const Icon(LucideIcons.camera, size: 18),
              label: Text(l.aiMenuFromPhotos),
              onPressed: enabled ? _photos : null,
            ),
            if (widget.backend.translate != null && _extraLangs.isNotEmpty)
              Tooltip(
                message: l.aiMenuTranslateNote(
                  _extraLangs.map(l.langName).join(', '),
                ),
                child: OutlinedButton.icon(
                  key: const Key('ai-menu-translate'),
                  icon: const Icon(LucideIcons.languages, size: 18),
                  label: Text(l.aiMenuTranslate),
                  onPressed: enabled ? _translate : null,
                ),
              ),
          ],
        ),
        if (!_available)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Row(
              children: [
                const Icon(LucideIcons.wifiOff, size: 16, color: T.textMuted),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    l.aiMenuUnavailableNote(widget.status.reason),
                    key: const Key('ai-menu-unavailable'),
                    style: T.small(),
                  ),
                ),
              ],
            ),
          ),
        if (_busy)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 24),
            child: Column(
              children: [
                const CircularProgressIndicator(),
                const SizedBox(height: 10),
                Text(
                  _working(l),
                  key: const Key('ai-menu-working'),
                  style: T.small(),
                ),
              ],
            ),
          )
        else if (_proposal != null)
          _proposalView(l, _proposal!),
      ],
    );
  }

  Widget _proposalView(L l, MenuProposal p) {
    final groups = [
      (l.aiMenuNew, p.changes.where((c) => c.isNew).toList()),
      (
        l.aiMenuChanged,
        p.changes.where((c) => !c.isNew && !c.isRemoved).toList(),
      ),
      (l.aiMenuRemoved, p.changes.where((c) => c.isRemoved).toList()),
    ];
    final refusal = p.refusal;
    final heard = (p.transcript ?? '').isEmpty
        ? null
        : Padding(
            padding: const EdgeInsets.only(top: 14),
            child: Text(
              l.aiHeard(p.transcript!),
              key: const Key('ai-menu-heard'),
              style: T.small(color: T.textMuted),
            ),
          );
    if (refusal != null) {
      // a normal assistant message, not an error
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ?heard,
          Padding(
            padding: const EdgeInsets.only(top: 14),
            child: Text(
              l.aiMenuRefusal(refusal),
              key: const Key('ai-menu-refusal'),
              style: T.text(size: 15),
            ),
          ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ?heard,
        const SizedBox(height: 14),
        if (p.summary.isNotEmpty) Text(p.summary, style: T.text(size: 15)),
        const SizedBox(height: 4),
        Text(l.aiMenuPreviewNote, style: T.small()),
        if (p.rejected.isNotEmpty)
          Text(
            l.aiMenuRejected(p.rejected.length),
            style: T.small(color: T.destructive),
          ),
        if (p.changes.isEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              p.proposalId.isEmpty ? l.aiMenuTranslateDone : l.aiMenuNothing,
            ),
          ),
        for (final (label, changes) in groups)
          if (changes.isNotEmpty) ...[
            Padding(
              padding: const EdgeInsets.only(top: 12, bottom: 2),
              child: Text(
                '$label (${changes.length})',
                style: T.text(size: 14, weight: FontWeight.w700),
              ),
            ),
            for (final c in changes) _changeTile(l, p, c),
          ],
      ],
    );
  }

  Widget _changeTile(L l, MenuProposal p, MenuChange c) {
    final title = switch (c.kind) {
      'add_category' => l.aiMenuNewCategory(c.title),
      'reorder_categories' => l.aiMenuCategoryOrder,
      _ => c.title,
    };
    final lines = <String>[
      if (c.category != null && c.kind != 'update_item') c.category!,
      for (final d in c.details)
        if ((d.after ?? '').isNotEmpty || (d.before ?? '').isNotEmpty)
          '${l.aiMenuField(d.field)}${d.label == null ? '' : ' (${d.field == 'name' ? l.langName(d.label!) : d.label})'}: '
              '${d.before == null ? '' : '${_value(l, d.field, d.before!)} → '}'
              '${_value(l, d.field, d.after ?? '')}',
    ];
    return CheckboxListTile(
      key: Key('ai-menu-change-${c.id}'),
      dense: true,
      contentPadding: EdgeInsets.zero,
      controlAffinity: ListTileControlAffinity.leading,
      value: _ticked.contains(c.id),
      onChanged: _busy
          ? null
          : (v) => setState(() {
              if (v == true) {
                _ticked.add(c.id);
                // an item in a new category needs that category
                if (c.needs != null) _ticked.add(c.needs!);
              } else {
                _ticked.remove(c.id);
                // unticking a new category unticks the items that need it
                _ticked.removeAll(
                  p.changes.where((o) => o.needs == c.id).map((o) => o.id),
                );
              }
            }),
      title: Text(
        title,
        style: T.text(
          size: 15,
          color: c.isRemoved ? T.destructive : T.textPrimary,
        ),
      ),
      subtitle: lines.isEmpty ? null : Text(lines.join('\n'), style: T.small()),
    );
  }

  // 'available' is on/off sale ("true"/"false") or, for a day-only item, the
  // store's own words ("Only Fri & Sat"), shown as they are
  String _value(L l, String field, String v) => field != 'available'
      ? v
      : v == 'true'
      ? l.aiMenuField('available')
      : v == 'false'
      ? l.offSale
      : v;

  Widget _historyView(L l) {
    final h = _history;
    if (h == null) {
      return const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (h.isEmpty) return Text(l.aiMenuNoHistory);
    return Column(
      children: [
        for (final s in h)
          ListTile(
            key: Key('ai-menu-set-${s.id}'),
            contentPadding: EdgeInsets.zero,
            title: Text(
              s.summary.isNotEmpty ? s.summary : s.titles.join(', '),
              style: T.text(size: 15),
            ),
            subtitle: Text(
              [
                if (s.createdAt != null) _when(s.createdAt!),
                l.aiMenuSourceLabel(s.source),
                s.appliedBy,
                if (s.titles.isNotEmpty) s.titles.join(', '),
              ].join(' · '),
              style: T.small(),
            ),
            trailing: s.reverted
                ? Text(l.aiMenuReverted, style: T.small())
                : OutlinedButton(
                    key: Key('ai-menu-revert-${s.id}'),
                    onPressed: _busy ? null : () => _revert(s),
                    child: Text(l.aiMenuRevert),
                  ),
          ),
      ],
    );
  }

  static String _when(DateTime t) =>
      '${t.year}-${t.month.toString().padLeft(2, '0')}-${t.day.toString().padLeft(2, '0')} '
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
}
