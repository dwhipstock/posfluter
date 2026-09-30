import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../i18n.dart';

/// "Take a photo" on the Windows tablet. image_picker has no camera of its own
/// on Windows; it hands the job to a delegate, and this is it: our own
/// full-screen camera page (the `camera` package). Once registered,
/// `supportsImageSource(ImageSource.camera)` is true there and every existing
/// "Take a photo / Choose from gallery" dialog offers the camera. Android keeps
/// its system camera app (nothing is registered there).
class CameraCaptureDelegate extends ImagePickerCameraDelegate {
  CameraCaptureDelegate(this.navigatorKey);

  /// The app's root navigator: the camera page is pushed on top of everything.
  final GlobalKey<NavigatorState> navigatorKey;

  /// Registers the delegate on Windows; true when it did.
  static bool register(
    GlobalKey<NavigatorState> navigatorKey, {
    TargetPlatform? platform,
  }) {
    if (kIsWeb ||
        (platform ?? defaultTargetPlatform) != TargetPlatform.windows) {
      return false;
    }
    final picker = ImagePickerPlatform.instance;
    if (picker is! CameraDelegatingImagePickerPlatform) return false;
    picker.cameraDelegate = CameraCaptureDelegate(navigatorKey);
    return true;
  }

  @override
  Future<XFile?> takePhoto({
    ImagePickerCameraDelegateOptions options =
        const ImagePickerCameraDelegateOptions(),
  }) async {
    final nav = navigatorKey.currentState;
    if (nav == null) return null;
    return nav.push<XFile>(
      MaterialPageRoute(
        fullscreenDialog: true,
        builder: (_) =>
            CameraCapturePage(preferred: options.preferredCameraDevice),
      ),
    );
  }

  @override
  Future<XFile?> takeVideo({
    ImagePickerCameraDelegateOptions options =
        const ImagePickerCameraDelegateOptions(),
  }) async => null; // the app never records video
}

/// Which camera opens first: the one [preferred] asks for when it can be told
/// apart, else the first. Windows reports every camera as "front", so the name
/// decides there (the Surface's are "Microsoft Camera Front" / "... Rear").
int pickCamera(List<CameraDescription> cameras, CameraDevice preferred) {
  final rear = preferred == CameraDevice.rear;
  final words = rear ? const ['rear', 'back'] : const ['front'];
  for (final (i, c) in cameras.indexed) {
    final name = c.name.toLowerCase();
    if (words.any(name.contains)) return i;
  }
  final direction = rear ? CameraLensDirection.back : CameraLensDirection.front;
  final i = cameras.indexWhere((c) => c.lensDirection == direction);
  return i < 0 ? 0 : i;
}

Future<XFile?> _pickFromGallery() => ImagePickerPlatform.instance
    .getImageFromSource(source: ImageSource.gallery);

/// Live preview, a big shutter, front/rear switch, Cancel; then the shot with
/// "Use photo" / "Retake". Pops with the photo (an in-memory [XFile]) or null.
/// The camera is released on leaving and whenever the app is not in front, so
/// it is never left locked.
class CameraCapturePage extends StatefulWidget {
  const CameraCapturePage({
    super.key,
    this.preferred = CameraDevice.rear,
    this.pickFromGallery = _pickFromGallery,
  });

  final CameraDevice preferred;

  /// The fallback when there is no usable camera.
  final Future<XFile?> Function() pickFromGallery;

  @override
  State<CameraCapturePage> createState() => _CameraCapturePageState();
}

class _CameraCapturePageState extends State<CameraCapturePage>
    with WidgetsBindingObserver {
  List<CameraDescription> _cameras = const [];
  int _index = 0;
  CameraController? _controller;

  /// Bumped on every open and close: a slow open that was overtaken quits.
  int _gen = 0;
  String? _error;
  bool _busy = false;
  XFile? _shot;
  Uint8List? _shotBytes;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _open();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _close();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      if (_controller == null && _error == null) _open();
    } else {
      _close().then((_) {
        if (mounted) setState(() {});
      });
    }
  }

  Future<void> _open() async {
    final gen = ++_gen;
    try {
      if (_cameras.isEmpty) {
        final cameras = await availableCameras();
        if (gen != _gen || !mounted) return;
        if (cameras.isEmpty) {
          setState(() => _error = L.current.cameraNone);
          return;
        }
        _cameras = cameras;
        _index = pickCamera(cameras, widget.preferred);
      }
      final c = CameraController(
        _cameras[_index],
        ResolutionPreset.veryHigh,
        enableAudio: false,
      );
      _controller = c;
      await c.initialize();
      if (gen != _gen || !mounted) return;
      setState(() {});
    } catch (e) {
      if (gen != _gen || !mounted) return;
      await _close();
      if (!mounted) return;
      final denied = e is CameraException && e.code == 'CameraAccessDenied';
      setState(
        () => _error = denied ? L.current.cameraDenied : L.current.cameraFailed,
      );
    }
  }

  Future<void> _close() async {
    _gen++;
    final c = _controller;
    _controller = null;
    try {
      await c?.dispose();
    } catch (_) {}
  }

  Future<void> _switch() async {
    if (_busy || _cameras.length < 2) return;
    _index = (_index + 1) % _cameras.length;
    await _close();
    if (!mounted) return;
    setState(() {});
    await _open();
  }

  Future<void> _shoot() async {
    final c = _controller;
    if (_busy || c == null || !c.value.isInitialized) return;
    setState(() => _busy = true);
    try {
      final shot = await c.takePicture();
      final bytes = await shot.readAsBytes();
      // camera_windows saves into the user's Pictures folder; keep the bytes
      // and leave no file behind.
      if (shot.path.isNotEmpty) {
        unawaited(File(shot.path).delete().then((_) {}, onError: (_) {}));
      }
      if (!mounted) return;
      setState(() {
        _shot = shot;
        _shotBytes = bytes;
      });
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(L.current.cameraFailed)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _retake() {
    setState(() {
      _shot = null;
      _shotBytes = null;
    });
    if (_controller == null && _error == null) _open();
  }

  Future<void> _leave(XFile? result) async {
    await _close();
    if (mounted) Navigator.of(context).pop(result);
  }

  void _use() {
    final bytes = _shotBytes;
    if (bytes == null) return;
    final path = _shot?.path ?? '';
    _leave(
      XFile.fromData(
        bytes,
        path: path.isEmpty ? 'photo.jpg' : path,
        mimeType: 'image/jpeg',
      ),
    );
  }

  Future<void> _gallery() async {
    final file = await widget.pickFromGallery();
    if (file != null) await _leave(file);
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, box) {
            final wide = box.maxWidth >= box.maxHeight;
            final view = Expanded(child: Center(child: _view(l)));
            final bar = Padding(
              padding: const EdgeInsets.all(24),
              child: Flex(
                direction: wide ? Axis.vertical : Axis.horizontal,
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: _controls(l),
              ),
            );
            return wide
                ? Row(
                    children: [
                      view,
                      SizedBox(width: 240, child: bar),
                    ],
                  )
                : Column(
                    children: [
                      view,
                      SizedBox(height: 200, child: bar),
                    ],
                  );
          },
        ),
      ),
    );
  }

  Widget _view(L l) {
    final bytes = _shotBytes;
    if (bytes != null) {
      return Image.memory(
        bytes,
        fit: BoxFit.contain,
        errorBuilder: (_, _, _) =>
            const Icon(LucideIcons.image, color: Colors.white54, size: 64),
      );
    }
    final error = _error;
    if (error != null) {
      return Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(LucideIcons.camera, color: Colors.white54, size: 64),
            const SizedBox(height: 20),
            Text(
              error,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white, fontSize: 20),
            ),
          ],
        ),
      );
    }
    final c = _controller;
    if (c == null || !c.value.isInitialized) {
      return const CircularProgressIndicator(color: Colors.white);
    }
    // Not CameraPreview: it assumes a phone held upright, and Windows reports
    // no orientation, so the landscape Surface picture would come out squashed.
    final size = c.value.previewSize;
    return AspectRatio(
      aspectRatio: size == null || size.height == 0
          ? 16 / 9
          : size.width / size.height,
      child: c.buildPreview(),
    );
  }

  static final _big = FilledButton.styleFrom(
    minimumSize: const Size(180, 64),
    textStyle: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
  );
  static final _bigOutlined = OutlinedButton.styleFrom(
    minimumSize: const Size(180, 64),
    foregroundColor: Colors.white,
    side: const BorderSide(color: Colors.white70),
    textStyle: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
  );

  List<Widget> _controls(L l) {
    final cancel = OutlinedButton.icon(
      style: _bigOutlined,
      onPressed: () => _leave(null),
      icon: const Icon(LucideIcons.x),
      label: Text(l.cancel),
    );
    if (_shotBytes != null) {
      return [
        OutlinedButton.icon(
          style: _bigOutlined,
          onPressed: _retake,
          icon: const Icon(LucideIcons.rotateCcw),
          label: Text(l.cameraRetake),
        ),
        FilledButton.icon(
          style: _big,
          onPressed: _use,
          icon: const Icon(LucideIcons.check),
          label: Text(l.cameraUsePhoto),
        ),
      ];
    }
    if (_error != null) {
      return [
        FilledButton.icon(
          style: _big,
          onPressed: _gallery,
          icon: const Icon(LucideIcons.images),
          label: Text(l.aiChooseFromGallery),
        ),
        cancel,
      ];
    }
    final ready = _controller?.value.isInitialized ?? false;
    return [
      if (_cameras.length > 1)
        IconButton(
          tooltip: l.cameraSwitch,
          onPressed: _busy ? null : _switch,
          iconSize: 36,
          color: Colors.white,
          style: IconButton.styleFrom(
            fixedSize: const Size(72, 72),
            side: const BorderSide(color: Colors.white70),
          ),
          icon: const Icon(LucideIcons.switchCamera),
        )
      else
        const SizedBox(width: 72, height: 72),
      Semantics(
        button: true,
        label: l.aiTakePhoto,
        child: SizedBox(
          width: 104,
          height: 104,
          child: FilledButton(
            key: const ValueKey('camera-shutter'),
            style: FilledButton.styleFrom(
              shape: const CircleBorder(
                side: BorderSide(color: Colors.white54, width: 6),
              ),
              padding: EdgeInsets.zero,
              backgroundColor: Colors.white,
              foregroundColor: Colors.black,
            ),
            onPressed: ready && !_busy ? _shoot : null,
            child: const Icon(LucideIcons.camera, size: 44),
          ),
        ),
      ),
      cancel,
    ];
  }
}
