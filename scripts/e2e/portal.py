#!/usr/bin/env python3
"""Headless portal client for the acceptance e2e (stdlib only).

Handles the full auth flow including first-login TOTP enrollment, then exposes
GET/POST helpers carrying the session cookie. The TOTP secret is cached to a file
so later runs log straight in. Cloud API/portal origin is env-overridable.
"""
import base64, hashlib, hmac, json, os, struct, sys, time, urllib.request, urllib.error

BASE = os.environ.get("E2E_PORTAL_BASE", "https://copperlantern.example.com")

def totp(secret_b32: str, step: int = 30, digits: int = 6) -> str:
    key = base64.b32decode(secret_b32.upper() + "=" * (-len(secret_b32) % 8))
    counter = struct.pack(">Q", int(time.time()) // step)
    mac = hmac.new(key, counter, hashlib.sha1).digest()
    offset = mac[-1] & 0x0F
    code = (struct.unpack(">I", mac[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)

class Portal:
    def __init__(self, base=BASE):
        self.base = base
        self.cookie = None

    def req(self, method, path, body=None, headers=None):
        h = dict(headers or {})
        if body is not None:
            body = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if self.cookie:
            h["Cookie"] = self.cookie
        r = urllib.request.Request(self.base + path, data=body, method=method, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=30) as res:
                setc = res.headers.get("Set-Cookie")
                if setc and "pos_portal_session=" in setc:
                    self.cookie = setc.split(";")[0]
                raw = res.read()
                return res.status, json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raw = e.read()
            try: parsed = json.loads(raw)
            except Exception: parsed = {"raw": raw[:200].decode(errors="replace")}
            return e.code, parsed

    def login(self, email, password, secret_file):
        st, body = self.req("POST", "/v1/auth/login", {"email": email, "password": password})
        assert st == 200, f"login: {st} {body}"
        stage = body.get("stage")
        if stage == "totp_setup":
            secret = body["secret"]
            open(secret_file, "w").write(secret)
            st, body2 = self.req("POST", "/v1/auth/totp/confirm",
                                 {"pendingToken": body["pendingToken"], "code": totp(secret)})
            assert st == 200, f"totp confirm: {st} {body2}"
            print(f"enrolled TOTP; secret cached in {secret_file}; backup codes: {body2.get('backupCodes', [])[:2]}...")
        elif stage == "totp":
            secret = open(secret_file).read().strip()
            st, body2 = self.req("POST", "/v1/auth/totp",
                                 {"pendingToken": body["pendingToken"], "code": totp(secret)})
            assert st == 200, f"totp: {st} {body2}"
        else:
            raise AssertionError(f"unexpected stage {stage}")
        st, me = self.req("GET", "/v1/auth/me")
        assert st == 200, f"me: {st} {me}"
        print("logged in as", me)
        return self

if __name__ == "__main__":
    email, password, secret_file = sys.argv[1], sys.argv[2], sys.argv[3]
    p = Portal().login(email, password, secret_file)
    print("cookie ok")
