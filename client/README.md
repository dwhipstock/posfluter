# Copper Lantern Flutter terminal

Flutter POS client for the fictional The Copper Lantern Pub demo. English is the default language and French is the secondary language.

```bash
flutter pub get
flutter analyze
flutter test
flutter run
```

## Tablet demo: local staff-app MFA

Normal Android builds require MFA for the tablet-hosted staff ordering app.
For an isolated demo APK, build with
`POS_DEMO_BUILD=true flutter build apk --release` from this directory. Only that explicit build packages
`android/app/src/demo/assets/copperlantern-demo.properties`; set
`staff.app.mfa.required=false` there to let staff sign in with their PIN alone.
Set it to `true`, rebuild, and sign out of the staff app to demonstrate the
normal authenticator flow.
There is no in-app switch. The owner/manager web portal is unaffected, and
existing staff authenticator enrollments are kept for MFA-on builds.
