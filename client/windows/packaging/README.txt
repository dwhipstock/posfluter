POS for Windows
===============

1. Unzip, and keep this whole folder together (the .exe needs the data and
   store folders beside it).
2. Double-click {{EXE}}.
   First run only:
   - Windows SmartScreen may say "Windows protected your PC":
     click "More info", then "Run anyway".
   - Windows Firewall may ask about Java: allow it on Private networks
     (so staff phones and the card reader phone can reach this store).
3. The POS starts its own store (10-20 seconds the first time) and opens
   the sign-in screen. Closing the window stops the store.

Store data and settings
-----------------------
Everything lives in %LOCALAPPDATA%\{{BRAND}}\
  pos.db            the store's database
  store.properties  settings (same keys as the Android tablet), e.g.
                      print.receipts=digital
                      forecourt.url=http://<pump simulator IP>:8086
                      payment.terminal=simulator
                      payment.terminal.host=<terminal IP>
                    Edit it with Notepad, then close and reopen the POS.
  store.log         the store's log, if something goes wrong

Not on Windows: camera barcode scanning (a USB or Bluetooth scanner works),
the Stripe Bluetooth card reader, and paper receipts over Bluetooth.
