import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

// The store only applies tables and objects that were in its proposal (by id),
// so the apply payload must carry each ghost's id — without it nothing is saved.
void main() {
  test('room apply payload keeps table and object ids', () {
    final t = TableInfo.fromJson({
      'id': 'ai-t1', 'label': 'L-11', 'zoneId': 'lower', 'x': 10, 'y': 20,
      'width': 100, 'height': 100, 'rotation': 0, 'shape': 'SQUARE', 'seats': 4,
    });
    expect(roomTableJson(t)['id'], 'ai-t1');
    final o = FloorObject.fromJson({
      'id': 'ai-o1', 'zoneId': 'lower', 'type': 'BAR_FRONT', 'x': 0, 'y': 0,
      'width': 300, 'height': 60, 'rotation': 0,
    });
    expect(roomObjectJson(o)['id'], 'ai-o1');
  });
}
