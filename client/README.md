# Copper Lantern Flutter terminal

Flutter POS client for the fictional The Copper Lantern Pub demo. English is the default language and French is the secondary language.

```bash
flutter pub get
flutter analyze
flutter test
flutter run
```

## The stock app (same code, a phone build)

`--dart-define=POS_APP=stock` builds the phone app that counts and receives
stock (lib/app_mode.dart, lib/stock/): portrait, finds the store on the Wi-Fi,
staff PIN sign-in, Count / Receive, an offline queue. On Android it installs
next to the POS as "Stock" (`dev.dwhipstock.pos_stock`) and never starts
the embedded store:

```bash
flutter build apk --release --dart-define=POS_APP=stock
```

An iOS build is the stock app by default (ios/, needs Xcode). See
docs/demo-runbook.md ("Counting and receiving stock").

## Tablet: staff-app MFA

The tablet-hosted staff ordering app requires MFA by default. Turn it off
(PIN only) with `staff.app.mfa=off` in the tablet's external
`store.properties`: `scripts/tablet-staff-mfa.sh off` (and `on` to restore).
An explicit demo APK (`POS_DEMO_BUILD=true flutter build apk --release`) also
packages `android/app/src/demo/assets/copperlantern-demo.properties`, whose
`staff.app.mfa.required=false` applies when store.properties does not set the
key. There is no in-app switch. The owner/manager web portal is unaffected, and
existing staff authenticator enrollments are kept for when MFA is back on.
See docs/demo-runbook.md ("Staff app MFA").
