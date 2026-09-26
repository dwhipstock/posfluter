import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import '../retail/sp_theme.dart';
import '../screens/login_screen.dart';
import '../widgets/brand.dart';
import 'count_screens.dart';
import 'receive_screen.dart';
import 'stock_i18n.dart';
import 'stock_queue.dart';
import 'stock_widgets.dart';

/// The stock app's home (a phone in the aisles): Count or Receive, what is
/// waiting to go to the store, and sign out. Sends queued work whenever the
/// store is reachable.
class StockHomeScreen extends StatefulWidget {
  final StockQueue? queue;
  const StockHomeScreen({super.key, this.queue});

  @override
  State<StockHomeScreen> createState() => _StockHomeScreenState();
}

class _StockHomeScreenState extends State<StockHomeScreen> {
  StockQueue get _queue => widget.queue ?? StockQueue.instance;

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    await _queue.load();
    _queue.flush();
    refreshStockReference(_queue);
  }

  Future<void> _signOut() async {
    await Api.logout();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (_) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    final user = Api.currentUser;
    final retail = StoreProfile.current.isRetail;
    return StockAutoFlush(
      queue: _queue,
      child: Scaffold(
        backgroundColor: c.background,
        appBar: AppBar(
          backgroundColor: c.sageDeep,
          foregroundColor: Colors.white,
          titleSpacing: 12,
          title: Row(
            children: [
              const BrandLogo(size: 36, ring: true),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      StoreProfile.current.isSagePoppy
                          ? 'Sage & Poppy'
                          : Api.venueBrand,
                      overflow: TextOverflow.ellipsis,
                      style: T.text(
                        size: 18,
                        weight: FontWeight.w700,
                        color: Colors.white,
                      ),
                    ),
                    Text(
                      '${s.appTitle}${user == null ? '' : ' · ${user.name}'}',
                      overflow: TextOverflow.ellipsis,
                      style: T.text(size: 13, color: const Color(0xFFD7E4D3)),
                    ),
                  ],
                ),
              ),
            ],
          ),
          actions: [
            const LangActions(color: Colors.white),
            IconButton(
              key: const Key('stock-sign-out'),
              tooltip: s.signOut,
              icon: const Icon(LucideIcons.logOut),
              onPressed: _signOut,
            ),
          ],
        ),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 720),
              child: ListView(
                padding: const EdgeInsets.only(bottom: 24),
                children: [
                  StockSyncBar(queue: _queue),
                  if (!retail)
                    Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        s.notRetail,
                        style: T.text(size: 16, color: c.text),
                      ),
                    ),
                  if (retail) ...[
                    _tile(
                      context,
                      key: const Key('home-count'),
                      icon: LucideIcons.clipboardList,
                      title: s.count,
                      subtitle: s.countHint,
                      color: c.sage,
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => CountListScreen(queue: _queue),
                        ),
                      ),
                    ),
                    _tile(
                      context,
                      key: const Key('home-receive'),
                      icon: LucideIcons.packagePlus,
                      title: s.receive,
                      subtitle: s.receiveHint,
                      color: c.poppy,
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => ReceiveScreen(queue: _queue),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _tile(
    BuildContext context, {
    required Key key,
    required IconData icon,
    required String title,
    required String subtitle,
    required Color color,
    required VoidCallback onTap,
  }) {
    final c = SpColors.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Material(
        key: key,
        color: c.surface,
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          borderRadius: BorderRadius.circular(18),
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: c.border),
            ),
            child: Row(
              children: [
                Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    color: color,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Icon(icon, color: Colors.white, size: 32),
                ),
                const SizedBox(width: 18),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: T.text(
                          size: 22,
                          weight: FontWeight.w700,
                          color: c.text,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        style: T.text(size: 15, color: c.textMuted),
                      ),
                    ],
                  ),
                ),
                Icon(LucideIcons.chevronRight, color: c.textMuted),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
