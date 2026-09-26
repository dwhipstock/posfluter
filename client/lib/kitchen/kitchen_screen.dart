import 'dart:async';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'kitchen_i18n.dart';

/// The kitchen screen inside the POS: orders arrive as cards (one per check
/// per station) the moment they're sent. Tap a card when it's done; Recall
/// brings the last one back. The same board as the store's /kitchen web page:
/// all state lives in the store, so a restart or a second screen agrees.
class KitchenScreen extends StatefulWidget {
  const KitchenScreen({super.key});

  @override
  State<KitchenScreen> createState() => _KitchenScreenState();
}

// a dark board reads from across a kitchen and shows the timer colours best
const _bg = Color(0xFF111418);
const _panel = Color(0xFF1B2027);
const _panel2 = Color(0xFF232A33);
const _line = Color(0xFF2E3742);
const _text = Color(0xFFF2F4F7);
const _muted = Color(0xFF9AA5B1);
const _ok = Color(0xFF2F9E62);
const _warn = Color(0xFFE0B21B);
const _late = Color(0xFFD64545);
const _add = Color(0xFF3B82F6);
const _note = Color(0xFFD9822B);

class _KitchenScreenState extends State<KitchenScreen> {
  KdsBoard? _board;
  DateTime _fetchedAt = DateTime.now();
  String _station = '';
  bool _sound = true;
  bool _offline = false;
  int? _lastSeen;
  Timer? _poll, _tick;
  final _player = AudioPlayer();

  @override
  void initState() {
    super.initState();
    _load();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _load());
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {}); // timers tick between polls
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _tick?.cancel();
    _player.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final b = await KitchenApi.board(station: _station);
      if (!mounted) return;
      final isNew = _lastSeen != null && b.latestTicket > _lastSeen!;
      _lastSeen = (_lastSeen ?? 0) > b.latestTicket
          ? _lastSeen
          : b.latestTicket;
      setState(() {
        _board = b;
        _fetchedAt = DateTime.now();
        _offline = false;
      });
      if (isNew && _sound && b.sound) _chime();
    } catch (_) {
      if (mounted) setState(() => _offline = true);
    }
  }

  Future<void> _chime() async {
    try {
      await _player.stop();
      await _player.play(AssetSource('chime.wav'));
    } catch (_) {} // no audio device: the screen still updates
  }

  Future<void> _bump(KdsCard c) async {
    try {
      await KitchenApi.bump(c.checkId, c.stationId);
    } catch (_) {}
    await _load();
  }

  Future<void> _recall([String? bumpId]) async {
    final k = K.of(context);
    try {
      await KitchenApi.recall(
        bumpId: bumpId,
        stationId: _station.isEmpty ? null : _station,
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(k.nothingToRecall)));
      }
    }
    await _load();
  }

  Future<void> _showWebAddress() async {
    final k = K.of(context);
    String? url;
    try {
      final info = await Api.cloudInfo();
      final store = Api.phoneQrBaseUrl(info['storeUrl'] as String?);
      url = Api.usesEmbeddedStore
          ? (store == null ? null : '$store/kitchen')
          : '${Api.baseUrl}/kitchen';
    } catch (_) {
      url = Api.usesEmbeddedStore ? null : '${Api.baseUrl}/kitchen';
    }
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(k.webScreen),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(k.webScreenHint),
            const SizedBox(height: 16),
            if (url != null) ...[
              QrImageView(data: url, size: 200),
              const SizedBox(height: 8),
              SelectableText(
                url,
                style: T.text(size: 18, weight: FontWeight.w700),
              ),
            ],
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(L.of(context).ok),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final k = K.of(context);
    final board = _board;
    final drift = DateTime.now().difference(_fetchedAt).inSeconds;
    final multi = _station.isEmpty && (board?.stations.length ?? 0) > 1;
    return Scaffold(
      backgroundColor: _bg,
      appBar: AppBar(
        backgroundColor: _panel,
        foregroundColor: _text,
        toolbarHeight: 68,
        title: Text(
          k.kitchen,
          style: const TextStyle(
            color: _text,
            fontSize: 22,
            fontWeight: FontWeight.w800,
          ),
        ),
        actions: [
          TextButton.icon(
            style: TextButton.styleFrom(foregroundColor: _text),
            icon: const Icon(LucideIcons.undo2, size: 20),
            label: Text(k.recall),
            onPressed: (board?.recent.isEmpty ?? true) ? null : () => _recall(),
          ),
          IconButton(
            tooltip: _sound ? k.soundOn : k.soundOff,
            icon: Icon(_sound ? LucideIcons.bell : LucideIcons.bellOff),
            onPressed: () {
              setState(() => _sound = !_sound);
              if (_sound) _chime();
            },
          ),
          IconButton(
            tooltip: k.webScreen,
            icon: const Icon(LucideIcons.monitorSmartphone),
            onPressed: _showWebAddress,
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // station filter
          SizedBox(
            height: 64,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
              children: [
                for (final s in [
                  KitchenStation(
                    id: '',
                    nameFr: k.allStations,
                    nameEn: k.allStations,
                  ),
                  ...?board?.stations,
                ])
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(k.name(s.nameFr, s.nameEn)),
                      selected: s.id == _station,
                      showCheckmark: false,
                      labelStyle: TextStyle(
                        color: s.id == _station ? Colors.black : _text,
                        fontWeight: FontWeight.w700,
                        fontSize: 16,
                      ),
                      selectedColor: _note,
                      backgroundColor: _panel2,
                      side: const BorderSide(color: _line),
                      onSelected: (_) {
                        setState(() {
                          _station = s.id;
                          _lastSeen = null;
                        });
                        _load();
                      },
                    ),
                  ),
              ],
            ),
          ),
          if (_offline)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                k.storeUnreachable,
                style: const TextStyle(color: Color(0xFFFF8A8A)),
              ),
            ),
          Expanded(
            child: board == null
                ? const Center(child: CircularProgressIndicator())
                : board.cards.isEmpty
                ? Center(
                    child: Text(
                      k.noOrders,
                      style: const TextStyle(color: _muted, fontSize: 22),
                    ),
                  )
                : GridView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 320,
                          mainAxisSpacing: 12,
                          crossAxisSpacing: 12,
                          childAspectRatio: 0.72,
                        ),
                    itemCount: board.cards.length,
                    itemBuilder: (_, i) => _card(
                      board,
                      board.cards[i],
                      board.cards[i].elapsedSeconds + drift,
                      multi,
                      k,
                    ),
                  ),
          ),
          if (board != null && board.recent.isNotEmpty)
            Container(
              color: _panel,
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 10),
              child: Wrap(
                spacing: 8,
                runSpacing: 6,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  for (final r in board.recent)
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        foregroundColor: _text,
                        backgroundColor: _panel2,
                        side: const BorderSide(color: _line),
                      ),
                      icon: const Icon(LucideIcons.undo2, size: 16),
                      label: Text(r.tableLabel),
                      onPressed: () => _recall(r.bumpId),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  static String _mmss(int s) =>
      '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';

  Widget _card(KdsBoard board, KdsCard c, int seconds, bool multi, K k) {
    final level = board.levelFor(seconds);
    final head = level == 'late' ? _late : (level == 'warn' ? _warn : _ok);
    final onHead = level == 'warn' ? Colors.black : Colors.white;
    final meta = [
      if (multi) k.name(c.stationNameFr, c.stationNameEn),
      '${k.server} ${c.serverName}',
      if (c.guests != null) k.guestsCount(c.guests!),
      '#${c.checkId}',
    ].join(' · ');
    return Material(
      color: _panel,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: const BorderSide(color: _line),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _bump(c),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              color: head,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      c.tableLabel,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: onHead,
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  Text(
                    _mmss(seconds),
                    style: TextStyle(
                      color: onHead,
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 6, 14, 6),
              child: Text(
                meta,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: _muted, fontSize: 14),
              ),
            ),
            const Divider(height: 1, color: _line),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(14, 6, 14, 6),
                children: [for (final i in c.items) _item(i, k)],
              ),
            ),
            Container(
              color: _panel2,
              padding: const EdgeInsets.symmetric(vertical: 12),
              alignment: Alignment.center,
              child: Text(
                k.tapToFinish,
                style: const TextStyle(
                  color: _text,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _item(KdsItem i, K k) {
    final struck = i.voided ? TextDecoration.lineThrough : null;
    final variant = i.variantFr == null
        ? null
        : k.name(i.variantFr!, i.variantEn ?? i.variantFr!);
    Widget tag(String t, Color c) => Container(
      margin: const EdgeInsets.only(left: 6),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: c,
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        t,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 12,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
    return Opacity(
      opacity: i.voided ? 0.6 : 1,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${i.remaining}× ',
                  style: TextStyle(
                    color: _text,
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                    decoration: struck,
                  ),
                ),
                Expanded(
                  child: Text(
                    k.name(i.nameFr, i.nameEn),
                    style: TextStyle(
                      color: _text,
                      fontSize: 19,
                      fontWeight: FontWeight.w700,
                      decoration: struck,
                    ),
                  ),
                ),
                if (i.add) tag(k.add, _add),
                if (i.voided) tag(k.voidTag, _late),
              ],
            ),
            if (variant != null)
              Padding(
                padding: const EdgeInsets.only(left: 30),
                child: Text(
                  variant,
                  style: TextStyle(
                    color: _muted,
                    fontSize: 15,
                    decoration: struck,
                  ),
                ),
              ),
            if (i.note != null)
              Padding(
                padding: const EdgeInsets.only(left: 30),
                child: Text(
                  '» ${i.note}',
                  style: TextStyle(
                    color: _note,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    decoration: struck,
                  ),
                ),
              ),
            if (!i.voided && i.voidedQty > 0)
              Padding(
                padding: const EdgeInsets.only(left: 30),
                child: Text(
                  k.voidedPart(i.voidedQty),
                  style: const TextStyle(
                    color: Color(0xFFFF8A8A),
                    fontSize: 14,
                    decoration: TextDecoration.lineThrough,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
