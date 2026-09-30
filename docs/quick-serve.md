# Quick-serve: Copper Lantern — Express

Copper Lantern's counter-service location, set up like a burger chain's
counter. It has no floor plan and no tables. Customers order at the counter
or at a self-order kiosk, pay at the counter, and are called by a short
order number. It is one more venue of the `copper-lantern` brand (venue id
`express`) and reports to the same Copper Lantern portal as Glenwood South and
Plateau. Sync is one-way, store to portal, as for every store.

| | |
|---|---|
| Store | `POS_VENUE=express` (desktop), or `store.venue=express` in the Copper Lantern POS app's `store.properties` (tablet) |
| Kind | `quick-serve` (`GET /health` → `"kind":"quick-serve"`) |
| Country, money, languages | US (Raleigh, NC; 418 Lantern Row, fictional), USD, NC sales tax 6.75% + Wake prepared food tax 1% added on top; en first, fr / es / de / af selectable |
| Legal age | 21: the kiosk's ID note and a 21+ badge on kiosk orders with alcohol at the counter |
| Existing counters | moved from Montréal in place on their next start: see [Copper Lantern moved to Raleigh](demo-runbook.md#copper-lantern-moved-to-raleigh-existing-devices) |
| Menu | 22 items: burgers, chicken, fries and sides, salads, desserts, soft drinks, and beer and wine only (no cocktails). Sizes on fries, soft drinks, wings and tenders |
| Staff | manager PIN 1234, cashier 9999 |

## How an order moves

One flow for every order, like a burger chain: the customer pays first,
whether they eat in or take out. Dine in or take out only decides a tray or
a bag.

1. **The counter opens on a new order**: the item grid and the order panel.
   There is no order list to start from. A new order is not stored until its
   first item: an empty order never shows anywhere, and leaving one discards it.
2. **Dine in / Take out** is a toggle on the order panel (a tray or a bag).
   A new order starts as the store's default (*Settings → Counter orders start
   as*, take out unless changed). It can change until the order is paid. It
   shows on the receipt, the kitchen ticket and screen, the pickup board and
   the shift report (*Dine in: 12 · Take out: 30*).
3. **Pay**: the usual pay screen (cash or card, the shift prompt, change due).
   Only when the order is paid in full is it an order: a counter order gets
   its number then (101, 102 and so on, again from 101 each business day), it
   goes to the kitchen (tickets and the kitchen screen, when kitchen tickets
   are on) and shows on the pickup board as *preparing*. An unpaid order never
   goes to the kitchen.
4. **The receipt** shows the number big (*Order #101 · Take out*). It closes
   after 8 seconds, or on *Done*, and the next new order opens.
5. **Clear order** (the bin on the left): drops an unpaid order. Nothing was
   paid and nothing went to the kitchen, so no manager is needed.
6. **Kiosk orders** arrive unpaid, as *waiting to pay*, already with their
   order number (the next one of the day, like any order: 101, 102...). It is
   the guest's one number, like at a burger chain: on the kiosk's last screen,
   on the ticket the store prints for them, and the same at the counter, the
   kitchen, the board and the receipt. They wait in the strip at the top of
   the counter (*#112 $14.50 · to pay*). The cashier taps one, it loads on the
   order panel, Pay, and from there it is like any order: the kitchen, the
   board. A kiosk order not paid within 30 minutes is dropped, and its number
   is not given again (a gap in the day's numbers). Tapping one while ringing
   another order asks first, then clears that order.
7. **Ready**: when the kitchen screen bumps the order's last card (kitchen and
   bar both, if it has both), or *Mark ready* on the **Orders** panel. The
   number moves to the *Ready* column with a chime.
8. **Picked up**: *Picked up* on the Orders panel. The number leaves the
   board. The Orders panel lists today's paid orders (newest first) with
   *Mark ready*, *Picked up*, *Recall* (back to ready) and *Reprint*.

The store enforces all of this: only a paid order can be ready or picked up
(`409 order_not_paid`), dine in / take out can't change once paid
(`order_paid`), and a new order needs its first item. Kitchen tickets and the
kitchen screen show the order number (`#101 · Take out`) where a pub shows
the table.

**Old test orders.** The first counter (PR #58) numbered and listed orders
before they were paid. On its first start with this version, the store
cleans up what it left: an empty unpaid order is dropped, and an unpaid one
with items is voided as *Test order (old counter flow)*. Paid ones stay.

Store routes (signed in): `POST /counter/orders` (a new order with its first
item), `POST /counter/orders/{id}/mode`, `POST /counter/orders/{id}/discard`,
`GET /counter/waiting` (the kiosk queue), `GET /counter/orders` (the Orders
panel), `GET /counter/orders/{id}`, `POST /counter/orders/{id}/status`,
`GET` / `PUT /counter/settings` (manager). Paying is the usual
`/checks/{id}/tenders` and `/checks/{id}/finalize`.

## The kiosk app

A separate Flutter build of the same code, like the stock and card-reader
apps: `--dart-define=POS_APP=kiosk`. On Android it has its own app id,
`dev.dwhipstock.pos_kiosk`. It runs portrait and full screen, never sleeps,
and never runs a store of its own.

- **Welcome**: *Touch to order*, with buttons for Français, English, Español
  and Deutsch.
- **Dine in / Take out**, then **categories and big photo tiles**. Items with
  more than one size open a size picker. Alcohol shows *Alcohol — 21+ only.
  Staff will check ID at the counter*. This is a note only; nothing is
  blocked. At the counter, a kiosk order with alcohol shows an ID *21+* badge
  in the kiosk strip.
- **Add a drink?** On the way to the cart, once per order: when the order has
  a main (a burger, chicken or a salad) but no drink, a full-width step
  offers up to four soft drinks, with photos and prices. If there is no side
  either, a second row offers fries and sides; with a drink and a side but no
  dessert, desserts. Two rows at most, a drink first. One tap adds an item
  (sizes open the usual size picker) and goes on to the cart; the big *No
  thanks, continue* goes to the cart too. The step never shows again in that
  order, and alcohol is never suggested (a beer or wine already counts as the
  drink). The store picks the rows (`POST /kiosk/upsell` with the cart): only
  items on sale, best sellers of the last 7 days first, then the menu's
  order. Which categories are mains, drinks, sides and desserts is set per
  venue (`CopperLanternExpressSeed.upsell`, or `CustomerConfig.upsell` for
  another brand; a quick-serve store's default uses the same category ids).
- **Cart** (quantities, subtotal, "taxes added at the counter"), then **Place
  order**. There is no review or confirmation step after that. The store
  prints the guest's ticket on the receipt printer (see below). The last
  screen says *Your order number is #101. Take your ticket to the counter to
  pay.* and goes back to Welcome after 10 seconds, or sooner on a tap. The
  guest pays with #101 at the counter and is called by #101 at pickup.

**The kiosk ticket.** The kiosk has no printer; the store prints the ticket on
its receipt printer (the POS's ESC/POS network printer) the moment a kiosk
order is placed: the brand, the order number big, dine in / take out, the
items with prices, the subtotal and taxes, the total, and the cash total (to
the nickel), then *Please pay at the counter*, all in the language the guest
chose at the kiosk, money as $1,234.56 in every language. It goes through the
receipt printer's queue: an offline printer is logged and never stops the
order. It prints on paper even when receipts are digital only. To turn it
off: *Settings → Print a ticket for kiosk orders* (on by default; `kioskTicket`
in `GET` / `PUT /counter/settings`). With it off, the kiosk says *Please pay at
the counter.* instead.
- **Idle timeout**: after 90 seconds with no touch mid-order, the cart is
  cleared and the kiosk goes back to Welcome.

**Pairing.** Each kiosk is a paired device of the store, and a store can have
any number of them. On the POS, a manager opens *Orders → Pair a kiosk* to get
a 6-digit code (one use, 10 minutes). The kiosk finds the Express store on
the Wi-Fi (it skips the pubs and the shops); if it can't find it, type the
store's address. Then enter the code. The kiosk keeps its own device token
and sends it as `X-Device-Token`. Store routes: `POST /kiosk/pair`,
`GET /kiosk/config`, `POST /kiosk/upsell`, `POST /kiosk/orders` (with `lang`,
the guest's language, for the ticket). The menu comes from the open
`GET /items` and `GET /categories`.

## The pickup board

`http://<store>:<port>/pickup` is a page the store serves, like `/kitchen`,
for a TV or any browser. It has two columns, *Preparing* and *Ready*, with
big numbers. A new ready number flashes and plays a chime; tap *Sound* once
to allow sound, since browsers block it until someone taps the page. The page
signs in to nothing and shows paid order numbers only, each with *Sur place
· Dine in* or *Pour emporter · Take out* under it, read from
`GET /pickup/board`. The POS's *Pickup board* button shows the exact address.

## Photos

Dishes the pubs also serve keep the pubs' item ids: `lantern-burger`,
`mushroom-burger`, `veggie-burger`, `wings`, `club`, `late-fries`, `poutine`,
`onion-rings`, `caesar-salad`, `harvest-salad`, `brownie`, `lantern-lager`,
`north-ipa`, `pinot-noir` and `riesling`. The AI photo copy mode gives them
the pubs' pictures at no cost:

    python3 scripts/ai-menu-photos.py --store http://<pub>:8080 --copy-to http://<express>:8098

The new items (double cheeseburger, tenders, maple sundae, fountain soda,
lemonade, iced tea, sparkling water) start without photos and show their
letter badges. Iced tea and sparkling water are new in the seed: an Express
store seeded before them has only the two drinks until its data is reset.

## Demo walkthrough (about 5 minutes)

1. **Start the store** on the Mac: `scripts/kiosk-setup.sh --store`. It runs
   on :8098 with kitchen tickets on, data in `.demo/express/`. If
   `.env.local` has `STORE_API_KEY_EXPRESS` and the local cloud is up
   (`scripts/demo-up.sh`), it syncs to the Copper Lantern portal. The script
   prints the POS, board, kitchen and kiosk addresses.
2. **POS**: `cd client && flutter run -d macos --dart-define=SERVER_URL=http://localhost:8098`.
   Sign in as the manager (PIN 1234). The POS opens on a new order; there
   is no floor plan and no order list.
3. **Board and kitchen**: open `/pickup` on the TV (or a second browser
   window) and `/kitchen` in another.
4. **Kiosk**: `scripts/kiosk-setup.sh --kiosk <adb serial>` installs and
   starts it. On the POS, tap *Pair a kiosk* and type the code on the kiosk.
5. **Order at the kiosk**: pick Español, *Para llevar*, a burger, large fries
   and an IPA. The ID note appears. Tap *Mi pedido*: the kiosk offers a
   dessert (*¿Algo dulce?*; the IPA counts as the drink, the fries as the
   side). Tap *No, gracias, continuar*, then *Hacer el pedido*. The kiosk
   shows **#101** and the receipt printer prints the guest's ticket in
   Spanish. At the same moment, *#101* appears in the kiosk strip on the POS.
   Nothing is in the kitchen or on the board yet: it is not paid. (For the
   drink step: order just a burger.)
6. **Pay the kiosk order**: tap *#101*, Pay, cash. The receipt says
   *Order #101 · Take out*; #101 appears on the kitchen screen and under
   *Preparing* on the board. The receipt closes and a new order opens.
7. **Order at the counter**: tap *Dine in*, ring two items, Pay. #102 goes to
   the kitchen and the board.
8. **Kitchen**: bump #101 at Kitchen and at Bar. #101 moves to *Ready* with
   the chime. On the POS, *Orders → Picked up* on #101: it leaves the board.
9. **Portal**: the Express venue appears next to Glenwood South and Plateau, with
   its sales.

Stop the store with `scripts/kiosk-setup.sh --stop`.

## Not in scope

- Paying at the kiosk: every order is paid at the counter.
- Holding an unpaid counter order to finish later: ring it, pay it, or clear it.
- Item modifiers beyond sizes: kiosk lines carry no free-text notes.
- A second POS terminal: the counter is one POS plus any number of kiosks.
