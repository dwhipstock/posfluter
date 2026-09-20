import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/pin_pad.dart';
import 'zones_screen.dart';

/// "Who's clocking in?" — staff tiles + a big centered PIN pad. Nothing else.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _padKey = GlobalKey<PinPadState>();
  List<Staff> _staff = [];
  String? _selected;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _loadStaff();
  }

  Future<void> _loadStaff() async {
    try {
      final staff = await Api.staff();
      if (mounted) setState(() => _staff = staff);
    } catch (
      _
    ) {} // tiles are progressive enhancement; the pad works without them
  }

  Future<void> _tryLogin(String pin) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await Api.login(pin);
      if (!mounted) return;
      Navigator.of(
        context,
      ).pushReplacement(MaterialPageRoute(builder: (_) => const ZonesScreen()));
    } catch (e) {
      if (mounted) {
        setState(() => _error = '$e');
        _padKey.currentState?.clear();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            const Positioned(top: 4, right: 8, child: LangActions()),
            Center(
              child: SingleChildScrollView(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text('Copper Lantern POS', style: T.headline()),
                    const SizedBox(height: 4),
                    Text(l.whoClockingIn, style: T.small()),
                    const SizedBox(height: 20),
                    if (_staff.isNotEmpty)
                      ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 480),
                        child: Wrap(
                          spacing: 10,
                          runSpacing: 10,
                          alignment: WrapAlignment.center,
                          children: [
                            for (final s in _staff)
                              SizedBox(
                                width:
                                    190, // fits "directeur (Manager)" untruncated
                                child: PosPanel(
                                  color: _selected == s.id
                                      ? T.surfaceAlt
                                      : T.surface,
                                  borderColor: _selected == s.id
                                      ? T.accent
                                      : T.border,
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 12,
                                    vertical: 14,
                                  ),
                                  onTap: () => setState(() => _selected = s.id),
                                  child: Column(
                                    children: [
                                      Icon(
                                        s.role == 'MANAGER'
                                            ? LucideIcons.shieldCheck
                                            : LucideIcons.user,
                                        size: 26,
                                        color: _selected == s.id
                                            ? T.accent
                                            : T.textMuted,
                                      ),
                                      const SizedBox(height: 6),
                                      Text(
                                        s.name,
                                        maxLines: 2,
                                        textAlign: TextAlign.center,
                                        overflow: TextOverflow.ellipsis,
                                        style: T.small(
                                          color: T.textPrimary,
                                          weight: FontWeight.w600,
                                        ),
                                      ),
                                      Text(
                                        s.role,
                                        style: T.small().copyWith(fontSize: 11),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    const SizedBox(height: 20),
                    SizedBox(
                      height: 24,
                      child: _error != null
                          ? Text(_error!, style: T.small(color: T.destructive))
                          : Text(l.enterPin, style: T.small()),
                    ),
                    const SizedBox(height: 8),
                    PinPad(key: _padKey, onComplete: _tryLogin),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
