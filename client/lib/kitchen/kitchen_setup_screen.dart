import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import 'kitchen_i18n.dart';

/// Manager setup for kitchen tickets: stations (name, printer or screen,
/// printer address, paper width, test print), which menu categories go where,
/// per-item overrides, ticket language and the kitchen screen's timers.
class KitchenSetupScreen extends StatefulWidget {
  const KitchenSetupScreen({super.key});

  @override
  State<KitchenSetupScreen> createState() => _KitchenSetupScreenState();
}

class _KitchenSetupScreenState extends State<KitchenSetupScreen> {
  KitchenConfig? _config;
  List<Category> _categories = [];
  List<Item> _items = [];
  KitchenSettings _settings = const KitchenSettings();
  final _warn = TextEditingController();
  final _late = TextEditingController();
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait([
        KitchenApi.config(),
        Api.categories(),
        Api.items(includeInactive: true),
      ]);
      if (!mounted) return;
      final config = results[0] as KitchenConfig;
      setState(() {
        _config = config;
        _categories = results[1] as List<Category>;
        _items = results[2] as List<Item>;
        _settings = config.settings;
        _warn.text = '${config.settings.warnMinutes}';
        _late.text = '${config.settings.lateMinutes}';
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  void _toast(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _run(Future<void> Function() op) async {
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

  Future<void> _saveSettings() => _run(() async {
    final k = K.of(context);
    final next = _settings.copyWith(
      warnMinutes: int.tryParse(_warn.text) ?? _settings.warnMinutes,
      lateMinutes: int.tryParse(_late.text) ?? _settings.lateMinutes,
    );
    await KitchenApi.saveSettings(next);
    await _load();
    if (mounted) _toast(k.saved);
  });

  Future<void> _editStation([KitchenStation? s]) async {
    final result = await showDialog<KitchenStation>(
      context: context,
      builder: (_) => _StationDialog(station: s),
    );
    if (result == null) return;
    await _run(() async {
      await KitchenApi.saveStation(result);
      await _load();
    });
  }

  Future<void> _deleteStation(KitchenStation s) async {
    final k = K.of(context);
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('${k.deleteStation} — ${k.name(s.nameFr, s.nameEn)}'),
        content: Text(k.deleteStationConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: T.destructive),
            onPressed: () => Navigator.pop(context, true),
            child: Text(k.deleteStation),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _run(() async {
      await KitchenApi.deleteStation(s.id);
      await _load();
    });
  }

  Future<void> _test(KitchenStation s) => _run(() async {
    final k = K.of(context);
    final r = await KitchenApi.testStation(s.id);
    if (!mounted) return;
    _toast(
      r.ok
          ? k.testOk(r.target)
          : !r.configured
          ? k.notConfigured
          : r.error == 'paper_out'
          ? k.paperOut
          : k.testFailed,
    );
  });

  Future<void> _route(String kind, String refId, String? stationId) =>
      _run(() async {
        final config = await KitchenApi.setRoute(kind, refId, stationId);
        if (mounted) setState(() => _config = config);
      });

  Future<void> _addOverride() async {
    final k = K.of(context);
    final l = L.of(context);
    final picked = await showDialog<Item>(
      context: context,
      builder: (context) {
        var query = '';
        return StatefulBuilder(
          builder: (context, setDialog) {
            final q = query.toLowerCase();
            final matches = _items
                .where(
                  (i) =>
                      q.isEmpty ||
                      i.nameFr.toLowerCase().contains(q) ||
                      i.nameEn.toLowerCase().contains(q),
                )
                .take(60)
                .toList();
            return AlertDialog(
              title: Text(k.pickItem),
              content: SizedBox(
                width: 420,
                height: 420,
                child: Column(
                  children: [
                    TextField(
                      autofocus: true,
                      decoration: const InputDecoration(
                        prefixIcon: Icon(LucideIcons.search),
                      ),
                      onChanged: (v) => setDialog(() => query = v),
                    ),
                    Expanded(
                      child: ListView(
                        children: [
                          for (final i in matches)
                            ListTile(
                              title: Text(l.name(i.nameFr, i.nameEn)),
                              onTap: () => Navigator.pop(context, i),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: Text(l.cancel),
                ),
              ],
            );
          },
        );
      },
    );
    if (picked == null) return;
    final first = _config?.stations.firstOrNull?.id ?? '';
    await _route('item', picked.id, first);
  }

  /// Dropdown values: '_default' (no mapping), '' (no ticket), or a station id.
  Widget _stationPicker(
    String? current,
    ValueChanged<String?> onChanged, {
    bool allowDefault = true,
  }) {
    final k = K.of(context);
    final stations = _config?.stations ?? const <KitchenStation>[];
    final value = current ?? (allowDefault ? '_default' : '');
    return DropdownButton<String>(
      value:
          stations.any((s) => s.id == value) ||
              value == '' ||
              value == '_default'
          ? value
          : (allowDefault ? '_default' : ''),
      onChanged: _busy ? null : (v) => onChanged(v == '_default' ? null : v),
      items: [
        if (allowDefault)
          DropdownMenuItem(value: '_default', child: Text(k.useDefault)),
        DropdownMenuItem(value: '', child: Text(k.noTicket)),
        for (final s in stations)
          DropdownMenuItem(
            value: s.id,
            child: Text(k.name(s.nameFr, s.nameEn)),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final k = K.of(context);
    final l = L.of(context);
    final config = _config;
    return Scaffold(
      appBar: AppBar(title: Text(k.setupTitle)),
      body: _error != null
          ? Center(child: Text(_error!))
          : config == null
          ? const DelayedSpinner()
          : ListView(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
              children: [
                SectionLabel(k.stations),
                for (final s in config.stations) _stationTile(s, k),
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerLeft,
                  child: OutlinedButton.icon(
                    icon: const Icon(LucideIcons.plus),
                    label: Text(k.addStation),
                    onPressed: _busy ? null : () => _editStation(),
                  ),
                ),
                const SizedBox(height: 20),
                SectionLabel(k.categories),
                for (final c in _categories)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(l.name(c.nameFr, c.nameEn)),
                    trailing: _stationPicker(
                      config.routeFor('category', c.id),
                      (v) => _route('category', c.id, v),
                    ),
                  ),
                const SizedBox(height: 20),
                SectionLabel(k.itemOverrides),
                for (final r in config.routes.where((r) => r.kind == 'item'))
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(() {
                      final item = _items
                          .where((i) => i.id == r.refId)
                          .firstOrNull;
                      return item == null
                          ? r.refId
                          : l.name(item.nameFr, item.nameEn);
                    }()),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _stationPicker(
                          r.stationId,
                          (v) => _route('item', r.refId, v ?? ''),
                          allowDefault: false,
                        ),
                        IconButton(
                          tooltip: l.cancel,
                          icon: const Icon(LucideIcons.x),
                          onPressed: _busy
                              ? null
                              : () => _route('item', r.refId, null),
                        ),
                      ],
                    ),
                  ),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    icon: const Icon(LucideIcons.plus),
                    label: Text(k.addOverride),
                    onPressed: _busy ? null : _addOverride,
                  ),
                ),
                const SizedBox(height: 20),
                SectionLabel(k.defaultStation),
                _stationPicker(
                  _settings.defaultStationId,
                  (v) => setState(
                    () => _settings = _settings.copyWith(
                      defaultStationId: v ?? '',
                    ),
                  ),
                  allowDefault: false,
                ),
                const SizedBox(height: 16),
                SectionLabel(k.ticketLanguage),
                DropdownButton<String>(
                  value: _settings.language,
                  onChanged: (v) => setState(
                    () => _settings = _settings.copyWith(language: v ?? ''),
                  ),
                  items: [
                    DropdownMenuItem(
                      value: '',
                      child: Text('${k.languageStore} (${config.language})'),
                    ),
                    const DropdownMenuItem(
                      value: 'fr',
                      child: Text('Français'),
                    ),
                    const DropdownMenuItem(value: 'en', child: Text('English')),
                    DropdownMenuItem(
                      value: 'both',
                      child: Text(k.languageBoth),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                SectionLabel(k.screenTimers),
                Row(
                  children: [
                    SizedBox(
                      width: 160,
                      child: TextField(
                        controller: _warn,
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(labelText: k.warnAfter),
                      ),
                    ),
                    const SizedBox(width: 16),
                    SizedBox(
                      width: 160,
                      child: TextField(
                        controller: _late,
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(labelText: k.lateAfter),
                      ),
                    ),
                  ],
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  activeThumbColor: T.primary,
                  title: Text(k.newOrderSound),
                  value: _settings.sound,
                  onChanged: (v) =>
                      setState(() => _settings = _settings.copyWith(sound: v)),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  height: T.minTouch,
                  child: FilledButton.icon(
                    icon: const Icon(LucideIcons.save),
                    label: Text(l.save),
                    onPressed: _busy ? null : _saveSettings,
                  ),
                ),
              ],
            ),
    );
  }

  Widget _stationTile(KitchenStation s, K k) {
    final where = s.printerHost.isEmpty
        ? k.receiptPrinter
        : '${s.printerHost}:${s.printerPort}';
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${s.nameFr} / ${s.nameEn}',
                    style: T.text(size: 17, weight: FontWeight.w700),
                  ),
                  Text(
                    [
                      k.outputName(s.output),
                      if (s.prints) '$where · ${s.paperMm} mm',
                    ].join(' · '),
                    style: T.small(),
                  ),
                ],
              ),
            ),
            if (s.prints)
              TextButton.icon(
                icon: const Icon(LucideIcons.printer, size: 18),
                label: Text(k.testPrint),
                onPressed: _busy ? null : () => _test(s),
              ),
            IconButton(
              tooltip: k.editStation,
              icon: const Icon(LucideIcons.pencil),
              onPressed: _busy ? null : () => _editStation(s),
            ),
            IconButton(
              tooltip: k.deleteStation,
              icon: const Icon(LucideIcons.trash2, color: T.destructive),
              onPressed: _busy ? null : () => _deleteStation(s),
            ),
          ],
        ),
      ),
    );
  }
}

class _StationDialog extends StatefulWidget {
  final KitchenStation? station;
  const _StationDialog({this.station});

  @override
  State<_StationDialog> createState() => _StationDialogState();
}

class _StationDialogState extends State<_StationDialog> {
  late final _fr = TextEditingController(text: widget.station?.nameFr ?? '');
  late final _en = TextEditingController(text: widget.station?.nameEn ?? '');
  late final _host = TextEditingController(
    text: widget.station?.printerHost ?? '',
  );
  late final _port = TextEditingController(
    text: '${widget.station?.printerPort ?? 9100}',
  );
  late String _output = widget.station?.output ?? 'printer';
  late int _paper = widget.station?.paperMm ?? 80;

  @override
  Widget build(BuildContext context) {
    final k = K.of(context);
    final l = L.of(context);
    return AlertDialog(
      title: Text(widget.station == null ? k.addStation : k.editStation),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _fr,
                decoration: InputDecoration(labelText: k.nameFr),
              ),
              TextField(
                controller: _en,
                decoration: InputDecoration(labelText: k.nameEn),
              ),
              const SizedBox(height: 12),
              Text(k.output, style: T.small()),
              SegmentedButton<String>(
                segments: [
                  ButtonSegment(value: 'printer', label: Text(k.outputPrinter)),
                  ButtonSegment(value: 'screen', label: Text(k.outputScreen)),
                  ButtonSegment(value: 'both', label: Text(k.outputBoth)),
                ],
                selected: {_output},
                onSelectionChanged: (v) => setState(() => _output = v.first),
              ),
              if (_output != 'screen') ...[
                TextField(
                  controller: _host,
                  keyboardType: TextInputType.url,
                  decoration: InputDecoration(labelText: k.printerHost),
                ),
                TextField(
                  controller: _port,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(labelText: k.port),
                ),
                const SizedBox(height: 12),
                Text(k.paper, style: T.small()),
                SegmentedButton<int>(
                  segments: const [
                    ButtonSegment(value: 80, label: Text('80 mm')),
                    ButtonSegment(value: 58, label: Text('58 mm')),
                  ],
                  selected: {_paper},
                  onSelectionChanged: (v) => setState(() => _paper = v.first),
                ),
              ],
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
          onPressed: () {
            final fr = _fr.text.trim(), en = _en.text.trim();
            if (fr.isEmpty && en.isEmpty) return;
            Navigator.pop(
              context,
              KitchenStation(
                id: widget.station?.id ?? '',
                nameFr: fr.isEmpty ? en : fr,
                nameEn: en.isEmpty ? fr : en,
                output: _output,
                printerHost: _host.text.trim(),
                printerPort: int.tryParse(_port.text) ?? 9100,
                paperMm: _paper,
                sortOrder: widget.station?.sortOrder ?? 0,
              ),
            );
          },
          child: Text(l.save),
        ),
      ],
    );
  }
}
