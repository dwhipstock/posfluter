import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import 'terminal.dart';

/// Settings → Card terminal (manager): which terminal the store uses, its
/// state and address, pairing a LAN terminal with the code on its screen,
/// and going back to the built-in one. Only for store-driven terminals
/// (simulator / J.P. Morgan); anything else shows nothing.
class CardTerminalSettings extends StatefulWidget {
  final TerminalClient client;
  const CardTerminalSettings({super.key, this.client = const TerminalClient()});

  @override
  State<CardTerminalSettings> createState() => _CardTerminalSettingsState();
}

class _CardTerminalSettingsState extends State<CardTerminalSettings> {
  TerminalStatus? _status;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final st = await widget.client.status();
      if (mounted) setState(() => _status = st);
    } catch (_) {}
  }

  Future<void> _pair() async {
    final st = _status;
    final result = await showDialog<(String, String)>(
      context: context,
      builder: (_) => _PairDialog(initialHost: st?.address ?? ''),
    );
    if (result == null || !mounted) return;
    setState(() => _busy = true);
    try {
      final next = await widget.client.pair(result.$1, result.$2);
      if (!mounted) return;
      setState(() => _status = next);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(L.of(context).terminalPaired)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _unpair() async {
    setState(() => _busy = true);
    try {
      final next = await widget.client.unpair();
      if (mounted) setState(() => _status = next);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final st = _status;
    if (st == null || !st.storeDriven) return const SizedBox.shrink();
    final l = L.of(context);
    return Column(
      key: const ValueKey('card-terminal-settings'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionLabel(l.sectionCardTerminal),
        Text(
          '${l.terminalKindName(st.kind)} · ${l.terminalStateName(st.readerState)}',
          style: T.text(size: 16, weight: FontWeight.w600),
        ),
        const SizedBox(height: 4),
        Text(
          st.embedded
              ? l.terminalBuiltIn
              : [st.readerName, st.address].whereType<String>().join(' · '),
          key: const ValueKey('card-terminal-where'),
          style: T.small(),
        ),
        if (!st.available && st.reason != null) ...[
          const SizedBox(height: 4),
          Text(l.terminalUnavailableHint(st.reason), style: T.small()),
        ],
        const SizedBox(height: 8),
        SizedBox(
          height: T.minTouch,
          child: OutlinedButton.icon(
            key: const ValueKey('card-terminal-pair'),
            icon: const Icon(LucideIcons.link),
            label: Text(l.pairTerminal),
            onPressed: _busy ? null : _pair,
          ),
        ),
        if (!st.embedded) ...[
          const SizedBox(height: 8),
          SizedBox(
            height: T.minTouch,
            child: OutlinedButton.icon(
              key: const ValueKey('card-terminal-unpair'),
              icon: const Icon(LucideIcons.monitorSmartphone),
              label: Text(l.useBuiltInTerminal),
              onPressed: _busy ? null : _unpair,
            ),
          ),
        ],
        const SizedBox(height: 8),
      ],
    );
  }
}

class _PairDialog extends StatefulWidget {
  final String initialHost;
  const _PairDialog({required this.initialHost});

  @override
  State<_PairDialog> createState() => _PairDialogState();
}

class _PairDialogState extends State<_PairDialog> {
  late final _host = TextEditingController(text: widget.initialHost);
  final _code = TextEditingController();

  @override
  void dispose() {
    _host.dispose();
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return AlertDialog(
      title: Text(l.pairTerminal),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const ValueKey('pair-host'),
            controller: _host,
            keyboardType: TextInputType.url,
            decoration: InputDecoration(labelText: l.terminalAddressLabel),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('pair-code'),
            controller: _code,
            keyboardType: TextInputType.number,
            maxLength: 6,
            decoration: InputDecoration(labelText: l.terminalCodeLabel),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(l.cancel),
        ),
        FilledButton(
          key: const ValueKey('pair-submit'),
          onPressed: () =>
              Navigator.pop(context, (_host.text.trim(), _code.text.trim())),
          child: Text(l.pairAction),
        ),
      ],
    );
  }
}
