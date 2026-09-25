import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../server_discovery.dart';

/// Manager-only venue settings: the DATA the owner tunes at runtime
/// (payments, fees, receipt identity). Policy lives in code, not here.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  VenueSettings? _settings;
  bool _busy = false;
  String? _error;

  final _card = TextEditingController();
  final _bankName = TextEditingController();
  final _bankNumber = TextEditingController();
  final _bankHolder = TextEditingController();
  final _serviceCharge = TextEditingController();
  final _corkage = TextEditingController();
  final _footer = TextEditingController();
  final _phone = TextEditingController();
  final _address = TextEditingController();
  final _idleMinutes = TextEditingController();
  final _escalateSeconds = TextEditingController();
  final _alertVolume = TextEditingController();
  final _printerIp = TextEditingController();
  final _printerPort = TextEditingController();
  // Store server URL is device-local (not a venue setting) — persisted via
  // shared device storage, applied on next launch. Separate from the printer IP.
  final _serverUrl = TextEditingController();
  bool _alertsEnabled = true;
  bool _scanning = false;
  bool _scanningPrinter = false;
  // Owner reporting-portal URL from the store (GET /cloud/info). Tri-state:
  // _portalLoaded gates the section so it doesn't flash the "not configured"
  // hint while the fetch is still in flight.
  String? _portalUrl;
  String? _staffAppUrl;
  bool _portalLoaded = false;

  @override
  void initState() {
    super.initState();
    _serverUrl.text = Api.serverUrlOverride ?? '';
    _load();
    _loadPortalUrl();
  }

  /// Best-effort fetch of the reporting-portal URL. A failure just leaves the
  /// section hidden — it never blocks the settings screen.
  Future<void> _loadPortalUrl() async {
    try {
      final info = await Api.cloudInfo();
      if (!mounted) return;
      setState(() {
        _portalUrl = info['portalUrl'] as String?;
        final storeUrl = Api.phoneQrBaseUrl(info['storeUrl'] as String?);
        _staffAppUrl = Api.usesEmbeddedStore
            ? (storeUrl == null ? null : '$storeUrl/staff-app')
            : '${Api.baseUrl}/staff-app';
        _portalLoaded = true;
      });
    } catch (_) {
      if (mounted) setState(() => _portalLoaded = true);
    }
  }

  /// Auto-discover the restaurant on Wi-Fi and switch immediately. There is
  /// no address form in the normal path; the advanced field is only a fallback.
  Future<void> _scanServer() async {
    if (_scanning) return;
    setState(() => _scanning = true);
    final l = L.of(context);
    final found = await ServerDiscovery.discover();
    if (found != null) await Api.useDiscovered(found);
    if (!mounted) return;
    setState(() {
      _scanning = false;
      if (found != null) _serverUrl.clear();
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          found != null ? l.restaurantConnectionReady : l.serverNotFound,
        ),
      ),
    );
  }

  /// Auto-discover the thermal printer on the LAN (open :9100) and fill the IP.
  /// The tablet does the scan (it sees the real venue subnet); Save persists the
  /// IP to the store, which then prints to it — no hand-typed printer IP.
  Future<void> _scanPrinter() async {
    if (_scanningPrinter) return;
    setState(() => _scanningPrinter = true);
    final l = L.of(context);
    final found = await ServerDiscovery.discoverPrinters();
    if (!mounted) return;
    setState(() {
      _scanningPrinter = false;
      if (found.isNotEmpty) _printerIp.text = found.first;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          found.isEmpty
              ? l.printerNotFound
              : found.length == 1
              ? l.printerFoundAt(found.first)
              : l.printersFound(found.length, found.first),
        ),
      ),
    );
  }

  Future<void> _load() async {
    try {
      final s = await Api.settings();
      if (!mounted) return;
      setState(() {
        _settings = s;
        _card.text = s.cardProcessor;
        _bankName.text = s.bankName;
        _bankNumber.text = s.bankAccountNumber;
        _bankHolder.text = s.bankAccountName;
        _serviceCharge.text = '${s.serviceChargePercent}';
        _corkage.text = '${s.corkagePerBottleCents ~/ 100}';
        _footer.text = s.receiptFooter;
        _phone.text = s.venuePhone;
        _address.text = s.venueAddress;
        _idleMinutes.text = '${s.sessionIdleMinutes}';
        _escalateSeconds.text = '${s.pendingAlertEscalateSeconds}';
        _alertVolume.text = '${s.pendingAlertVolume}';
        _printerIp.text = s.printerIp;
        _printerPort.text = '${s.printerPort}';
        _alertsEnabled = s.pendingAlertsEnabled;
      });
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  /// PATCH the current form to the server. Shared by Save and Test print
  /// (the printer must be persisted before the server can test it).
  Future<void> _persist() => Api.updateSettings({
    'cardProcessor': _card.text,
    'bankName': _bankName.text,
    'bankAccountNumber': _bankNumber.text,
    'bankAccountName': _bankHolder.text,
    'serviceChargePercent': int.tryParse(_serviceCharge.text) ?? 0,
    'corkagePerBottleCents': (int.tryParse(_corkage.text) ?? 0) * 100,
    'receiptFooter': _footer.text,
    'venuePhone': _phone.text,
    'venueAddress': _address.text,
    'sessionIdleMinutes':
        int.tryParse(_idleMinutes.text) ?? _settings!.sessionIdleMinutes,
    'pendingAlertsEnabled': _alertsEnabled,
    'pendingAlertEscalateSeconds':
        int.tryParse(_escalateSeconds.text) ??
        _settings!.pendingAlertEscalateSeconds,
    'pendingAlertVolume':
        int.tryParse(_alertVolume.text) ?? _settings!.pendingAlertVolume,
    'printerIp': _printerIp.text.trim(),
    'printerPort': int.tryParse(_printerPort.text) ?? _settings!.printerPort,
  });

  Future<void> _save() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l = L.of(context);
    try {
      await _persist();
      // Persist the device-local server URL AFTER the venue settings, so the
      // save above still lands on the current server even when repointing.
      if (!Api.usesEmbeddedStore) {
        await Api.setServerUrlOverride(_serverUrl.text);
      }
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.savedTakesEffectNext)));
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Save the current form, then ask the server to fire a test page so the
  /// owner can confirm the printer answers at the IP they just typed.
  Future<void> _testPrint() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l = L.of(context);
    try {
      await _persist();
      final status = await Api.testPrint();
      if (!mounted) return;
      final msg = !status.configured
          ? l.printerNotConfigured
          : status.online
          ? l.printerTestSent
          : l.printerOffline;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Bulk venue setup: confirm (paper warning + table count) then ask the server
  /// to print every active table's QR slip. Manager-gated like the test print —
  /// this whole screen is manager-only. Result comes back as a status toast.
  Future<void> _printAllSlips() async {
    if (_busy) return;
    final l = L.of(context);
    // Count active tables up front so the confirm copy can name the number.
    final int count;
    try {
      final zones = await Api.zones();
      count = zones.fold<int>(0, (sum, z) => sum + z.tables.length);
    } catch (e) {
      if (mounted) showApiError(context, e);
      return;
    }
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.printAllTableQr),
        content: Text(l.printAllTableQrConfirm(count)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.printAllTableQr),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final res = await Api.printAllTableSlips();
      final msg = !res.configured
          ? l.printerNotConfigured
          : !res.online
          ? l.printerOffline
          : l.slipsPrinted(res.printed);
      messenger.showSnackBar(SnackBar(content: Text(msg)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _field(
    TextEditingController c,
    String label, {
    TextInputType? keyboard,
    String? suffix,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextField(
        controller: c,
        keyboardType: keyboard,
        style: T.text(size: 16),
        decoration: InputDecoration(labelText: label, suffixText: suffix),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l.settings), actions: const [LangActions()]),
      body: _error != null
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
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
          : _settings == null
          ? const DelayedSpinner()
          : Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 560),
                child: ListView(
                  padding: const EdgeInsets.all(20),
                  children: [
                    if (!Api.usesEmbeddedStore) ...[
                      SectionLabel(l.sectionServer),
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: Text(
                          l.restaurantConnectionReady,
                          style: T.small(),
                        ),
                      ),
                      SizedBox(
                        height: T.minTouch,
                        child: OutlinedButton.icon(
                          icon: _scanning
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(LucideIcons.search),
                          label: Text(
                            _scanning ? l.scanningForServer : l.scanForServer,
                          ),
                          onPressed: _scanning ? null : _scanServer,
                        ),
                      ),
                      ExpansionTile(
                        title: Text(l.advancedConnection, style: T.small()),
                        tilePadding: EdgeInsets.zero,
                        childrenPadding: const EdgeInsets.only(bottom: 8),
                        children: [
                          _field(
                            _serverUrl,
                            l.serverUrlLabel,
                            keyboard: TextInputType.url,
                          ),
                          Text(l.currentlyUsing(Api.baseUrl), style: T.small()),
                        ],
                      ),
                      const SizedBox(height: 16),
                    ],
                    // Staff-app QR: employees scan it off this screen to
                    // open the web ordering app. Host = the configured
                    // store URL (LAN-reachable), same rule as table QRs.
                    SectionLabel(l.sectionStaffApp),
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(l.staffAppQrLabel, style: T.small()),
                    ),
                    if (_staffAppUrl != null)
                      Center(
                        child: Container(
                          color: Colors.white,
                          padding: const EdgeInsets.all(12),
                          child: QrImageView(data: _staffAppUrl!, size: 200),
                        ),
                      ),
                    if (_staffAppUrl != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 8, bottom: 8),
                        child: Text(
                          _staffAppUrl!,
                          style: T.small(),
                          textAlign: TextAlign.center,
                        ),
                      ),
                    if (_portalLoaded && _staffAppUrl == null)
                      Text(l.staffAppNeedsWifi, style: T.small()),
                    const SizedBox(height: 16),
                    // Owner reporting-portal QR: the owner scans it to open
                    // the cloud reporting portal (their reports) on a phone.
                    // URL comes from the store's cloud config, never hardcoded;
                    // hidden entirely when cloud sync isn't configured.
                    if (_portalLoaded) ...[
                      SectionLabel(l.sectionReportsPortal),
                      if (_portalUrl != null && _portalUrl!.isNotEmpty) ...[
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(l.reportsPortalQrLabel, style: T.small()),
                        ),
                        Center(
                          child: Container(
                            color: Colors.white,
                            padding: const EdgeInsets.all(12),
                            child: QrImageView(data: _portalUrl!, size: 200),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.only(top: 8, bottom: 8),
                          child: Text(
                            _portalUrl!,
                            style: T.small(),
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ] else
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(l.cloudNotConfigured, style: T.small()),
                        ),
                      const SizedBox(height: 16),
                    ],
                    SectionLabel(l.sectionPayments),
                    _field(
                      _card,
                      l.cardProcessorLabel,
                      keyboard: TextInputType.phone,
                    ),
                    _field(_bankName, l.bankNameLabel),
                    _field(_bankNumber, l.bankAccountNumberLabel),
                    _field(_bankHolder, l.bankAccountNameLabel),
                    SectionLabel(l.sectionFees),
                    _field(
                      _serviceCharge,
                      l.serviceChargeLabel,
                      keyboard: TextInputType.number,
                      suffix: '%',
                    ),
                    _field(
                      _corkage,
                      l.corkageRateLabel,
                      keyboard: TextInputType.number,
                      suffix: '\$',
                    ),
                    SectionLabel(l.sectionReceipt),
                    _field(_footer, l.receiptFooterLabel),
                    _field(
                      _phone,
                      l.venuePhoneLabel,
                      keyboard: TextInputType.phone,
                    ),
                    _field(_address, l.venueAddressLabel),
                    SectionLabel(l.sectionSecurity),
                    _field(
                      _idleMinutes,
                      l.sessionIdleLabel,
                      keyboard: TextInputType.number,
                      suffix: 'min',
                    ),
                    SectionLabel(l.sectionAlerts),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      activeThumbColor: T.accent,
                      title: Text(
                        l.alertsEnabledLabel,
                        style: T.text(size: 16),
                      ),
                      value: _alertsEnabled,
                      onChanged: (v) => setState(() => _alertsEnabled = v),
                    ),
                    _field(
                      _escalateSeconds,
                      l.alertEscalateLabel,
                      keyboard: TextInputType.number,
                      suffix: 's',
                    ),
                    _field(
                      _alertVolume,
                      l.alertVolumeLabel,
                      keyboard: TextInputType.number,
                      suffix: '%',
                    ),
                    SectionLabel(l.sectionPrinter),
                    _field(
                      _printerIp,
                      l.printerIpLabel,
                      keyboard: TextInputType.url,
                    ),
                    _field(
                      _printerPort,
                      l.printerPortLabel,
                      keyboard: TextInputType.number,
                    ),
                    SizedBox(
                      height: T.minTouch,
                      child: OutlinedButton.icon(
                        icon: _scanningPrinter
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(LucideIcons.search),
                        label: Text(
                          _scanningPrinter
                              ? l.scanningForPrinter
                              : l.scanForPrinter,
                        ),
                        onPressed: _scanningPrinter ? null : _scanPrinter,
                      ),
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      height: T.minTouch,
                      child: OutlinedButton.icon(
                        icon: const Icon(LucideIcons.printer),
                        label: Text(l.testPrint),
                        onPressed: _busy ? null : _testPrint,
                      ),
                    ),
                    const SizedBox(height: 8),
                    // Bulk-print a QR slip per table — venue setup, one tap.
                    SizedBox(
                      height: T.minTouch,
                      child: OutlinedButton.icon(
                        icon: const Icon(LucideIcons.qrCode),
                        label: Text(l.printAllTableQr),
                        onPressed: _busy ? null : _printAllSlips,
                      ),
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      height: T.minTouch,
                      child: FilledButton.icon(
                        icon: const Icon(LucideIcons.save),
                        label: Text(l.save),
                        onPressed: _busy ? null : _save,
                      ),
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
              ),
            ),
    );
  }
}
