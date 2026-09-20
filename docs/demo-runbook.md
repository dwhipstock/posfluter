# Local demo runbook

1. Copy `.env.local.example` to `.env.local` and replace every placeholder secret.
2. Run `scripts/demo-up.sh` for the Compose stack, or run `server/gradlew run` and `flutter run` separately.
3. Open the terminal with manager PIN `1234` or server PIN `9999`.
4. Verify English is selected initially, switch to French, and place a test order using only fictional catalog data.
5. Exercise cash, generic card, and bank-transfer tenders. Uploaded photos and generated receipts are runtime data and must not be committed.
6. Stop the stack with `scripts/demo-down.sh`.

The sample venue is The Copper Lantern Pub in Toronto, configured for `America/Toronto` and CAD.
