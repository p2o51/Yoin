import { describe, it, expect } from '@ohos/hypium';
import { sliceBytes } from '../../../../main/ets/yoin/share/SharePlugin';
import { riseCurvePoints } from '../../../../main/ets/yoin/haptics/HapticsPlugin';

export default function batch1HelpersTest() {
  describe('Batch1HelpersTest', () => {
    it('sliceBytes_returnsOnlySubarrayWindow_when_backingBufferHasEnvelope', 0, () => {
      // Simulate StandardMethodCodec envelope: 5 leading bytes + 8-byte PNG signature + 4 trailing bytes.
      const envelope = new Uint8Array([
        0x07, 0x05, 0x62, 0x79, 0x74,
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x01, 0x02, 0x03, 0x04,
      ]);
      const view = envelope.subarray(5, 13);
      expect(view.byteOffset).assertEqual(5);
      expect(view.byteLength).assertEqual(8);
      // Old bug: writing view.buffer directly would write all 17 envelope bytes starting at 0x07.
      expect(view.buffer.byteLength).assertEqual(17);

      const sliced = new Uint8Array(sliceBytes(view));
      expect(sliced.byteLength).assertEqual(8);
      expect(sliced[0]).assertEqual(0x89);
      expect(sliced[1]).assertEqual(0x50);
      expect(sliced[2]).assertEqual(0x4e);
      expect(sliced[3]).assertEqual(0x47);
      expect(sliced[7]).assertEqual(0x0a);
    });

    it('riseCurvePoints_returnsFourMonotonicPoints_for_validDurations', 0, () => {
      const durations: number[] = [3, 4, 5, 60, 5000];
      for (let i = 0; i < durations.length; i++) {
        const d = durations[i];
        const pts = riseCurvePoints(d);
        expect(pts.length).assertEqual(4);
        expect(pts[0].time).assertEqual(0);
        expect(pts[3].time).assertEqual(d);
        expect(pts[0].time < pts[1].time).assertTrue();
        expect(pts[1].time < pts[2].time).assertTrue();
        expect(pts[2].time < pts[3].time).assertTrue();
        expect(pts[0].intensity).assertEqual(0);
        expect(pts[3].intensity).assertEqual(1);
      }
    });
  });
}
