// The AI assistants hear the screen's language: every typed or spoken request
// carries `?lang=` (Thai right after the ไทย button, before the user row on
// the store has caught up), so the store answers and transcribes in Thai.
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';

void main() {
  tearDown(() => Prefs.instance.lang = 'en');

  test('typed and spoken AI requests carry the screen language', () async {
    final seen = <String, String?>{};
    final client = MockClient((req) async {
      seen[req.url.path] = req.url.queryParameters['lang'];
      final body = req.url.path.contains('ai-edit')
          ? {'proposalId': 'e1', 'zoneId': 'z1', 'tables': [], 'objects': []}
          : {
              'proposalId': 'p1',
              'provider': 'fake',
              'model': 'fake',
              'summary': 'เพิ่มเบียร์',
              'transcript': 'เพิ่มเบียร์ 90 บาท',
              'changes': [],
              'rejected': [],
              'elapsedMs': 1,
            };
      return http.Response.bytes(
        utf8.encode(jsonEncode(body)),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    });
    Prefs.instance.lang = 'th';
    await http.runWithClient(() async {
      await Api.menuAiChat('เพิ่มเบียร์ 90 บาท', '1234');
      final heard = await Api.menuAiChatVoice(
        List.filled(4000, 1),
        'audio/wav',
        '1234',
      );
      expect(heard.transcript, 'เพิ่มเบียร์ 90 บาท');
      await Api.floorEditVoice('z1', List.filled(4000, 1), 'audio/wav', '1234');
    }, () => client);
    expect(seen['/menu-ai/chat'], 'th');
    expect(seen['/menu-ai/chat/voice'], 'th');
    expect(seen['/zones/z1/ai-edit/voice'], 'th');
  });
}
