-- 032: guest Wi-Fi, printed on a join slip (and on table QR slips) so guests'
-- phones can join the venue network. The table QR points at the tablet's LAN
-- address, so ordering needs the venue Wi-Fi. Empty ssid = not configured.
-- wifi_security is the WIFI: QR "T" value: WPA (default), WEP or nopass.
-- The password stays on the store: it is never written to the sync outbox.
ALTER TABLE venue_settings ADD COLUMN wifi_ssid VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE venue_settings ADD COLUMN wifi_password VARCHAR(63) NOT NULL DEFAULT '';
ALTER TABLE venue_settings ADD COLUMN wifi_security VARCHAR(8) NOT NULL DEFAULT 'WPA';
ALTER TABLE venue_settings ADD COLUMN wifi_hidden INT NOT NULL DEFAULT 0;
-- Which surface minted a session: 'pos' (the tablet terminal) or 'staff_app'
-- (the staff phone web app). The guest Wi-Fi password is readable only from a
-- manager's POS session, and on-prem both kinds can be device-unbound, so the
-- device column alone cannot tell them apart.
ALTER TABLE sessions ADD COLUMN surface VARCHAR(16) NOT NULL DEFAULT 'pos';
