#!/usr/bin/env python3
"""Emit deterministic PostgreSQL seed SQL for a two-month manager demo.

The generated rows use a reserved numeric range (>= 900000), so rerunning this
script replaces only its own synthetic history and never touches real store data.

Usage:
  python3 scripts/seed-manager-demo-history.py --through 2026-09-20 --days 61 \
    | psql "$DATABASE_URL"
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import random


TENANT = "copperlantern"
VENUE = "vieux-port"  # cloud migration 014 renamed the original "main" store
ID_BASE = 900_000

# item id, variant id, category id, English display name, pre-tax unit price
# (CAD cents; the store's seeded menu, CopperLanternSeed)
MENU = [
    ("lantern-lager", "lantern-lager:pint", "beer-cider", "Lantern House Lager", 750),
    ("amber-ale", "amber-ale:pint", "beer-cider", "Copper Amber Ale", 795),
    ("hazy-ipa", "hazy-ipa:regular", "beer-cider", "Local Hazy IPA", 825),
    ("irish-stout", "irish-stout:regular", "beer-cider", "Irish Stout", 850),
    ("dry-cider", "dry-cider:regular", "beer-cider", "Ontario Dry Cider", 795),
    ("hop-water", "hop-water:regular", "beer-cider", "Sparkling Hop Water", 495),
    ("wings", "wings:regular", "starters", "Chicken Wings", 1675),
    ("poutine", "poutine:regular", "starters", "Classic Poutine", 1300),
    ("pretzel", "pretzel:regular", "starters", "Giant Pub Pretzel", 1225),
    ("nachos", "nachos:regular", "starters", "Loaded Pub Nachos", 1750),
    ("lantern-burger", "lantern-burger:regular", "burgers-sandwiches", "Copper Lantern Burger", 1925),
    ("club", "club:regular", "burgers-sandwiches", "Grilled Chicken Club", 1850),
    ("fish-chips", "fish-chips:regular", "mains-salads", "Beer-Battered Fish and Chips", 2025),
    ("chicken-pot-pie", "chicken-pot-pie:regular", "mains-salads", "Chicken Pot Pie", 1850),
    ("steak-frites", "steak-frites:regular", "mains-salads", "Steak Frites", 2650),
    ("caesar-salad", "caesar-salad:regular", "mains-salads", "Caesar Salad", 1300),
    ("cheesecake", "cheesecake:regular", "desserts", "Maple Cheesecake", 850),
    ("brownie", "brownie:regular", "desserts", "Stout Brownie", 800),
    ("lantern-mule", "lantern-mule:regular", "cocktails", "Lantern Mule", 1300),
    ("espresso-martini", "espresso-martini:regular", "cocktails", "Espresso Martini", 1395),
]

ZONES = [
    ("upper", "Dining Room", "U", 14),
    ("bar", "Bar", "B", 8),
    ("outside", "Patio", "O", 10),
    ("lower", "Games Room", "L", 12),
]
STAFF = ["Alex", "Morgan", "Jamie", "Taylor"]


def q(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


VENUE_TZ = "America/New_York"

# Québec taxes added on top of the pre-tax subtotal, as the store charges them:
# code, French label, English label, rate (percent, decimal string), registration no.
TAXES = [
    ("GST", "TPS", "GST", "5", "123456789 RT0001"),
    ("QST", "TVQ", "QST", "9.975", "1234567890 TQ0001"),
]


def tax_cents(subtotal: int, rate: str) -> int:
    """One tax on the whole check, rounded half-up to the cent (the store's math)."""
    thousandths = round(float(rate) * 1000)  # 9.975 -> 9975
    return (subtotal * thousandths * 2 + 100_000) // 200_000


def taxes_for(subtotal: int) -> list[dict]:
    return [
        {"code": code, "labelFr": fr, "labelEn": en, "ratePercent": rate,
         "registrationNumber": reg, "amountCents": tax_cents(subtotal, rate)}
        for code, fr, en, rate, reg in TAXES
    ]


def jsonb(value) -> str:
    return q(json.dumps(value, separators=(",", ":"), ensure_ascii=False)) + "::jsonb"


def ts(value: dt.datetime) -> str:
    # columns are timestamptz: a venue wall time must name its zone explicitly
    return f"({q(value.isoformat(sep=' ', timespec='seconds'))}::timestamp AT TIME ZONE {q(VENUE_TZ)})"


def main() -> None:
    global VENUE
    parser = argparse.ArgumentParser()
    parser.add_argument("--through", type=dt.date.fromisoformat, default=dt.date.today())
    parser.add_argument("--days", type=int, default=61)
    parser.add_argument("--venue", default=VENUE, help="store (venue id): vieux-port or plateau")
    args = parser.parse_args()
    if not 1 <= args.days <= 366:
        parser.error("--days must be between 1 and 366")
    VENUE = args.venue

    rng = random.Random(0xC0FFEE)
    first_day = args.through - dt.timedelta(days=args.days - 1)
    sql: list[str] = [
        "BEGIN;",
        f"DELETE FROM refunds WHERE tenant_id={q(TENANT)} AND venue_id={q(VENUE)} AND refund_id >= {ID_BASE};",
        f"DELETE FROM check_tenders WHERE tenant_id={q(TENANT)} AND venue_id={q(VENUE)} AND tender_id >= {ID_BASE * 100};",
        f"DELETE FROM check_lines WHERE tenant_id={q(TENANT)} AND venue_id={q(VENUE)} AND check_id >= {ID_BASE};",
        f"DELETE FROM checks WHERE tenant_id={q(TENANT)} AND venue_id={q(VENUE)} AND check_id >= {ID_BASE};",
        f"DELETE FROM cash_movements WHERE tenant_id={q(TENANT)} AND venue_id={q(VENUE)} AND movement_id >= {ID_BASE};",
        f"DELETE FROM shifts WHERE tenant_id={q(TENANT)} AND venue_id={q(VENUE)} AND shift_id >= {ID_BASE};",
    ]

    check_id = ID_BASE
    tender_id = ID_BASE * 100
    refund_id = ID_BASE
    movement_id = ID_BASE
    closed_count = 0
    gross_total = 0

    for day_offset in range(args.days):
        day = first_day + dt.timedelta(days=day_offset)
        weekday = day.weekday()
        low_high = [(8, 12), (9, 13), (12, 17), (14, 20), (23, 30), (27, 36), (15, 21)][weekday]
        sale_count = rng.randint(*low_high)
        shift_id = ID_BASE + day_offset
        opened_at = dt.datetime.combine(day, dt.time(11, rng.randint(0, 12)))
        closed_at = dt.datetime.combine(day, dt.time(23, rng.randint(15, 55)))
        shift_gross = 0
        cash_total = 0
        card_total = 0

        for sale_index in range(sale_count):
            check_id += 1
            zone_id, zone_name, table_prefix, table_count = rng.choice(ZONES)
            table_no = rng.randint(1, table_count)
            table_id = f"{zone_id}-{table_no}"
            table_label = f"{table_prefix}-{table_no}"
            hour = rng.choices(
                [12, 13, 14, 17, 18, 19, 20, 21, 22],
                [6, 7, 3, 4, 8, 11, 11, 8, 4],
            )[0]
            minute = rng.randint(0, 59)
            close_time = dt.datetime.combine(day, dt.time(hour, minute, rng.randint(0, 59)))
            open_time = close_time - dt.timedelta(minutes=rng.randint(28, 105))
            staff = rng.choice(STAFF)

            # Drinks are common, with food-heavy checks around meal periods.
            item_count = rng.choices([1, 2, 3, 4, 5], [8, 26, 34, 23, 9])[0]
            picked = []
            for _ in range(item_count):
                if rng.random() < 0.42:
                    picked.append(rng.choice(MENU[:6] + MENU[18:]))
                else:
                    picked.append(rng.choice(MENU[6:18]))

            line_rows = []
            subtotal = 0
            for line_no, (item, variant, category, name, price) in enumerate(picked, 1):
                qty = 2 if rng.random() < 0.14 else 1
                line_total = price * qty
                subtotal += line_total
                line_rows.append(
                    f"({q(TENANT)},{q(VENUE)},{check_id},{line_no},{q(item)},{q(variant)},{q(category)},"
                    f"{q(name)},{q(name)},NULL,NULL,{q(name)},{qty},{price},{line_total})"
                )

            # GST and QST on top of the pre-tax subtotal: the guest pays the total
            taxes = taxes_for(subtotal)
            gst, qst = (t["amountCents"] for t in taxes)
            tax = gst + qst
            total = subtotal + tax

            is_void = rng.random() < 0.018
            status = "VOID" if is_void else "CLOSED"
            void_reason = rng.choice(["Customer changed mind", "Duplicate order", "Entered by mistake"]) if is_void else None
            sql.append(
                "INSERT INTO checks (tenant_id,venue_id,check_id,status,table_id,table_label,zone_id,zone_name_fr,zone_name_en,"
                "shift_id,opened_at,closed_at,opened_by,grand_total_cents,tax_included_cents,corkage_bottles,corkage_cents,"
                "service_charge_cents,void_reason,voided_by,gst_cents,qst_cents,taxes) VALUES "
                f"({q(TENANT)},{q(VENUE)},{check_id},{q(status)},{q(table_id)},{q(table_label)},{q(zone_id)},"
                f"{q(zone_name)},{q(zone_name)},{shift_id},{ts(open_time)},{ts(close_time)},{q(staff)},{total},{tax},0,0,0,"
                f"{q(void_reason)},{q(staff) if is_void else 'NULL'},{gst},{qst},{jsonb(taxes)});"
            )
            sql.append(
                "INSERT INTO check_lines (tenant_id,venue_id,check_id,line_id,item_id,variant_id,category_id,name_fr,name_en,"
                "variant_label_fr,variant_label_en,display_name,qty,unit_price_cents,line_total_cents) VALUES "
                + ",".join(line_rows) + ";"
            )

            if not is_void:
                closed_count += 1
                gross_total += total
                shift_gross += total
                tender_id += 1
                tender_type = "CARD" if rng.random() < 0.82 else "CASH"
                tendered = total if tender_type == "CARD" else ((total + 499) // 500) * 500
                change = tendered - total
                sql.append(
                    "INSERT INTO check_tenders (tenant_id,venue_id,tender_id,check_id,type,amount_tendered_cents,"
                    "amount_applied_cents,rounding_adjustment_cents,change_cents,tendered_at) VALUES "
                    f"({q(TENANT)},{q(VENUE)},{tender_id},{check_id},{q(tender_type)},{tendered},{total},0,{change},{ts(close_time)});"
                )
                if tender_type == "CARD":
                    card_total += total
                else:
                    cash_total += total

                # A small, believable number of full refunds on later days.
                if day_offset > 2 and rng.random() < 0.012:
                    refund_id += 1
                    refund_time = close_time + dt.timedelta(hours=rng.randint(2, 30))
                    reason = rng.choice(["Customer complaint", "Order error", "Duplicate payment"])
                    sql.append(
                        "INSERT INTO refunds (tenant_id,venue_id,refund_id,check_id,shift_id,gross_cents,net_cents,"
                        "tax_included_cents,tender_type,reason,refunded_by,table_label,zone_id,zone_name_fr,zone_name_en,created_at,"
                        "gst_cents,qst_cents) VALUES "
                        f"({q(TENANT)},{q(VENUE)},{refund_id},{check_id},{shift_id},{total},{subtotal},{tax},{q(tender_type)},"
                        f"{q(reason)},{q('Morgan')},{q(table_label)},{q(zone_id)},{q(zone_name)},{q(zone_name)},{ts(refund_time)},"
                        f"{gst},{qst});"
                    )

        breakdown = json.dumps({"CASH": cash_total, "CARD": card_total}, separators=(",", ":"))
        expected_cash = 50000 + cash_total
        over_short = rng.choice([-500, -200, 0, 0, 0, 0, 100, 200, 500])
        sql.append(
            "INSERT INTO shifts (tenant_id,venue_id,shift_id,status,opened_at,opened_by,opening_float_cents,closed_at,closed_by,"
            "revenue_cents,transaction_count,avg_check_cents,corkage_cents,tender_breakdown,expected_cash_cents,"
            "closing_count_cents,over_short_cents) VALUES "
            f"({q(TENANT)},{q(VENUE)},{shift_id},'CLOSED',{ts(opened_at)},{q('Alex')},50000,{ts(closed_at)},"
            f"{q('Morgan')},{shift_gross},{sale_count},{shift_gross // max(sale_count, 1)},0,{q(breakdown)}::jsonb,"
            f"{expected_cash},{expected_cash + over_short},{over_short});"
        )
        if day_offset % 10 == 4:
            movement_id += 1
            movement_time = opened_at + dt.timedelta(hours=4)
            sql.append(
                "INSERT INTO cash_movements (tenant_id,venue_id,movement_id,shift_id,direction,amount_cents,reason,created_by,created_at) VALUES "
                f"({q(TENANT)},{q(VENUE)},{movement_id},{shift_id},'OUT',7500,'Petty cash — supplies','Alex',{ts(movement_time)});"
            )

    sql.extend([
        "COMMIT;",
        f"\\echo Seeded {closed_count} closed demo checks totaling {gross_total} cents from {first_day} through {args.through}.",
    ])
    print("\n".join(sql))


if __name__ == "__main__":
    main()
