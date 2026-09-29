# Quick-serve: Copper Lantern — Express

Copper Lantern's counter-service location, set up like a burger chain's
counter. It has no floor plan and no tables. Customers order at the counter
or at a self-order kiosk, pay at the counter, and are called by a short
order number. It is one more venue of the `copper-lantern` brand (venue id
`express`) and reports to the same Copper Lantern portal as Vieux-Port and
Plateau. Sync is one-way, store to portal, as for every store.

| | |
|---|---|
| Store | `POS_VENUE=express` (desktop), or `store.venue=express` in the Copper Lantern POS app's `store.properties` (tablet) |
| Kind | `quick-serve` (`GET /health` → `"kind":"quick-serve"`) |
| Country, money, languages | Canada, CAD, GST + QST added on top; fr / en / es / de |
| Menu | 20 items: burgers, chicken, fries and sides, salads, desserts, soft drinks, and beer and wine only (no cocktails). Sizes on fries, soft drinks, wings and tenders |
| Staff | manager PIN 1234, cashier 9999 |

## How an order moves

1. **A new order** gets the next number: 101, 102 and so on. Numbering starts
   again at 101 each business day. Each order is marked *dine in* or *take out*.
2. **From the POS**: *New — dine in* or *New — take out* opens the usual bill
   screen, titled with the order (`#104 · Take out`). When the cashier goes
   back to the order list, the order goes to the kitchen and shows on the
   pickup board as *preparing*.
3. **From a kiosk**: the order is sent the moment the customer taps *Place
   order*. It arrives at the POS as an unpaid order with its number and goes
   straight to the kitchen (tickets and the kitchen screen, when kitchen
   tickets are on). Staff do not approve it first, unlike QR table orders.
   The kiosk never takes payment. The cashier opens the order on the POS and
   takes payment as for any bill.
4. **Ready**: the order becomes ready when the kitchen screen bumps its last
   card (kitchen and bar both, if it has both), or when the cashier taps
   *Mark ready*. The number moves to the *Ready* column with a chime.
5. **Picked up**: the cashier taps *Picked up*. The number leaves the board.
   Once an order is both paid and picked up, it leaves the POS list.

A voided or cancelled order never shows on the board. Kitchen tickets and the
kitchen screen show the order number (`#101 · Take out`) where a pub shows
the table.

## The kiosk app

A separate Flutter build of the same code, like the stock and card-reader
apps: `--dart-define=POS_APP=kiosk`. On Android it has its own app id,
`dev.dwhipstock.pos_kiosk`. It runs portrait and full screen, never sleeps,
and never runs a store of its own.

- **Welcome**: *Touch to order*, with buttons for Français, English, Español
  and Deutsch.
- **Dine in / Take out**, then **categories and big photo tiles**. Items with
  more than one size open a size picker. Alcohol shows *Staff will check ID at
  the counter*. This is a note only; nothing is blocked.
- **Cart** (quantities, subtotal, "taxes added at the counter"), then **Place
  order**. There is no review or confirmation step after that. The last
  screen says *Your order number is 123. Please pay at the counter.* and goes
  back to Welcome after 10 seconds, or sooner on a tap.
- **Idle timeout**: after 90 seconds with no touch mid-order, the cart is
  cleared and the kiosk goes back to Welcome.

**Pairing.** Each kiosk is a paired device of the store, and a store can have
any number of them. On the POS, a manager opens *Orders → Pair a kiosk* to get
a 6-digit code (one use, 10 minutes). The kiosk finds the Express store on
the Wi-Fi (it skips the pubs and the shops); if it can't find it, type the
store's address. Then enter the code. The kiosk keeps its own device token
and sends it as `X-Device-Token`. Store routes: `POST /kiosk/pair`,
`GET /kiosk/config`, `POST /kiosk/orders`. The menu comes from the open
`GET /items` and `GET /categories`.

## The pickup board

`http://<store>:<port>/pickup` is a page the store serves, like `/kitchen`,
for a TV or any browser. It has two columns, *Preparing* and *Ready*, with
big numbers. A new ready number flashes and plays a chime; tap *Sound* once
to allow sound, since browsers block it until someone taps the page. The page
signs in to nothing and shows order numbers only, read from
`GET /pickup/board`. The POS's *Pickup board* button shows the exact address.

## Photos

Dishes the pubs also serve keep the pubs' item ids: `lantern-burger`,
`mushroom-burger`, `veggie-burger`, `wings`, `club`, `late-fries`, `poutine`,
`onion-rings`, `caesar-salad`, `harvest-salad`, `brownie`, `lantern-lager`,
`north-ipa`, `pinot-noir` and `riesling`. The AI photo copy mode gives them
the pubs' pictures at no cost:

    python3 scripts/ai-menu-photos.py --store http://<pub>:8080 --copy-to http://<express>:8098

The new items (double cheeseburger, tenders, maple sundae, fountain soda,
lemonade) start without photos and show their letter badges.

## Demo walkthrough (about 5 minutes)

1. **Start the store** on the Mac: `scripts/kiosk-setup.sh --store`. It runs
   on :8098 with kitchen tickets on, data in `.demo/express/`. If
   `.env.local` has `STORE_API_KEY_EXPRESS` and the local cloud is up
   (`scripts/demo-up.sh`), it syncs to the Copper Lantern portal. The script
   prints the POS, board, kitchen and kiosk addresses.
2. **POS**: `cd client && flutter run -d macos --dart-define=SERVER_URL=http://localhost:8098`.
   Sign in as the manager (PIN 1234). The POS opens on the order list; there
   is no floor plan.
3. **Board and kitchen**: open `/pickup` on the TV (or a second browser
   window) and `/kitchen` in another.
4. **Kiosk**: `scripts/kiosk-setup.sh --kiosk <adb serial>` installs and
   starts it. On the POS, tap *Pair a kiosk* and type the code on the kiosk.
5. **Order at the kiosk**: pick Español, *Para llevar*, a burger, large fries
   and an IPA. The ID note appears. Tap *Hacer el pedido*. The kiosk shows
   **101**. At the same moment, #101 appears on the POS as unpaid, on the
   kitchen screen, and under *Preparing* on the board.
6. **Order at the counter**: *New — dine in*, ring two items, then go back.
   #102 goes to the kitchen and the board.
7. **Kitchen**: bump #101 at Kitchen and at Bar. #101 moves to *Ready* with
   the chime.
8. **Counter**: open #101, take cash or card, then tap *Picked up*. #101
   leaves the board.
9. **Portal**: the Express venue appears next to Vieux-Port and Plateau, with
   its sales.

Stop the store with `scripts/kiosk-setup.sh --stop`.

## Not in scope

- Paying at the kiosk: every order is paid at the counter.
- Item modifiers beyond sizes: kiosk lines carry no free-text notes.
- A second POS terminal: the counter is one POS plus any number of kiosks.
