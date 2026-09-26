import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/app_mode.dart';
import 'package:pos_client/server_discovery.dart';

void main() {
  group('brand apps on one tablet', () {
    test('each brand has its own embedded store port', () {
      expect(AppMode.storePortFor('copperlantern'), 8080);
      expect(AppMode.storePortFor('sagepoppy'), 8082);
    });

    test('a plain build is Copper Lantern on the original port', () {
      expect(AppMode.brand, 'copperlantern');
      expect(AppMode.embeddedStorePort, 8080);
      expect(Api.embeddedStoreUrl, 'http://127.0.0.1:8080');
    });

    test('discovery looks on both store ports, stock app Sage & Poppy first',
        () {
      expect(AppMode.discoveryPorts(stock: false), [8080, 8082]);
      expect(AppMode.discoveryPorts(stock: true), [8082, 8080]);
    });

    test('discovery tries the last-used store port first', () {
      const defaults = [8082, 8080];
      expect(ServerDiscovery.portOrder(null, defaults), defaults);
      expect(ServerDiscovery.portOrder('http://10.0.0.5:8080', defaults),
          [8080, 8082]);
      expect(ServerDiscovery.portOrder('http://10.0.0.5:8082', defaults),
          [8082, 8080]);
      // not a store port (e.g. a hosted venue) → the defaults
      expect(ServerDiscovery.portOrder('https://example.test', defaults),
          defaults);
      expect(ServerDiscovery.portOrder('http://10.0.0.5:9999', defaults),
          defaults);
    });
  });
}
