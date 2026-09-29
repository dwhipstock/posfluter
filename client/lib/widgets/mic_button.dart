import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:record/record.dart';

import '../design/tokens.dart';
import '../i18n.dart';

/// A short voice clip, kept in memory only (never written to disk).
typedef VoiceClip = ({Uint8List bytes, String contentType});

/// The microphone behind [MicButton] (tests pass a fake).
abstract class VoiceRecorder {
  /// Starts recording, asking for the microphone first when needed (the
  /// Android runtime prompt); false = no microphone or permission refused.
  Future<bool> start();

  /// Stops and returns the clip; null when nothing usable was recorded.
  Future<VoiceClip?> stop();

  void dispose();
}

/// 16 kHz mono 16-bit PCM streamed into memory, wrapped as WAV. The `record`
/// package streams PCM on Android and on Windows alike, so both the tablet
/// and the Surface send the same clip, and nothing touches the disk.
class PcmVoiceRecorder implements VoiceRecorder {
  static const sampleRate = 16000;
  AudioRecorder? _rec;
  StreamSubscription<Uint8List>? _sub;
  Completer<void>? _done;
  final _pcm = BytesBuilder(copy: false);

  @override
  Future<bool> start() async {
    try {
      final rec = _rec ??= AudioRecorder();
      if (!await rec.hasPermission()) return false;
      _pcm.clear();
      final stream = await rec.startStream(
        const RecordConfig(
          encoder: AudioEncoder.pcm16bits,
          sampleRate: sampleRate,
          numChannels: 1,
        ),
      );
      final done = _done = Completer<void>();
      _sub = stream.listen(
        _pcm.add,
        onDone: () => done.isCompleted ? null : done.complete(),
        onError: (_) => done.isCompleted ? null : done.complete(),
      );
      return true;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<VoiceClip?> stop() async {
    try {
      await _rec?.stop();
      // the last chunks arrive before the stream closes
      await _done?.future.timeout(
        const Duration(milliseconds: 600),
        onTimeout: () {},
      );
    } catch (_) {}
    await _sub?.cancel();
    _sub = null;
    final pcm = _pcm.takeBytes();
    // under a quarter of a second: a slip of the finger, not a request
    if (pcm.length < sampleRate ~/ 2) return null;
    return (bytes: wav(pcm, sampleRate), contentType: 'audio/wav');
  }

  @override
  void dispose() {
    _sub?.cancel();
    _rec?.dispose();
  }

  /// A 44-byte RIFF header in front of mono 16-bit [pcm].
  static Uint8List wav(Uint8List pcm, int rate) {
    final h = ByteData(44);
    void tag(int at, String s) {
      for (var i = 0; i < 4; i++) {
        h.setUint8(at + i, s.codeUnitAt(i));
      }
    }

    tag(0, 'RIFF');
    h.setUint32(4, 36 + pcm.length, Endian.little);
    tag(8, 'WAVE');
    tag(12, 'fmt ');
    h.setUint32(16, 16, Endian.little);
    h.setUint16(20, 1, Endian.little); // PCM
    h.setUint16(22, 1, Endian.little); // mono
    h.setUint32(24, rate, Endian.little);
    h.setUint32(28, rate * 2, Endian.little);
    h.setUint16(32, 2, Endian.little);
    h.setUint16(34, 16, Endian.little);
    tag(36, 'data');
    h.setUint32(40, pcm.length, Endian.little);
    return (BytesBuilder(copy: false)
          ..add(h.buffer.asUint8List())
          ..add(pcm))
        .takeBytes();
  }
}

enum MicState { idle, recording, sending }

/// Press to talk, release to send; or tap to start and tap again to send.
/// Stops by itself after [maxDuration]. [onClip] gets the clip (the button
/// shows a spinner until it completes).
class MicButton extends StatefulWidget {
  final bool enabled;
  final Future<void> Function(VoiceClip clip) onClip;

  /// null = the device microphone ([PcmVoiceRecorder]).
  final VoiceRecorder? recorder;
  static const maxDuration = Duration(seconds: 30);

  /// A press held at least this long is push-to-talk: release sends.
  static const holdThreshold = Duration(milliseconds: 400);
  const MicButton({
    super.key,
    required this.onClip,
    this.enabled = true,
    this.recorder,
  });

  @override
  State<MicButton> createState() => _MicButtonState();
}

class _MicButtonState extends State<MicButton> {
  late final VoiceRecorder _rec = widget.recorder ?? PcmVoiceRecorder();
  MicState _state = MicState.idle;
  // the press has lasted [MicButton.holdThreshold]: push-to-talk
  bool _held = false;
  Timer? _hold;
  // this press started the recording (its release may be push-to-talk)
  bool _pressStarted = false;
  Timer? _limit;
  Future<bool>? _starting;

  @override
  void dispose() {
    _limit?.cancel();
    _hold?.cancel();
    if (_state == MicState.recording) _rec.stop();
    _rec.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    setState(() => _state = MicState.recording);
    final ok = await (_starting = _rec.start());
    if (!mounted) return;
    if (!ok) {
      setState(() => _state = MicState.idle);
      ScaffoldMessenger.maybeOf(
        context,
      )?.showSnackBar(SnackBar(content: Text(L.of(context).micUnavailable)));
      return;
    }
    _limit = Timer(MicButton.maxDuration, _stop);
  }

  Future<void> _stop() async {
    if (_state != MicState.recording) return;
    _limit?.cancel();
    setState(() => _state = MicState.sending);
    // released before the microphone was even open: nothing to send
    if (await _starting != true) return setState(() => _state = MicState.idle);
    final clip = await _rec.stop();
    try {
      if (clip != null) await widget.onClip(clip);
    } finally {
      if (mounted) setState(() => _state = MicState.idle);
    }
  }

  void _down(PointerDownEvent _) {
    if (!widget.enabled) return;
    if (_state == MicState.idle) {
      _pressStarted = true;
      _held = false;
      _hold?.cancel();
      _hold = Timer(MicButton.holdThreshold, () => _held = true);
      _start();
    } else if (_state == MicState.recording) {
      _pressStarted = false;
      _stop(); // the second tap
    }
  }

  void _up(PointerUpEvent _) {
    _hold?.cancel();
    if (_pressStarted && _held && _state == MicState.recording) {
      _stop(); // push-to-talk: release sends
    }
    _pressStarted = false;
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final recording = _state == MicState.recording;
    final on = widget.enabled || _state != MicState.idle;
    return Tooltip(
      message: recording ? l.micListening : l.micHint,
      child: Listener(
        onPointerDown: _down,
        onPointerUp: _up,
        child: Semantics(
          button: true,
          label: recording ? l.micListening : l.micHint,
          child: AnimatedContainer(
            key: Key('mic-${_state.name}'),
            duration: const Duration(milliseconds: 150),
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: recording ? T.destructive : Colors.transparent,
              border: Border.all(
                color: recording
                    ? T.destructive
                    : on
                    ? T.primary
                    : T.border,
                width: 2,
              ),
            ),
            alignment: Alignment.center,
            child: _state == MicState.sending
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Icon(
                    recording ? LucideIcons.square : LucideIcons.mic,
                    size: 22,
                    color: recording
                        ? T.onDestructive
                        : on
                        ? T.primary
                        : T.textMuted,
                  ),
          ),
        ),
      ),
    );
  }
}
