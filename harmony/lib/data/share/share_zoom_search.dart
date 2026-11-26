/// How far a Story zooms its content (port of :shared ShareZoomSearch.kt,
/// Android `ZoomToFit`): the content is laid out at the frame's width / zoom
/// (wider when zooming out, so lines wrap less; narrower when zooming in) and
/// drawn back at `zoom` — the largest zoom up to [maxZoom] whose height fits
/// the frame. Fitting is monotonic, so it is searched one measurement at a
/// time: [maxZoom] first; when that doesn't fit, 1 (a story that would grow);
/// then [steps] halvings of the bracket that fits. It settles on the largest
/// zoom known to fit ([minZoom] when nothing does). Zooms are 32-bit floats,
/// as Kotlin's.
///
/// Flutter can measure synchronously, so the Story runs it in one go
/// ([YoinShareCards.storyZoom]).
library;

import 'share_card_models.dart' show f32;

enum _Phase { max, one, bisect }

class ShareZoomSearch {
  ShareZoomSearch(double maxZoom) : _maxZoom = f32(maxZoom), _zoom = f32(maxZoom);

  /// Furthest a story zooms out: below this the type is too small to read on a phone.
  static final double minZoom = f32(0.4);

  /// Furthest a story zooms IN on short content (a card on the backdrop; a poster never grows).
  static final double maxZoomLimit = f32(1.4);

  /// Halvings of the bracket before it settles.
  static const int steps = 8;

  /// Rounding: a height a fraction of a pixel over the frame still fits.
  static const double fitSlackPx = 0.5;

  final double _maxZoom;
  double _zoom;
  bool _settled = false;
  _Phase _phase = _Phase.max;
  double _low = 0;
  double _high = 0;
  int _steps = 0;

  /// The zoom to measure next (or, once [settled], the answer).
  double get zoom => _zoom;

  bool get settled => _settled;

  /// One step of the search for the zoom just tried.
  void report(bool fits) {
    if (_settled) return;
    switch (_phase) {
      case _Phase.max:
        if (fits) {
          _settle(_maxZoom);
        } else if (_maxZoom > 1) {
          _phase = _Phase.one;
          _zoom = 1;
        } else {
          _bisect(minZoom, _maxZoom);
        }
      case _Phase.one:
        if (fits) {
          _bisect(1, _maxZoom);
        } else {
          _bisect(minZoom, 1);
        }
      case _Phase.bisect:
        if (fits) {
          _low = _zoom;
        } else {
          _high = _zoom;
        }
        _steps++;
        if (_steps >= steps) {
          _settle(_low);
        } else {
          _zoom = f32((_low + _high) / 2);
        }
    }
  }

  void _bisect(double from, double to) {
    _phase = _Phase.bisect;
    _low = from;
    _high = to;
    _steps = 0;
    _zoom = f32((_low + _high) / 2);
  }

  void _settle(double value) {
    _zoom = value;
    _settled = true;
  }
}
