#include "touch_keyboard.h"

#include <inputpaneinterop.h>
#include <roapi.h>
#include <wrl/event.h>
#include <wrl/wrappers/corewrappers.h>

using ABI::Windows::UI::ViewManagement::IInputPane;
using ABI::Windows::UI::ViewManagement::IInputPane2;
using ABI::Windows::UI::ViewManagement::IInputPaneVisibilityEventArgs;
using ABI::Windows::UI::ViewManagement::InputPane;
using ABI::Windows::UI::ViewManagement::InputPaneVisibilityEventArgs;
using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::Wrappers::HStringReference;

namespace {

using InputPaneHandler = ABI::Windows::Foundation::ITypedEventHandler<
    InputPane*, InputPaneVisibilityEventArgs*>;

void TryHide(IInputPane* pane) {
  ComPtr<IInputPane2> pane2;
  if (pane && SUCCEEDED(pane->QueryInterface(IID_PPV_ARGS(&pane2)))) {
    boolean hidden = false;
    pane2->TryHide(&hidden);
  }
}

}  // namespace

TouchKeyboardSuppressor::~TouchKeyboardSuppressor() {
  if (pane_ && subscribed_) {
    pane_->remove_Showing(showing_token_);
  }
}

bool TouchKeyboardSuppressor::EnsurePane(HWND window) {
  if (pane_) {
    return true;
  }
  ComPtr<IInputPaneInterop> interop;
  HRESULT hr = ::RoGetActivationFactory(
      HStringReference(RuntimeClass_Windows_UI_ViewManagement_InputPane).Get(),
      IID_PPV_ARGS(&interop));
  if (FAILED(hr)) {
    return false;
  }
  hr = interop->GetForWindow(window, IID_PPV_ARGS(&pane_));
  if (FAILED(hr)) {
    pane_.Reset();
    return false;
  }
  return true;
}

void TouchKeyboardSuppressor::SetSuppressed(HWND window, bool suppressed) {
  if (suppressed == subscribed_) {
    return;
  }
  if (!suppressed) {
    pane_->remove_Showing(showing_token_);
    subscribed_ = false;
    return;
  }
  if (!EnsurePane(window)) {
    return;
  }
  auto handler = Callback<InputPaneHandler>(
      [](IInputPane* sender, IInputPaneVisibilityEventArgs*) -> HRESULT {
        TryHide(sender);
        return S_OK;
      });
  if (SUCCEEDED(pane_->add_Showing(handler.Get(), &showing_token_))) {
    subscribed_ = true;
  }
  TryHide(pane_.Get());
}

void TouchKeyboardSuppressor::Hide(HWND window) {
  if (subscribed_ && EnsurePane(window)) {
    TryHide(pane_.Get());
  }
}
