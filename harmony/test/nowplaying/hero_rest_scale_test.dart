import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';

void main() {
  test('should_orderHeldPlayingPaused_when_resolvingHeroScale', () {
    final paused = heroRestScale(playing: false);
    final playing = heroRestScale(playing: true);
    final held = heroRestScale(playing: false, held: true);
    expect(paused.title, 0.97);
    expect(paused.artist, 0.98);
    expect(playing.title, 1.03);
    expect(playing.artist, 1.02);
    expect(held.title, 1.05);
    expect(held.artist, 1.04);
  });

  test('should_breatheLess_when_reducedMotion', () {
    final paused = heroRestScale(playing: false, reduced: true);
    final playing = heroRestScale(playing: true, reduced: true);
    expect(paused.title, 0.99);
    expect(playing.title, 1.01);
    expect(heroRestScale(playing: true, held: true, reduced: true).artist, 1.015);
  });
}
