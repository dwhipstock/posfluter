#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>

#include "flutter_window.h"
#include "utils.h"

// The window title follows the brand (POS_BRAND env at build time, the same
// value as --dart-define=POS_BRAND; see runner/CMakeLists.txt).
#if defined(POS_BRAND_SAGEPOPPY)
#define POS_WINDOW_TITLE L"Sage & Poppy POS"
#elif defined(POS_BRAND_PRONGHORN)
#define POS_WINDOW_TITLE L"Pronghorn POS"
#else
#define POS_WINDOW_TITLE L"Copper Lantern POS"
#endif

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t *command_line, _In_ int show_command) {
  // Attach to console when present (e.g., 'flutter run') or create a
  // new console when running with a debugger.
  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  // Initialize COM, so that it is available for use in the library and/or
  // plugins.
  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  // The POS starts its store as a child process (lib/desktop_store.dart).
  // A kill-on-close job ties it to this app: when the app exits or crashes,
  // Windows stops the store too, so no orphan keeps the port.
  if (HANDLE job = ::CreateJobObject(nullptr, nullptr)) {
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = {};
    info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    ::SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info,
                              sizeof(info));
    ::AssignProcessToJobObject(job, ::GetCurrentProcess());
  }

  flutter::DartProject project(L"data");

  std::vector<std::string> command_line_arguments =
      GetCommandLineArguments();

  project.set_dart_entrypoint_arguments(std::move(command_line_arguments));

  FlutterWindow window(project);
  Win32Window::Point origin(10, 10);
  Win32Window::Size size(1280, 720);
  if (!window.Create(POS_WINDOW_TITLE, origin, size)) {
    return EXIT_FAILURE;
  }
  window.SetQuitOnClose(true);

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  return EXIT_SUCCESS;
}
