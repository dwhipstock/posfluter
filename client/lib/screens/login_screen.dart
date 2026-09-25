import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/brand.dart';
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
    _loadVenue();
  }

  Future<void> _loadVenue() async {
    try {
      await Api.loadVenueName();
      if (mounted) setState(() {});
    } catch (_) {} // the house brand shows until the store answers
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
        setState(
          () => _error = e is ApiException
              ? '$e'
              : L.of(context).loginInterrupted,
        );
        _padKey.currentState?.clear();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: LayoutBuilder(
        builder: (context, c) {
          final wide = c.maxWidth >= 900;
          final signIn = _signInPane(context, compact: !wide);
          if (wide) {
            // landscape tablet: brand panel left, sign-in right
            return Row(
              children: [
                SizedBox(
                  width: (c.maxWidth * .38).clamp(360.0, 560.0),
                  child: _BrandPanel(logoSize: c.maxHeight * .36),
                ),
                Expanded(child: signIn),
              ],
            );
          }
          return Column(
            children: [
              SizedBox(
                height: (c.maxHeight * .3).clamp(220.0, 420.0),
                child: _BrandPanel(
                  logoSize: (c.maxHeight * .16).clamp(110.0, 220.0),
                  horizontal: true,
                ),
              ),
              Expanded(child: signIn),
            ],
          );
        },
      ),
    );
  }

  Widget _signInPane(BuildContext context, {required bool compact}) {
    final l = L.of(context);
    return SafeArea(
      left: false,
      child: Stack(
        children: [
          const Positioned(top: 12, right: 16, child: LangActions()),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    l.whoClockingIn,
                    style: T.text(
                      size: 28,
                      weight: FontWeight.w700,
                      color: T.navy,
                    ),
                  ),
                  const SizedBox(height: 20),
                  if (_staff.isNotEmpty)
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 680),
                      child: Wrap(
                        spacing: 14,
                        runSpacing: 14,
                        alignment: WrapAlignment.center,
                        children: [for (final s in _staff) _staffCard(s)],
                      ),
                    ),
                  SizedBox(height: compact ? 20 : 28),
                  SizedBox(
                    height: 26,
                    child: _error != null
                        ? Text(
                            _error!,
                            style: T.text(
                              size: 16,
                              color: T.destructive,
                              weight: FontWeight.w600,
                            ),
                          )
                        : Text(
                            l.enterPin,
                            style: T.text(size: 16, color: T.textMuted),
                          ),
                  ),
                  const SizedBox(height: 8),
                  PinPad(
                    key: _padKey,
                    onComplete: _tryLogin,
                    keySize: const Size(104, 76),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _staffCard(Staff s) {
    final selected = _selected == s.id;
    final manager = s.role == 'MANAGER';
    return SizedBox(
      width: 208, // fits "directeur (Manager)" untruncated
      child: PosPanel(
        raised: !selected,
        color: selected ? T.surfaceAlt : T.surface,
        borderColor: selected ? T.navy : T.border,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
        onTap: () => setState(() => _selected = s.id),
        child: Column(
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: selected ? T.navy : T.surfaceAlt,
              ),
              child: Icon(
                manager ? LucideIcons.shieldCheck : LucideIcons.user,
                size: 24,
                color: selected ? T.onPrimary : T.navy,
              ),
            ),
            const SizedBox(height: 10),
            Text(
              s.name,
              maxLines: 2,
              textAlign: TextAlign.center,
              overflow: TextOverflow.ellipsis,
              style: T.text(size: 17, weight: FontWeight.w600),
            ),
            const SizedBox(height: 2),
            Text(
              s.role,
              style: T
                  .small(weight: FontWeight.w600)
                  .copyWith(fontSize: 11, letterSpacing: 1.1),
            ),
          ],
        ),
      ),
    );
  }
}

/// Navy brand band: the badge, the venue brand and its location.
class _BrandPanel extends StatelessWidget {
  final double logoSize;
  final bool horizontal;
  const _BrandPanel({required this.logoSize, this.horizontal = false});

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final location = Api.venueLocation;
    final names = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: horizontal
          ? CrossAxisAlignment.start
          : CrossAxisAlignment.center,
      children: [
        Text(
          Api.venueBrand,
          textAlign: TextAlign.center,
          style: T.text(size: 34, weight: FontWeight.w700, color: T.onPrimary),
        ),
        if (location != null) ...[
          const SizedBox(height: 4),
          Text(
            location,
            textAlign: TextAlign.center,
            style: T.text(size: 22, weight: FontWeight.w500, color: T.pending),
          ),
        ],
        const SizedBox(height: 14),
        Text(
          l.staffTerminal.toUpperCase(),
          style: T
              .small(color: T.onNavyMuted, weight: FontWeight.w600)
              .copyWith(letterSpacing: 2),
        ),
      ],
    );
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [T.navy, T.navyDeep],
        ),
      ),
      child: SafeArea(
        right: false,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: horizontal
                ? Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      BrandLogo(size: logoSize, ring: true),
                      const SizedBox(width: 28),
                      Flexible(child: names),
                    ],
                  )
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      BrandLogo(size: logoSize, ring: true),
                      const SizedBox(height: 32),
                      names,
                    ],
                  ),
          ),
        ),
      ),
    );
  }
}
