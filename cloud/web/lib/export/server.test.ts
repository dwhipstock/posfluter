// Server-built exports: the paths the page asks for and the saved file name. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { ALL_DATA_PATH, canExport, exportPath, filenameFrom } from "./server";

const range = { from: "2026-07-01", to: "2026-07-31" };

test("a dated export carries the range, and the picked store as venue", () => {
  assert.equal(exportPath("sales", "csv", range, null), "/v1/exports/sales.csv?from=2026-07-01&to=2026-07-31");
  assert.equal(
    exportPath("tax-summary", "xlsx", range, "plateau"),
    "/v1/exports/tax-summary.xlsx?from=2026-07-01&to=2026-07-31&venue=plateau"
  );
});

test("menu and staff are current state: no dates", () => {
  assert.equal(exportPath("staff", "csv", range, null), "/v1/exports/staff.csv");
  assert.equal(exportPath("menu-items", "xlsx", range, "a b"), "/v1/exports/menu-items.xlsx?venue=a%20b");
});

test("the whole-data zip is never narrowed by the pickers", () => {
  assert.equal(ALL_DATA_PATH, "/v1/exports/all.zip");
});

test("owners and managers export, viewers don't", () => {
  assert.equal(canExport("owner"), true);
  assert.equal(canExport("manager"), true);
  assert.equal(canExport("viewer"), false);
  assert.equal(canExport(undefined), true); // older server: no role = the owner
});

test("the file name comes from Content-Disposition, never as a path", () => {
  assert.equal(filenameFrom('attachment; filename="sales_2026-07-01_2026-07-31.csv"'), "sales_2026-07-01_2026-07-31.csv");
  assert.equal(filenameFrom("attachment; filename=all-data_2026-10-01.zip"), "all-data_2026-10-01.zip");
  assert.equal(filenameFrom('attachment; filename="../../x.csv"'), ".._.._x.csv");
  assert.equal(filenameFrom(null), null);
  assert.equal(filenameFrom("inline"), null);
});
