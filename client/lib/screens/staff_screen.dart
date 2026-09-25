import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';

/// Staff administration (manage_staff). The tablet owns its staff: changes
/// apply here at once, offline, and are pushed up to the owner portal when it
/// next connects (one-way sync).
class StaffScreen extends StatefulWidget {
  const StaffScreen({super.key});

  @override
  State<StaffScreen> createState() => _StaffScreenState();
}

class _StaffScreenState extends State<StaffScreen> {
  List<ManagedStaff>? _staff;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final staff = await Api.managedStaff();
      if (mounted) setState(() => _staff = staff);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _edit([ManagedStaff? existing]) async {
    final changed = await showDialog<bool>(
      context: context,
      builder: (_) => _StaffDialog(existing: existing),
    );
    if (changed == true) _load();
  }

  Future<void> _toggleActive(ManagedStaff s, bool active) async {
    try {
      await Api.updateStaff(s.id, active: active);
      _load();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final staff = _staff;
    return Scaffold(
      appBar: AppBar(title: Text(l.staffTitle), actions: const [LangActions()]),
      floatingActionButton: staff == null
          ? null
          : FloatingActionButton.extended(
              onPressed: () => _edit(),
              icon: const Icon(LucideIcons.userPlus),
              label: Text(l.staffAdd),
            ),
      body: _error != null
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(_error!),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: () {
                      setState(() => _error = null);
                      _load();
                    },
                    child: Text(l.retry),
                  ),
                ],
              ),
            )
          : staff == null
          ? const DelayedSpinner()
          : Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 640),
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 96),
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(l.staffSyncHint, style: T.small()),
                    ),
                    for (final s in staff)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(
                          s.isManager
                              ? LucideIcons.shieldCheck
                              : LucideIcons.user,
                        ),
                        title: Text(s.name, style: T.text(size: 16)),
                        subtitle: Text(
                          '${s.isManager ? l.roleManager : l.roleServer} · '
                          '${s.active ? l.staffActive : l.staffInactive}',
                          style: T.small(),
                        ),
                        onTap: () => _edit(s),
                        trailing: Switch(
                          value: s.active,
                          onChanged: (v) => _toggleActive(s, v),
                        ),
                      ),
                  ],
                ),
              ),
            ),
    );
  }
}

class _StaffDialog extends StatefulWidget {
  final ManagedStaff? existing;
  const _StaffDialog({this.existing});

  @override
  State<_StaffDialog> createState() => _StaffDialogState();
}

class _StaffDialogState extends State<_StaffDialog> {
  late final _name = TextEditingController(text: widget.existing?.name ?? '');
  final _pin = TextEditingController();
  late String _role = widget.existing?.role ?? 'SERVER';
  bool _busy = false;

  bool get _isNew => widget.existing == null;

  Future<void> _save() async {
    final l = L.of(context);
    final name = _name.text.trim();
    final pin = _pin.text.trim();
    if (name.isEmpty) return _toast(l.staffNameRequired);
    final pinOk = RegExp(r'^\d{4}$').hasMatch(pin);
    if ((_isNew || pin.isNotEmpty) && !pinOk) return _toast(l.staffPinInvalid);
    setState(() => _busy = true);
    try {
      if (_isNew) {
        await Api.createStaff(name, _role, pin);
      } else {
        final s = widget.existing!;
        await Api.updateStaff(
          s.id,
          name: name == s.name ? null : name,
          role: _role == s.role ? null : _role,
        );
        if (pin.isNotEmpty) await Api.resetStaffPin(s.id, pin);
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete() async {
    final l = L.of(context);
    final s = widget.existing!;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(l.staffDeleteConfirm(s.name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: T.destructive),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.staffDelete),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    try {
      await Api.deleteStaff(s.id);
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return AlertDialog(
      title: Text(_isNew ? l.staffAdd : l.staffEdit),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _name,
              autofocus: _isNew,
              textCapitalization: TextCapitalization.words,
              decoration: InputDecoration(labelText: l.staffName),
            ),
            const SizedBox(height: 12),
            SegmentedButton<String>(
              segments: [
                ButtonSegment(value: 'SERVER', label: Text(l.roleServer)),
                ButtonSegment(value: 'MANAGER', label: Text(l.roleManager)),
              ],
              selected: {_role},
              onSelectionChanged: (v) => setState(() => _role = v.first),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _pin,
              keyboardType: TextInputType.number,
              obscureText: true,
              maxLength: 4,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(
                labelText: _isNew ? l.staffPin4 : l.staffResetPin,
              ),
            ),
          ],
        ),
      ),
      actions: [
        if (!_isNew)
          TextButton(
            onPressed: _busy ? null : _delete,
            child: Text(
              l.staffDelete,
              style: const TextStyle(color: T.destructive),
            ),
          ),
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: Text(l.cancel),
        ),
        FilledButton(onPressed: _busy ? null : _save, child: Text(l.save)),
      ],
    );
  }
}
