import 'package:flutter/widgets.dart';

import 'app_mode.dart';
import 'retail/retail_screen.dart';
import 'screens/zones_screen.dart';
import 'stock/stock_home_screen.dart';
import 'store_profile.dart';

/// Where a signed-in terminal lands: the floor plan at a restaurant, the
/// counter at a retail store — and Count / Receive in the stock app.
Widget homeScreen() {
  if (AppMode.isStock) return const StockHomeScreen();
  return StoreProfile.current.isRetail
      ? const RetailScreen()
      : const ZonesScreen();
}
