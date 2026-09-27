import 'package:flutter/material.dart';

import '../app_mode.dart';
import '../server_discovery.dart';
import 'reader_controller.dart';
import 'tap_reader.dart';

/// The card reader app (`POS_APP=reader`): the phone that takes cards for the
/// store with Stripe Tap to Pay. Plain on purpose: the customer looks at it.
class ReaderApp extends StatelessWidget {
  final ReaderController? controller;
  const ReaderApp({super.key, this.controller});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Card Reader',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      colorSchemeSeed: const Color(0xFF2F6B4F),
      useMaterial3: true,
    ),
    home: ReaderHome(
      controller:
          controller ??
          ReaderController(
            reader: StripeTapToPayReader.instance,
            // the US stores first (Sage & Poppy :8082, Pronghorn :8084)
            discover: () => ServerDiscovery.discover(
              ports: AppMode.discoveryPorts(stock: true),
            ),
          ),
    ),
  );
}

class ReaderHome extends StatefulWidget {
  final ReaderController controller;
  const ReaderHome({super.key, required this.controller});

  @override
  State<ReaderHome> createState() => _ReaderHomeState();
}

class _ReaderHomeState extends State<ReaderHome> {
  ReaderController get c => widget.controller;
  final _code = TextEditingController();
  final _address = TextEditingController();

  @override
  void initState() {
    super.initState();
    c.addListener(_changed);
    // ignore: discarded_futures
    c.start();
  }

  void _changed() => setState(() {});

  @override
  void dispose() {
    c.removeListener(_changed);
    _code.dispose();
    _address.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          c.storeName == null ? 'Card reader' : 'Card reader · ${c.storeName}',
        ),
        actions: [
          if (c.stage == ReaderStage.ready || c.stage == ReaderStage.error)
            PopupMenuButton<String>(
              onSelected: (v) => v == 'forget' ? c.forget() : c.connect(),
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'reconnect', child: Text('Reconnect')),
                PopupMenuItem(
                  value: 'forget',
                  child: Text('Unpair from this store'),
                ),
              ],
            ),
        ],
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Center(child: SingleChildScrollView(child: _body(t))),
        ),
      ),
    );
  }

  Widget _body(TextTheme t) {
    Widget big(IconData icon, [Color? color]) => Icon(
      icon,
      size: 96,
      color: color ?? Theme.of(context).colorScheme.primary,
    );
    Widget msg() => c.message == null
        ? const SizedBox.shrink()
        : Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              c.message!,
              key: const ValueKey('reader-message'),
              textAlign: TextAlign.center,
              style: t.bodyLarge?.copyWith(
                color: Theme.of(context).colorScheme.error,
              ),
            ),
          );
    switch (c.stage) {
      case ReaderStage.finding:
        return Column(
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text('Looking for the store on the Wi-Fi…', style: t.titleMedium),
          ],
        );
      case ReaderStage.pairing:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Pair with the store',
              style: t.headlineSmall,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'On the POS: Settings → Card terminal shows a 6-digit code.',
              textAlign: TextAlign.center,
              style: t.bodyMedium,
            ),
            const SizedBox(height: 16),
            if (c.storeUrl != null)
              Text('Store: ${c.storeUrl}', textAlign: TextAlign.center),
            TextField(
              key: const ValueKey('reader-address'),
              controller: _address,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(
                labelText:
                    'Store address (only if not found), e.g. 192.168.1.20:8082',
              ),
            ),
            TextField(
              key: const ValueKey('reader-code'),
              controller: _code,
              keyboardType: TextInputType.number,
              maxLength: 6,
              style: t.headlineMedium,
              textAlign: TextAlign.center,
              decoration: const InputDecoration(labelText: 'Pairing code'),
            ),
            FilledButton(
              key: const ValueKey('reader-pair'),
              onPressed: () => c.pair(
                _code.text,
                address: _address.text,
                deviceName: 'Card reader phone',
              ),
              child: const Text('Pair'),
            ),
            msg(),
          ],
        );
      case ReaderStage.connecting:
        return Column(
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text('Connecting Tap to Pay…', style: t.titleMedium),
          ],
        );
      case ReaderStage.ready:
        return Column(
          children: [
            big(Icons.contactless_outlined),
            const SizedBox(height: 16),
            Text(
              'Ready',
              key: const ValueKey('reader-ready'),
              style: t.headlineMedium,
            ),
            const SizedBox(height: 8),
            Text('Waiting for the POS to send a payment', style: t.bodyLarge),
            if (c.readerName != null) ...[
              const SizedBox(height: 4),
              Text(c.readerName!, style: t.bodySmall),
            ],
            if (c.simulated) ...[
              const SizedBox(height: 4),
              Text(
                'Test mode: Stripe\'s simulated reader, no real card',
                style: t.bodySmall,
              ),
            ],
            const SizedBox(height: 24),
            for (final line in c.log.take(5)) Text(line, style: t.bodySmall),
          ],
        );
      case ReaderStage.payment:
        final j = c.job!;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              j.amountLabel,
              key: const ValueKey('reader-amount'),
              textAlign: TextAlign.center,
              style: t.displayMedium,
            ),
            const SizedBox(height: 8),
            Text(
              c.simulated
                  ? 'Pick a Stripe test card, then tap'
                  : 'Tap the card on the back of the phone',
              textAlign: TextAlign.center,
              style: t.titleMedium,
            ),
            if (c.simulated) ...[
              const SizedBox(height: 12),
              RadioGroup<ReaderTestCard>(
                groupValue: c.testCard,
                onChanged: (v) =>
                    setState(() => c.testCard = v ?? ReaderTestCard.visa),
                child: Column(
                  children: [
                    for (final card in ReaderTestCard.values)
                      RadioListTile<ReaderTestCard>(
                        key: ValueKey('reader-card-${card.name}'),
                        value: card,
                        title: Text(card.label),
                        dense: true,
                      ),
                  ],
                ),
              ),
              FilledButton.icon(
                key: const ValueKey('reader-tap'),
                icon: const Icon(Icons.contactless),
                label: const Text('Tap card (simulated)'),
                onPressed: c.collect,
              ),
            ] else
              Center(child: big(Icons.contactless)),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: c.cancelPayment,
              child: const Text('Cancel'),
            ),
          ],
        );
      case ReaderStage.processing:
        return Column(
          children: [
            if (c.job != null) Text(c.job!.amountLabel, style: t.displaySmall),
            const SizedBox(height: 16),
            big(Icons.contactless),
            const SizedBox(height: 16),
            Text(
              c.simulated
                  ? 'Reading the test card…'
                  : 'Hold the card to the back of the phone',
              style: t.titleLarge,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            OutlinedButton(
              onPressed: c.cancelPayment,
              child: const Text('Cancel'),
            ),
          ],
        );
      case ReaderStage.result:
        return Column(
          children: [
            big(
              c.resultOk ? Icons.check_circle : Icons.cancel,
              c.resultOk ? Colors.green : Theme.of(context).colorScheme.error,
            ),
            const SizedBox(height: 16),
            Text(
              c.resultTitle ?? '',
              key: const ValueKey('reader-result'),
              style: t.headlineSmall,
              textAlign: TextAlign.center,
            ),
          ],
        );
      case ReaderStage.error:
        return Column(
          children: [
            big(
              Icons.warning_amber_rounded,
              Theme.of(context).colorScheme.error,
            ),
            msg(),
            const SizedBox(height: 16),
            FilledButton(onPressed: c.connect, child: const Text('Try again')),
          ],
        );
    }
  }
}
