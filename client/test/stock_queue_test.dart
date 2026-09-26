import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/stock/stock_queue.dart';

/// The stock app's offline queue: every change is on the phone first, goes to
/// the store in order when it can, survives a restart, never sends a count
/// twice as two, and never drops what the store refused.
void main() {
  /// A fake store: idempotent like the real one (ids and SET quantities).
  late Map<String, Map<String, int>> counts; // countId → itemId → qty
  late Set<String> submitted;
  late Map<String, Map<String, dynamic>> receipts;
  late List<String> calls;
  late bool reachable;
  late bool loseReplies; // the store did it, but the reply never arrived
  late String? managerPin;

  Future<Object?> store(StockOp op, String counter) async {
    if (!reachable) throw const SocketException('no route to host');
    calls.add('${op.kind} ${op.countId ?? op.body['id']}');
    switch (op.kind) {
      case 'start':
        counts.putIfAbsent(op.countId!, () => {});
      case 'lines':
        final c = counts[op.countId!]!;
        for (final l in (op.body['lines'] as List).cast<Map>()) {
          if (l['remove'] == true) {
            c.remove(l['itemId']);
          } else {
            c[l['itemId'] as String] = l['qty'] as int;
          }
        }
      case 'submit':
        if (!submitted.contains(op.countId) && op.managerPin != managerPin) {
          throw ApiException(
            'manager approval required',
            'manager_approval_required',
            null,
            403,
          );
        }
        submitted.add(op.countId!);
      case 'receive':
        receipts[op.body['id'] as String] = op.body;
    }
    if (loseReplies) {
      loseReplies = false;
      throw const SocketException('connection reset');
    }
    return null;
  }

  late MemoryKeyValueStore disk;
  StockQueue queue() => StockQueue(disk, sender: store);

  setUp(() {
    counts = {};
    submitted = {};
    receipts = {};
    calls = [];
    reachable = true;
    loseReplies = false;
    managerPin = null;
    disk = MemoryKeyValueStore();
  });

  test(
    'offline counting is kept on the phone and sent in order later',
    () async {
      reachable = false;
      final q = queue();
      await q.load();
      final draft = await q.startCount(name: 'Back room');
      await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 1);
      await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 2);
      await q.setQty(draft.id, itemId: 'ice', name: 'Ice', qty: 5);
      await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 3);
      await q.removeLine(draft.id, 'ice');
      await q.submitCount(draft.id);

      var r = await q.flush();
      expect(r.offline, isTrue);
      expect(r.sent, 0);
      expect(q.offline, isTrue);
      // start, ONE lines op (the changes folded together), submit
      expect(q.ops.map((o) => o.kind), ['start', 'lines', 'submit']);
      expect(q.ops[1].body['lines'], [
        {'itemId': 'lager', 'qty': 3, 'countedAt': isA<String>()},
        {'itemId': 'ice', 'remove': true},
      ]);

      // the phone restarts before the Wi-Fi comes back: nothing is lost
      final again = queue();
      await again.load();
      expect(again.counterId, q.counterId);
      expect(again.pending, 3);
      expect(again.draft(draft.id)!.lines.keys, ['lager']);
      expect(again.draft(draft.id)!.lines['lager']!.qty, 3);
      expect(again.draft(draft.id)!.submitting, isTrue);

      reachable = true;
      r = await again.flush();
      expect(r.sent, 3);
      expect(r.offline, isFalse);
      expect(calls, [
        'start ${draft.id}',
        'lines ${draft.id}',
        'submit ${draft.id}',
      ]);
      expect(counts[draft.id], {'lager': 3});
      expect(submitted, {draft.id});
      expect(again.pending, 0);
      expect(again.draft(draft.id), isNull); // the store has it now
    },
  );

  test(
    'a lost reply means a resend with the same ids — never a second count',
    () async {
      final q = queue();
      await q.load();
      final draft = await q.startCount();
      await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 4);
      await q.flush();
      await q.submitCount(draft.id);
      loseReplies = true; // the store submitted it, the phone never heard
      final r = await q.flush();
      expect(r.offline, isTrue);
      expect(q.pending, 1);
      final r2 = await q.flush();
      expect(r2.sent, 1);
      // sent twice, the same count both times: one submitted count at the store
      expect(calls.where((c) => c == 'submit ${draft.id}').length, 2);
      expect(submitted, {draft.id});

      // a delivery too: its id is minted once, the resend is the same delivery
      final id = await q.receive(
        supplier: 'Valley Beverage',
        lines: [
          {'itemId': 'lager', 'qty': 24},
        ],
      );
      loseReplies = true;
      await q.flush();
      await q.flush();
      expect(calls.where((c) => c == 'receive $id').length, 2);
      expect(receipts.keys, [id]);
    },
  );

  test(
    'a refused submit is kept, holds its count, and goes after a manager PIN',
    () async {
      managerPin = '1234';
      final q = queue();
      await q.load();
      final draft = await q.startCount();
      await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 9);
      await q.submitCount(draft.id); // no PIN: the store refuses the variance
      // a later change to the same count waits behind the refusal
      await q.receive(
        lines: [
          {'itemId': 'ice', 'qty': 10},
        ],
      );
      final r = await q.flush();
      expect(r.offline, isFalse);
      final refused = q.failed.single;
      expect(refused.kind, 'submit');
      expect(refused.errorCode, 'manager_approval_required');
      expect(q.draft(draft.id)!.submitting, isFalse); // back to editable
      expect(receipts, hasLength(1)); // other work still went
      expect(submitted, isEmpty);

      // still there after a restart (the refusal is saved, never dropped)
      final again = queue();
      await again.load();
      expect(again.failed.single.id, refused.id);

      await again.retry(refused.id, managerPin: '1234');
      expect(again.pending, 0);
      expect(submitted, {draft.id});
    },
  );

  test('a manager PIN is never written to the phone', () async {
    reachable = false;
    final q = queue();
    await q.load();
    final draft = await q.startCount();
    await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 1);
    await q.submitCount(draft.id, managerPin: '4321');
    expect(q.ops.last.managerPin, '4321'); // in memory for this run
    expect(disk.data.values.join(), isNot(contains('4321')));
  });

  test(
    'discarding a count that never reached the store sends nothing',
    () async {
      reachable = false;
      final q = queue();
      await q.load();
      final draft = await q.startCount();
      await q.setQty(draft.id, itemId: 'lager', name: 'Lager', qty: 1);
      await q.discardCount(draft.id);
      expect(q.pending, 0);
      expect(q.draft(draft.id), isNull);

      // one the store knows gets a cancel
      reachable = true;
      final known = await q.startCount();
      await q.flush();
      await q.discardCount(known.id);
      expect(q.ops.single.kind, 'cancel');
    },
  );

  test('transient vs refused errors', () {
    expect(StockQueue.isTransient(const SocketException('x')), isTrue);
    expect(
      StockQueue.isTransient(ApiException('x', 'internal', null, 500)),
      isTrue,
    );
    expect(StockQueue.isTransient(SessionExpiredException()), isTrue);
    expect(
      StockQueue.isTransient(ApiException('x', 'count_closed', null, 409)),
      isFalse,
    );
    expect(
      StockQueue.isTransient(ApiException('x', 'bad_qty', null, 400)),
      isFalse,
    );
  });

  test('ids are UUIDs and barcodes match in either form', () {
    final id = newStockId();
    expect(
      RegExp(
        r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
      ).hasMatch(id),
      isTrue,
    );
    expect(newStockId(), isNot(id));
    expect(
      normalizeBarcode('0036000291452'),
      '036000291452',
    ); // EAN-13 of a UPC-A
    expect(normalizeBarcode('036000291452'), '036000291452');
    expect(
      normalizeBarcode('0036000291453'),
      '0036000291453',
    ); // bad check digit: as typed
    final index = ProductIndex([
      const StockProduct(
        id: 'soda',
        name: 'Club Soda',
        barcode: '036000291452',
      ),
    ]);
    expect(index.byCode('0036000291452')?.id, 'soda');
    expect(index.search('club').single.id, 'soda');
  });
}
