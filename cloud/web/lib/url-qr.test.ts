// The portal's UrlQr: the QR it draws encodes exactly the URL it prints.
// Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QRCodeSVG } from "qrcode.react";
import { UrlQr } from "../components/url-qr";
import { staffAppLink } from "./links";

const qrMarkup = (value: string) =>
  renderToStaticMarkup(createElement(QRCodeSVG, { value, size: 200, bgColor: "#ffffff", fgColor: "#000000", level: "M" }));
const esc = (s: string) => s.replace(/&/g, "&amp;");

for (const url of ["http://192.168.1.50:8080", "https://portal.example.com/staff-app?store=vieux-port&x=1"]) {
  test(`UrlQr: the QR is ${url} and the text is the same URL`, () => {
    const html = renderToStaticMarkup(createElement(UrlQr, { url }));
    assert.ok(html.includes(qrMarkup(url)), "QR payload == shown URL");
    assert.ok(html.includes(`>${esc(url)}</figcaption>`), "the URL is shown as text");
    assert.ok(!html.includes(qrMarkup(url + "x")));
  });
}

test("UrlQr never draws smaller than 200px", () => {
  const html = renderToStaticMarkup(createElement(UrlQr, { url: "https://example.com", size: 120 }));
  assert.match(html, /width="200"/);
});

test("staff app link: the portal's per-store entry point", () => {
  assert.equal(staffAppLink("https://portal.example.com", "vieux-port"), "https://portal.example.com/staff-app?store=vieux-port");
  assert.equal(staffAppLink("https://portal.example.com/", "a b"), "https://portal.example.com/staff-app?store=a%20b");
});
