#ifndef RUNNER_TOUCH_KEYBOARD_H_
#define RUNNER_TOUCH_KEYBOARD_H_

#include <windows.h>
#include <windows.ui.viewmanagement.h>
#include <wrl/client.h>

// Keeps the Windows touch keyboard from popping up over the app's own
// on-screen keyboard (lib/keyboard/): while suppressed, every time Windows
// starts to show its input pane for this window, it is asked to hide again.
// Dart turns this on at startup unless POS_SYSTEM_KEYBOARD=1.
class TouchKeyboardSuppressor {
 public:
  TouchKeyboardSuppressor() = default;
  ~TouchKeyboardSuppressor();

  TouchKeyboardSuppressor(const TouchKeyboardSuppressor&) = delete;
  TouchKeyboardSuppressor& operator=(const TouchKeyboardSuppressor&) = delete;

  void SetSuppressed(HWND window, bool suppressed);

  // Hides the touch keyboard now, if it is up and suppression is on.
  void Hide(HWND window);

 private:
  bool EnsurePane(HWND window);

  Microsoft::WRL::ComPtr<ABI::Windows::UI::ViewManagement::IInputPane> pane_;
  EventRegistrationToken showing_token_ = {};
  bool subscribed_ = false;
};

#endif  // RUNNER_TOUCH_KEYBOARD_H_
