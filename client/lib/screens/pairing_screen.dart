import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../connection_monitor.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'login_screen.dart';

/// First-run pairing: this terminal introduces itself to a venue with a
/// single-use code minted in the owner portal, and receives the per-device
/// token that must accompany every request from then on. Shown when /health
/// says pairingRequired and no token is stored, or when the store answers
/// 401 device_required / device_revoked mid-flight.
class PairingScreen extends StatefulWidget {
  const PairingScreen({super.key});

  @override
  State<PairingScreen> createState() => _PairingScreenState();
}

class _PairingScreenState extends State<PairingScreen> {
  // Prefill with whatever server we're currently talking to — arriving here
  // means /health answered there (or it's the platform default on a dev box).
  late final _addressCtrl = TextEditingController(text: Api.baseUrl);
  final _codeCtrl = TextEditingController();
  final _nameCtrl = TextEditingController(text: 'Terminal');
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // This screen owns its connectivity story — keep the global overlay away.
    ConnectionMonitor.instance.pushSuppress();
  }

  @override
  void dispose() {
    ConnectionMonitor.instance.popSuppress();
    _addressCtrl.dispose();
    _codeCtrl.dispose();
    _nameCtrl.dispose();
    super.dispose();
  }

  Future<void> _pair() async {
    if (_busy) return;
    final l = L(Prefs.instance.isEn);
    final address = Api.normalizeVenueAddress(_addressCtrl.text);
    if (address == null) {
      setState(() => _error = l.enterVenueAddress);
      return;
    }
    final code = Api.normalizePairingCode(_codeCtrl.text);
    if (code == null) {
      setState(() => _error = l.enterPairingCode);
      return;
    }
    final name = _nameCtrl.text.trim().isEmpty
        ? 'Terminal'
        : _nameCtrl.text.trim();
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await Api.pair(address, code, name);
      if (!mounted) return;
      Navigator.of(
        context,
      ).pushReplacement(MaterialPageRoute(builder: (_) => const LoginScreen()));
    } on ApiException catch (e) {
      // bad_pairing_code / pairing_unavailable / cloud_unreachable /
      // rate_limited — all localized by ApiException.toString().
      if (mounted) setState(() => _error = '$e');
    } catch (_) {
      // transport failure: wrong address, no route, server down
      if (mounted) {
        setState(() => _error = L(Prefs.instance.isEn).pairNetworkError);
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
                padding: const EdgeInsets.all(24),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 440),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Icon(LucideIcons.link, size: 40, color: T.accent),
                      const SizedBox(height: 12),
                      Text(
                        'Copper Lantern POS',
                        textAlign: TextAlign.center,
                        style: T.headline(),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        l.pairTerminalTitle,
                        textAlign: TextAlign.center,
                        style: T.text(weight: FontWeight.w600),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        l.pairTerminalIntro,
                        textAlign: TextAlign.center,
                        style: T.small(),
                      ),
                      const SizedBox(height: 24),
                      TextField(
                        controller: _addressCtrl,
                        keyboardType: TextInputType.url,
                        autocorrect: false,
                        decoration: InputDecoration(
                          labelText: l.pairVenueAddressLabel,
                          hintText: 'venue.example.com',
                        ),
                      ),
                      const SizedBox(height: 14),
                      TextField(
                        controller: _codeCtrl,
                        autocorrect: false,
                        textAlign: TextAlign.center,
                        textCapitalization: TextCapitalization.characters,
                        inputFormatters: [_UpperCaseFormatter()],
                        style: T
                            .text(size: 28, weight: FontWeight.w600)
                            .copyWith(
                              fontFamily: 'monospace',
                              letterSpacing: 4,
                            ),
                        decoration: InputDecoration(
                          labelText: l.pairingCodeLabel,
                          hintText: 'ABCD-EFGH',
                        ),
                      ),
                      const SizedBox(height: 14),
                      TextField(
                        controller: _nameCtrl,
                        decoration: InputDecoration(
                          labelText: l.deviceNameLabel,
                        ),
                      ),
                      const SizedBox(height: 20),
                      SizedBox(
                        height: 24,
                        child: _error != null
                            ? Text(
                                _error!,
                                textAlign: TextAlign.center,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: T.small(color: T.destructive),
                              )
                            : const SizedBox.shrink(),
                      ),
                      const SizedBox(height: 8),
                      FilledButton.icon(
                        onPressed: _busy ? null : _pair,
                        icon: _busy
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(LucideIcons.link),
                        label: Text(l.pairAction),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Pairing codes are shown uppercase in the portal — type-as-shown.
class _UpperCaseFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) => newValue.copyWith(text: newValue.text.toUpperCase());
}
