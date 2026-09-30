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

On-screen keyboard
------------------
The POS brings its own keyboard up whenever a text box is touched, in the
language the screen is in: it changes with the EN / FR / ES / DE button.
To use the Windows touch keyboard instead, set the environment variable
POS_SYSTEM_KEYBOARD=1 (System > About > Advanced system settings >
Environment Variables), then reopen the POS.

Not on Windows: camera barcode scanning (a USB or Bluetooth scanner works),
the Stripe Bluetooth card reader, and paper receipts over Bluetooth.
