import 'package:flutter/material.dart';

/// Calls [onAppResume] when the app returns to the foreground.
///
/// Poll timers stall while the Android activity is paused (tablet screen
/// off overnight, emulator focus loss) — without this, screens wake up
/// showing stale data until the next poll happens to fire. Refetch on
/// resume so what's on screen is never older than the wake-up.
mixin ResumeRefresh<T extends StatefulWidget> on State<T> {
  late final _ResumeObserver _resumeObserver;

  void onAppResume();

  @override
  void initState() {
    super.initState();
    _resumeObserver = _ResumeObserver(() {
      if (mounted) onAppResume();
    });
    WidgetsBinding.instance.addObserver(_resumeObserver);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(_resumeObserver);
    super.dispose();
  }
}

class _ResumeObserver with WidgetsBindingObserver {
  final VoidCallback onResume;
  _ResumeObserver(this.onResume);

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) onResume();
  }
}
