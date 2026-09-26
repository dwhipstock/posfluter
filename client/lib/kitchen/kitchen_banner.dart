import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'kitchen_i18n.dart';

/// "Kitchen printer offline — 2 tickets waiting", with Retry and Cancel.
/// Shows only while kitchen tickets are on and a waiting ticket has failed to
/// print at least once; clears itself when the printer catches up. Polls the
/// store's queue (local, cheap); never blocks anything on screen.
class KitchenQueueBanner extends StatefulWidget {
  const KitchenQueueBanner({super.key});

  @override
  State<KitchenQueueBanner> createState() => _KitchenQueueBannerState();
}

class _KitchenQueueBannerState extends State<KitchenQueueBanner> {
  Timer? _poll;
  KitchenQueueStatus? _status;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    if (!KitchenApi.enabled) return;
    _load();
    _poll = Timer.periodic(const Duration(seconds: 8), (_) => _load());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final s = await KitchenApi.status();
      if (mounted) setState(() => _status = s);
    } catch (_) {} // a missed poll is fine; the next one catches up
  }

  Future<void> _retry() async {
    setState(() => _busy = true);
    try {
      await KitchenApi.retry();
      await Future<void>.delayed(const Duration(seconds: 2));
      await _load();
    } catch (_) {
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _cancel() async {
    final k = K.of(context);
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(k.cancelTickets),
        content: Text(k.cancelConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: T.destructive),
            onPressed: () => Navigator.pop(context, true),
            child: Text(k.cancelTickets),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await KitchenApi.cancel();
    } catch (_) {}
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    if (!KitchenApi.enabled || s == null || !s.failing || s.waiting == 0) {
      return const SizedBox.shrink();
    }
    final k = K.of(context);
    final message = s.lastError == 'paper_out'
        ? '${k.paperOut} — ${k.ticketsWaiting(s.waiting)}'
        : s.lastError == 'printer_not_configured'
        ? '${k.notConfigured} — ${k.ticketsWaiting(s.waiting)}'
        : k.printerOffline(s.waiting);
    return Material(
      color: T.destructive,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        child: Row(
          children: [
            const Icon(LucideIcons.printer, size: 20, color: Colors.white),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                message,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            TextButton.icon(
              style: TextButton.styleFrom(
                foregroundColor: Colors.white,
                minimumSize: const Size(0, 48),
              ),
              icon: _busy
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(LucideIcons.refreshCw, size: 18),
              label: Text(k.retry),
              onPressed: _busy ? null : _retry,
            ),
            TextButton(
              style: TextButton.styleFrom(
                foregroundColor: Colors.white,
                minimumSize: const Size(0, 48),
              ),
              onPressed: _cancel,
              child: Text(k.cancelTickets),
            ),
          ],
        ),
      ),
    );
  }
}
