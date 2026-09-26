import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'retail_i18n.dart';
import 'scan_detector.dart';
import 'sp_theme.dart';

/// The ID check before age-restricted items can be paid for. Two ways:
///
/// - scan the licence: a 2D HID scanner types the AAMVA text from the back
///   of a US / Canadian driver's licence; the store reads the date of birth
///   and expiry, and computes the age in its own time zone;
/// - or pick the date of birth from dropdowns, confirming the ID was seen.
///
/// Only the outcome is kept (pass/fail, age in years, who, when). Resolves to
/// the store's [AgeCheckResult], or null when dismissed.
class AgeCheckDialog extends StatefulWidget {
  final int saleId;
  final int legalAge;

  /// The store allows a visual check over this age and nothing on the sale
  /// forbids it (tobacco and vape always need the ID); null = ID only.
  final int? looksOver;
  const AgeCheckDialog({
    super.key,
    required this.saleId,
    required this.legalAge,
    this.looksOver,
  });

  static Future<AgeCheckResult?> show(
    BuildContext context, {
    required int saleId,
    required int legalAge,
    int? looksOver,
  }) => showDialog<AgeCheckResult>(
    context: context,
    builder: (_) => AgeCheckDialog(
      saleId: saleId,
      legalAge: legalAge,
      looksOver: looksOver,
    ),
  );

  @override
  State<AgeCheckDialog> createState() => _AgeCheckDialogState();
}

class _AgeCheckDialogState extends State<AgeCheckDialog> {
  final _capture = IdScanCapture();
  Timer? _idle;
  int? _month, _day, _year;
  bool _sawId = false;
  bool _busy = false;
  AgeCheckResult? _result;

  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_onKey);
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    _idle?.cancel();
    super.dispose();
  }

  /// The licence arrives as a burst of keys; it's done once they stop.
  bool _onKey(KeyEvent e) {
    if (e is KeyUpEvent || _busy) return false;
    final key = e.logicalKey;
    if (key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.numpadEnter) {
      _capture.enter(e.timeStamp);
    } else if (e.character != null && e.character!.isNotEmpty) {
      _capture.key(e.character!, e.timeStamp);
    } else {
      return false;
    }
    final at = e.timeStamp;
    _idle?.cancel();
    _idle = Timer(_capture.idle + const Duration(milliseconds: 20), () {
      final text = _capture.complete(
        at + _capture.idle + const Duration(milliseconds: 1),
      );
      if (text != null) _submit(scan: text);
    });
    return false;
  }

  Future<void> _submit({String? scan, bool visual = false}) async {
    if (_busy) return;
    String? dob;
    if (scan == null && !visual) {
      if (_year == null || _month == null || _day == null) return;
      dob =
          '${_year!.toString().padLeft(4, '0')}-${_month!.toString().padLeft(2, '0')}-${_day!.toString().padLeft(2, '0')}';
    }
    setState(() => _busy = true);
    try {
      final r = await Api.ageCheck(
        widget.saleId,
        scan: scan,
        dateOfBirth: dob,
        cashierSawId: visual || _sawId,
        visual: visual,
      );
      if (!mounted) return;
      setState(() => _result = r);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  int _daysIn(int? year, int? month) {
    if (month == null) return 31;
    return DateTime(year ?? 2000, month + 1, 0).day;
  }

  @override
  Widget build(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final result = _result;
    return AlertDialog(
      titlePadding: const EdgeInsets.fromLTRB(24, 22, 24, 0),
      contentPadding: const EdgeInsets.fromLTRB(24, 16, 24, 8),
      title: Row(
        children: [
          Icon(LucideIcons.idCard, color: c.sage),
          const SizedBox(width: 10),
          Text(r.ageCheckTitle(widget.legalAge)),
        ],
      ),
      content: SizedBox(
        width: 560,
        child: result != null ? _verdict(r, c, result) : _ask(r, c),
      ),
      actions: [
        if (result == null) ...[
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(r.cancel),
          ),
          FilledButton(
            onPressed:
                _busy ||
                    _year == null ||
                    _month == null ||
                    _day == null ||
                    !_sawId
                ? null
                : () => _submit(),
            child: Text(r.verify),
          ),
        ] else if (result.passed)
          FilledButton(
            onPressed: () => Navigator.pop(context, result),
            child: Text(r.done),
          )
        else ...[
          TextButton(
            onPressed: () => setState(() => _result = null),
            child: Text(r.tryAgain),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: c.bad,
              foregroundColor: Colors.white,
            ),
            onPressed: () => Navigator.pop(context, result),
            child: Text(r.removeRestricted),
          ),
        ],
      ],
    );
  }

  Widget _ask(R r, SpColors c) {
    final now = DateTime.now();
    final years = [for (var y = now.year - 16; y >= now.year - 100; y--) y];
    final days = _daysIn(_year, _month);
    if (_day != null && _day! > days) _day = days;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: c.surfaceAlt,
            borderRadius: T.radiusLarge,
            border: Border.all(color: c.border),
          ),
          child: Row(
            children: [
              Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                  color: c.sage,
                  borderRadius: T.radiusMedium,
                ),
                child: Icon(LucideIcons.scanLine, color: c.onSage, size: 30),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      r.scanIdTitle,
                      style: T.text(weight: FontWeight.w700, color: c.text),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      r.scanIdHint,
                      style: T.text(size: 15, color: c.textMuted),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: c.sage,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          _busy ? '…' : r.listening,
                          style: T.text(
                            size: 14,
                            color: c.sage,
                            weight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        Text(
          r.orEnterDob,
          style: T.text(size: 16, weight: FontWeight.w600, color: c.text),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              flex: 3,
              child: _drop<int>(
                r.month,
                _month,
                [for (var m = 1; m <= 12; m++) m],
                (m) => r.months[m - 1],
                (v) => setState(() => _month = v),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 2,
              child: _drop<int>(
                r.day,
                _day,
                [for (var d = 1; d <= days; d++) d],
                (d) => '$d',
                (v) => setState(() => _day = v),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 3,
              child: _drop<int>(
                r.year,
                _year,
                years,
                (y) => '$y',
                (v) => setState(() => _year = v),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          value: _sawId,
          activeColor: c.sage,
          onChanged: (v) => setState(() => _sawId = v ?? false),
          title: Text(r.sawId, style: T.text(size: 16, color: c.text)),
        ),
        Text(r.privacyNote, style: T.text(size: 13, color: c.textMuted)),
        if (widget.looksOver != null) ...[
          const SizedBox(height: 12),
          OutlinedButton.icon(
            key: const Key('age-visual'),
            onPressed: _busy ? null : () => _submit(visual: true),
            icon: const Icon(Icons.visibility_rounded),
            label: Text(r.looksOver(widget.looksOver!)),
          ),
        ],
      ],
    );
  }

  Widget _drop<V>(
    String label,
    V? value,
    List<V> options,
    String Function(V) text,
    ValueChanged<V?> onChanged,
  ) => DropdownButtonFormField<V>(
    initialValue: value,
    isExpanded: true,
    menuMaxHeight: 360,
    decoration: InputDecoration(labelText: label),
    items: [
      for (final o in options)
        DropdownMenuItem<V>(value: o, child: Text(text(o))),
    ],
    onChanged: onChanged,
  );

  Widget _verdict(R r, SpColors c, AgeCheckResult result) {
    final ok = result.passed;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: ok ? c.okSoft : c.badSoft,
        borderRadius: T.radiusLarge,
      ),
      child: Row(
        children: [
          Icon(
            ok ? LucideIcons.circleCheck : LucideIcons.circleX,
            color: ok ? c.ok : c.bad,
            size: 40,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Text(
              ok
                  ? r.passed(result.ageYears ?? widget.legalAge)
                  : r.failed(result.reason, result.ageYears, result.legalAge),
              style: T.text(
                size: 20,
                weight: FontWeight.w700,
                color: ok ? c.ok : c.bad,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
