import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';

/// The small "AI" mark on a thumbnail whose photo was AI-generated or
/// AI-enhanced — a transparency point, the same badge as on the portal.
class AiPhotoBadge extends StatelessWidget {
  final String? source;
  const AiPhotoBadge(this.source, {super.key});

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final label = source == 'ai_enhanced'
        ? l.aiEnhancedLabel
        : l.aiGeneratedLabel;
    return Tooltip(
      message: label,
      child: Semantics(
        label: label,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
          decoration: BoxDecoration(
            color: T.textPrimary,
            borderRadius: T.radiusSmall,
            border: Border.all(color: Colors.white, width: 1.5),
          ),
          child: Text(
            l.aiBadge,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 9,
              fontWeight: FontWeight.w800,
              height: 1.2,
            ),
          ),
        ),
      ),
    );
  }
}

/// A thumbnail with the [AiPhotoBadge] in its corner when the item's photo is AI.
class AiBadged extends StatelessWidget {
  final Item item;
  final Widget child;
  const AiBadged({super.key, required this.item, required this.child});

  @override
  Widget build(BuildContext context) {
    if (!item.photoIsAi || item.photoVersion == null) return child;
    return Stack(
      clipBehavior: Clip.none,
      children: [
        child,
        Positioned(
          right: -4,
          bottom: -4,
          child: AiPhotoBadge(item.photoSource),
        ),
      ],
    );
  }
}

/// "Generate photo" and "Snap and enhance" in the item editor. Hidden when the
/// add-on is not on for this client; shown disabled with a clear note when it
/// is on but not usable now (offline, not set up). Nothing else depends on it.
class AiPhotoActions extends StatelessWidget {
  final AiPhotoStatus status;
  final bool busy;
  final VoidCallback onGenerate;
  final VoidCallback onEnhance;
  const AiPhotoActions({
    super.key,
    required this.status,
    required this.onGenerate,
    required this.onEnhance,
    this.busy = false,
  });

  @override
  Widget build(BuildContext context) {
    if (!status.configured) return const SizedBox.shrink();
    final l = L.of(context);
    final enabled = status.available && !busy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('ai-generate'),
                icon: const Icon(LucideIcons.sparkles, size: 18),
                label: Text(l.aiGeneratePhoto),
                onPressed: enabled ? onGenerate : null,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('ai-enhance'),
                icon: const Icon(LucideIcons.camera, size: 18),
                label: Text(l.aiSnapEnhance),
                onPressed: enabled ? onEnhance : null,
              ),
            ),
          ],
        ),
        if (!status.available)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Row(
              children: [
                const Icon(LucideIcons.wifiOff, size: 16, color: T.textMuted),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    l.aiUnavailableNote(status.reason),
                    key: const Key('ai-unavailable-note'),
                    style: T.small(),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// Runs a generate / enhance request, shows the 2–4 candidates, and saves the
/// one the manager taps ([choose]). "Regenerate" runs [run] again. Pops `true`
/// once a photo is saved.
class AiPhotoDialog extends StatefulWidget {
  final String title;
  final String? hint;
  final Future<AiPhotoCandidates> Function() run;
  final Future<void> Function(String candidateId) choose;
  const AiPhotoDialog({
    super.key,
    required this.title,
    required this.run,
    required this.choose,
    this.hint,
  });

  @override
  State<AiPhotoDialog> createState() => _AiPhotoDialogState();
}

class _AiPhotoDialogState extends State<AiPhotoDialog> {
  AiPhotoCandidates? _result;
  Object? _error;
  bool _loading = false;
  bool _saving = false;
  String? _picked;

  @override
  void initState() {
    super.initState();
    _generate();
  }

  Future<void> _generate() async {
    setState(() {
      _loading = true;
      _error = null;
      _picked = null;
    });
    try {
      final r = await widget.run();
      if (!mounted) return;
      setState(() {
        _result = r;
        _picked = r.candidates.isEmpty ? null : r.candidates.first.id;
      });
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _use() async {
    final id = _picked;
    if (id == null || _saving) return;
    setState(() => _saving = true);
    try {
      await widget.choose(id);
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = e;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final candidates = _result?.candidates ?? const <AiPhotoCandidate>[];
    return Dialog(
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: const BorderSide(color: T.border),
      ),
      child: SizedBox(
        width: 620,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(widget.title, style: T.headline()),
                if (widget.hint != null) ...[
                  const SizedBox(height: 4),
                  Text(widget.hint!, style: T.small()),
                ],
                const SizedBox(height: 14),
                if (_loading)
                  SizedBox(
                    height: 220,
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const CircularProgressIndicator(),
                        const SizedBox(height: 14),
                        Text(l.aiWorking, style: T.small()),
                      ],
                    ),
                  )
                else if (candidates.isNotEmpty) ...[
                  Text(l.aiPickOne, style: T.small()),
                  const SizedBox(height: 8),
                  GridView.count(
                    crossAxisCount: candidates.length <= 2
                        ? 2
                        : (candidates.length == 3 ? 3 : 4),
                    shrinkWrap: true,
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                    physics: const NeverScrollableScrollPhysics(),
                    children: [
                      for (final c in candidates)
                        _CandidateTile(
                          key: Key('ai-candidate-${c.id}'),
                          candidate: c,
                          selected: c.id == _picked,
                          onTap: () => setState(() => _picked = c.id),
                        ),
                    ],
                  ),
                ],
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 10),
                    child: Text(
                      '$_error',
                      key: const Key('ai-error'),
                      style: T.small(color: T.destructive),
                    ),
                  ),
                const SizedBox(height: 16),
                Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    TextButton.icon(
                      key: const Key('ai-regenerate'),
                      icon: const Icon(LucideIcons.refreshCw, size: 18),
                      label: Text(l.aiRegenerate),
                      onPressed: _loading || _saving ? null : _generate,
                    ),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        TextButton(
                          onPressed: _saving
                              ? null
                              : () => Navigator.pop(context, false),
                          child: Text(l.cancel),
                        ),
                        FilledButton.icon(
                          key: const Key('ai-use'),
                          icon: const Icon(LucideIcons.check, size: 18),
                          label: Text(l.aiUseThis),
                          onPressed: _loading || _saving || _picked == null
                              ? null
                              : _use,
                        ),
                      ],
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

class _CandidateTile extends StatelessWidget {
  final AiPhotoCandidate candidate;
  final bool selected;
  final VoidCallback onTap;
  const _CandidateTile({
    super.key,
    required this.candidate,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: T.radiusMedium,
      child: AnimatedContainer(
        duration: T.dFast,
        decoration: BoxDecoration(
          borderRadius: T.radiusMedium,
          border: Border.all(
            color: selected ? T.primary : T.border,
            width: selected ? 3 : 1,
          ),
        ),
        child: ClipRRect(
          borderRadius: T.radiusSmall,
          child: Image.memory(
            candidate.bytes,
            fit: BoxFit.cover,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => const ColoredBox(color: T.surfaceAlt),
          ),
        ),
      ),
    );
  }
}
