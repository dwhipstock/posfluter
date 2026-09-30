import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:camera_platform_interface/camera_platform_interface.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/camera_capture.dart';

/// The Windows tablet's camera page (lib/widgets/camera_capture.dart), driven
/// through a fake camera platform: which camera opens, shoot / retake / use,
/// switch, cancel, no camera, access refused, and that the camera is always
/// released.
final _png = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
);

class _FakeCamera extends CameraPlatform {
  _FakeCamera(this.cameras);
  List<CameraDescription> cameras;
  CameraException? createError;
  final created = <String>[];
  final disposed = <int>[];
  var shots = 0;
  final _events = StreamController<CameraEvent>.broadcast();

  int get open => created.length - disposed.length;

  @override
  Future<List<CameraDescription>> availableCameras() async => cameras;

  @override
  Future<int> createCameraWithSettings(
    CameraDescription description,
    MediaSettings? settings,
  ) async {
    if (createError != null) throw createError!;
    created.add(description.name);
    return created.length;
  }

  @override
  Future<void> initializeCamera(
    int cameraId, {
    ImageFormatGroup imageFormatGroup = ImageFormatGroup.unknown,
  }) async {
    _events.add(
      CameraInitializedEvent(
        cameraId,
        1920,
        1080,
        ExposureMode.auto,
        false,
        FocusMode.auto,
        false,
      ),
    );
  }

  @override
  Stream<CameraInitializedEvent> onCameraInitialized(int cameraId) => _events
      .stream
      .where((e) => e.cameraId == cameraId)
      .cast<CameraInitializedEvent>();

  @override
  Stream<CameraErrorEvent> onCameraError(int cameraId) =>
      StreamController<CameraErrorEvent>().stream;

  @override
  Stream<DeviceOrientationChangedEvent> onDeviceOrientationChanged() =>
      StreamController<DeviceOrientationChangedEvent>().stream;

  @override
  Widget buildPreview(int cameraId) =>
      Container(key: ValueKey('preview-$cameraId'), color: Colors.grey);

  @override
  Future<XFile> takePicture(int cameraId) async {
    shots++;
    return XFile.fromData(Uint8List.fromList(_png), mimeType: 'image/png');
  }

  @override
  Future<void> dispose(int cameraId) async => disposed.add(cameraId);
}

class _FakePicker extends CameraDelegatingImagePickerPlatform {}

const _front = CameraDescription(
  name: 'Microsoft Camera Front <\\\\?\\usb#1>',
  lensDirection: CameraLensDirection.front,
  sensorOrientation: 0,
);
const _rear = CameraDescription(
  name: 'Microsoft Camera Rear <\\\\?\\usb#2>',
  lensDirection: CameraLensDirection.front, // Windows says "front" for all
  sensorOrientation: 0,
);

/// A button that takes a photo through the delegate, as image_picker would.
class _Harness extends StatefulWidget {
  const _Harness(this.delegate);
  final CameraCaptureDelegate delegate;
  @override
  State<_Harness> createState() => _HarnessState();
}

class _HarnessState extends State<_Harness> {
  XFile? result;
  bool done = false;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: TextButton(
      onPressed: () async {
        final f = await widget.delegate.takePhoto();
        setState(() {
          result = f;
          done = true;
        });
      },
      child: const Text('open'),
    ),
  );
}

void main() {
  late _FakeCamera camera;
  final l = L.current;

  setUp(() {
    camera = _FakeCamera([_front, _rear]);
    CameraPlatform.instance = camera;
  });

  Future<_HarnessState> openCamera(WidgetTester tester) async {
    final key = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: key,
        home: _Harness(CameraCaptureDelegate(key)),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return tester.state<_HarnessState>(
      find.byType(_Harness, skipOffstage: false),
    );
  }

  test('picks the rear camera by name, else the first', () {
    expect(pickCamera([_front, _rear], CameraDevice.rear), 1);
    expect(pickCamera([_front, _rear], CameraDevice.front), 0);
    const a = CameraDescription(
      name: 'USB Webcam',
      lensDirection: CameraLensDirection.front,
      sensorOrientation: 0,
    );
    expect(pickCamera([a], CameraDevice.rear), 0);
    const back = CameraDescription(
      name: 'cam 2',
      lensDirection: CameraLensDirection.back,
      sensorOrientation: 0,
    );
    expect(pickCamera([a, back], CameraDevice.rear), 1);
  });

  test('the delegate is registered on Windows only', () {
    final picker = _FakePicker();
    ImagePickerPlatform.instance = picker;
    final key = GlobalKey<NavigatorState>();
    expect(
      CameraCaptureDelegate.register(key, platform: TargetPlatform.android),
      isFalse,
    );
    expect(picker.supportsImageSource(ImageSource.camera), isFalse);
    expect(
      CameraCaptureDelegate.register(key, platform: TargetPlatform.windows),
      isTrue,
    );
    expect(picker.cameraDelegate, isA<CameraCaptureDelegate>());
    expect(picker.supportsImageSource(ImageSource.camera), isTrue);
  });

  testWidgets('shoot, retake, use: returns the photo, frees the camera', (
    tester,
  ) async {
    final harness = await openCamera(tester);
    expect(camera.created, [_rear.name]);
    expect(find.byKey(const ValueKey('preview-1')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('camera-shutter')));
    await tester.pumpAndSettle();
    expect(find.text(l.cameraUsePhoto), findsOneWidget);
    await tester.tap(find.text(l.cameraRetake));
    await tester.pumpAndSettle();
    expect(find.text(l.cameraUsePhoto), findsNothing);

    await tester.tap(find.byKey(const ValueKey('camera-shutter')));
    await tester.pumpAndSettle();
    await tester.tap(find.text(l.cameraUsePhoto));
    await tester.pumpAndSettle();
    expect(camera.shots, 2);
    expect(harness.done, isTrue);
    expect(await harness.result!.readAsBytes(), _png);
    expect(harness.result!.name, 'photo.jpg');
    expect(camera.open, 0);
  });

  testWidgets('switch camera, then cancel: null, camera freed', (tester) async {
    final harness = await openCamera(tester);
    await tester.tap(find.byTooltip(l.cameraSwitch));
    await tester.pumpAndSettle();
    expect(camera.created, [_rear.name, _front.name]);
    expect(camera.open, 1);
    await tester.tap(find.text(l.cancel));
    await tester.pumpAndSettle();
    expect(harness.done, isTrue);
    expect(harness.result, isNull);
    expect(camera.open, 0);
  });

  testWidgets('released while the app is away, reopened on return', (
    tester,
  ) async {
    await openCamera(tester);
    expect(camera.open, 1);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump(); // a spinner while away: no pumpAndSettle
    await tester.pump();
    expect(camera.open, 0);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(camera.open, 1);
    expect(find.byKey(const ValueKey('preview-2')), findsOneWidget);
    await tester.tap(find.text(l.cancel));
    await tester.pumpAndSettle();
    expect(camera.open, 0);
  });

  testWidgets('no camera: says so, and no switch button', (tester) async {
    camera.cameras = [];
    final harness = await openCamera(tester);
    expect(find.text(l.cameraNone), findsOneWidget);
    expect(find.byKey(const ValueKey('camera-shutter')), findsNothing);
    await tester.tap(find.text(l.cancel));
    await tester.pumpAndSettle();
    expect(harness.result, isNull);
  });

  testWidgets('access refused: says how to allow it, gallery fallback', (
    tester,
  ) async {
    camera.createError = CameraException('CameraAccessDenied', 'denied');
    final picked = XFile.fromData(Uint8List.fromList(_png), path: 'menu.png');
    XFile? result;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await Navigator.of(context).push<XFile>(
                MaterialPageRoute(
                  builder: (_) =>
                      CameraCapturePage(pickFromGallery: () async => picked),
                ),
              );
            },
            child: const Text('open'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text(l.cameraDenied), findsOneWidget);
    await tester.tap(find.text(l.aiChooseFromGallery));
    await tester.pumpAndSettle();
    expect(result, same(picked));
  });
}
