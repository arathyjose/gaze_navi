import 'package:flutter/foundation.dart';

enum BlinkState { open, closing, closed, opening }

class BlinkDetector {
  // ── Thresholds ───────────────────────────────────────────────────────────
  static const double _closeThreshold  = 0.35; // was 0.5  — too high, missed real blinks
  static const double _openThreshold   = 0.55; // was 0.6  — slightly more forgiving re-open
  static const int    _minClosedMs     = 40;   // was 20   — filters camera noise
  static const int    _maxQuickBlinkMs = 400;  // was 500  — tightened so long-hold isn't confused
  static const int    _doubleBlinkWindowMs = 1200; // was 1800 — 1.2 s is a natural double-blink
  static const int    _longBlinkMinMs  = 600;
  static const int    _longBlinkMaxMs  = 2000;

  // ── State ────────────────────────────────────────────────────────────────
  bool       _longBlinkFired  = false;
  BlinkState _state           = BlinkState.open;
  DateTime?  _closeStartTime;
  DateTime?  _lastBlinkTime;
  DateTime?  _firstCloseTime;
  int        _blinkCount      = 0;

  // ── Callbacks ────────────────────────────────────────────────────────────
  void Function()? onSingleBlink;
  void Function()? onDoubleBlink;   // ← double-blink fires this → TAP on screen

  // ── EMA smoothing ────────────────────────────────────────────────────────
  double _smoothProb = 1.0;
  double _rawProb    = 1.0;
  // Lower alpha = more smoothing (less jitter). Was 0.65 — too reactive to noise.
  static const double _probAlpha = 0.45;

  // ── Stats ────────────────────────────────────────────────────────────────
  int    _totalBlinks       = 0;
  int    _totalDoubleBlinks = 0;
  int    _totalLongBlinks   = 0;
  String _lastTrigger       = '';

  // ─────────────────────────────────────────────────────────────────────────

  void update(double? leftEyeOpen, double? rightEyeOpen) {
    // Combine both eyes; fall back to whichever is available
    double prob;
    if (leftEyeOpen != null && rightEyeOpen != null) {
      prob = (leftEyeOpen + rightEyeOpen) / 2.0;
    } else {
      prob = leftEyeOpen ?? rightEyeOpen ?? 1.0;
    }

    _rawProb     = prob;
    _smoothProb += (prob - _smoothProb) * _probAlpha;

    // Use the MORE closed of raw vs smooth to detect closing,
    // and the MORE open of the two to detect opening.
    // This prevents smoothing from masking a real fast blink.
    final closeProb = _rawProb < _smoothProb ? _rawProb : _smoothProb;
    final openProb  = _rawProb > _smoothProb ? _rawProb : _smoothProb;

    final now = DateTime.now();

    switch (_state) {
      case BlinkState.open:
      // ── Detect eye closure ───────────────────────────────────────────
        if (closeProb < _closeThreshold) {
          _state          = BlinkState.closing;
          _closeStartTime = now;
          _longBlinkFired = false;
          if (_blinkCount == 0) _firstCloseTime = now;
          debugPrint('BLINK: closing (prob=${closeProb.toStringAsFixed(2)})');
        }

        // ── Check if the double-blink window expired (single blink result) ─
        if (_blinkCount == 1 && _firstCloseTime != null) {
          final sinceFirst = now.difference(_firstCloseTime!).inMilliseconds;
          if (sinceFirst > _doubleBlinkWindowMs) {
            _blinkCount     = 0;
            _firstCloseTime = null;
            _lastTrigger    = 'SINGLE BLINK';
            debugPrint('BLINK: window expired → SINGLE');
            onSingleBlink?.call();
          }
        }
        break;

      case BlinkState.closing:
        if (openProb > _openThreshold) {
          final elapsed = now.difference(_closeStartTime!).inMilliseconds;
          if (elapsed >= _minClosedMs && elapsed <= _maxQuickBlinkMs) {
            _state          = BlinkState.open;
            _closeStartTime = null;
            _onQuickBlink(now);
          } else {
            // Too short (noise) or too long (held) — ignore
            _state          = BlinkState.open;
            _closeStartTime = null;
          }
        } else if (now.difference(_closeStartTime!).inMilliseconds >= _minClosedMs) {
          _state = BlinkState.closed; // confirmed closed
        }
        break;

      case BlinkState.closed:
        final elapsed = now.difference(_closeStartTime!).inMilliseconds;

        if (openProb > _openThreshold) {
          // Eyes just reopened
          if (elapsed <= _maxQuickBlinkMs) {
            _state          = BlinkState.open;
            _closeStartTime = null;
            _onQuickBlink(now);
          } else if (_longBlinkFired) {
            // Long-blink already fired while eyes were shut — clean up
            _state          = BlinkState.open;
            _closeStartTime = null;
            _blinkCount     = 0;
            _firstCloseTime = null;
          } else {
            // Held beyond quick-blink window but no long-blink — discard
            _state          = BlinkState.open;
            _closeStartTime = null;
          }
        } else {
          // Still closed — check for long-blink (maps to doubleBlink callback)
          if (!_longBlinkFired &&
              elapsed >= _longBlinkMinMs &&
              elapsed <= _longBlinkMaxMs) {
            _longBlinkFired = true;
            _totalLongBlinks++;
            _lastTrigger = 'LONG BLINK';
            debugPrint('BLINK: LONG BLINK (${elapsed}ms) → TAP');
            onDoubleBlink?.call(); // long-blink also fires the tap action
          }
          // Safety: if held way too long, reset so detector doesn't stay stuck
          if (elapsed > _longBlinkMaxMs + 500) {
            _state          = BlinkState.open;
            _closeStartTime = null;
            _longBlinkFired = false;
          }
        }
        break;

      case BlinkState.opening:
        _state = BlinkState.open;
        break;
    }
  }

  // ── Quick-blink logic (double-blink detection) ───────────────────────────
  void _onQuickBlink(DateTime now) {
    _totalBlinks++;
    debugPrint('BLINK: quick blink #$_totalBlinks (count in window: ${_blinkCount + 1})');

    if (_blinkCount == 0) {
      // First blink of a potential double
      _blinkCount     = 1;
      _lastBlinkTime  = now;
      // _firstCloseTime already set when we entered closing state
    } else {
      // Second blink — check it's within the window
      final sinceFirst = _firstCloseTime != null
          ? now.difference(_firstCloseTime!).inMilliseconds
          : now.difference(_lastBlinkTime!).inMilliseconds;

      if (sinceFirst <= _doubleBlinkWindowMs) {
        // ✅ DOUBLE BLINK — tap at cursor position
        _blinkCount     = 0;
        _lastBlinkTime  = null;
        _firstCloseTime = null;
        _totalDoubleBlinks++;
        _lastTrigger = 'DOUBLE BLINK';
        debugPrint('BLINK: ✅ DOUBLE BLINK #$_totalDoubleBlinks (${sinceFirst}ms apart)');
        onDoubleBlink?.call();
      } else {
        // Second blink came too late — treat as new first blink
        _lastTrigger    = 'SINGLE BLINK';
        onSingleBlink?.call();
        _blinkCount     = 1;
        _lastBlinkTime  = now;
        _firstCloseTime = _closeStartTime ?? now;
      }
    }
  }

  // ── Getters ──────────────────────────────────────────────────────────────
  double get eyeOpenProbability => _smoothProb;
  double get rawProbability     => _rawProb;

  String get stateLabel {
    if (_state == BlinkState.closed && _closeStartTime != null) {
      final elapsed = DateTime.now().difference(_closeStartTime!).inMilliseconds;
      if (elapsed >= _longBlinkMinMs && !_longBlinkFired) return 'LONG_HOLD';
    }
    return _state.name.toUpperCase();
  }

  int    get totalBlinks       => _totalBlinks;
  int    get totalDoubleBlinks => _totalDoubleBlinks;
  int    get totalLongBlinks   => _totalLongBlinks;
  String get lastTrigger       => _lastTrigger;

  void reset() {
    _state          = BlinkState.open;
    _closeStartTime = null;
    _lastBlinkTime  = null;
    _firstCloseTime = null;
    _blinkCount     = 0;
    _smoothProb     = 1.0;
    _rawProb        = 1.0;
    _totalBlinks        = 0;
    _totalDoubleBlinks  = 0;
    _totalLongBlinks    = 0;
    _longBlinkFired = false;
    _lastTrigger    = '';
  }
}