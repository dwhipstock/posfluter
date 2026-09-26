import 'package:flutter/widgets.dart';

import 'retail/retail_screen.dart';
import 'screens/zones_screen.dart';
import 'store_profile.dart';

/// Where a signed-in terminal lands: the floor plan at a restaurant, the
/// counter at a retail store.
Widget homeScreen() =>
    StoreProfile.current.isRetail ? const RetailScreen() : const ZonesScreen();
