import { describe, it, expect } from '@ohos/hypium';
import {
  joinSecretChunks,
  parseChunkCount,
  splitSecretChunks
} from '../../../../main/ets/yoin/storage/StoragePlugin';

export default function batch2HelpersTest() {
  describe('Batch2HelpersTest', () => {
    it('splitAndJoinSecretChunks_roundTripsPayloadOver1024Bytes', 0, () => {
      const payload = new Uint8Array(2500);
      for (let i = 0; i < payload.byteLength; i++) {
        payload[i] = (i * 37 + 11) % 256;
      }
      const chunks = splitSecretChunks(payload, 1024);
      expect(chunks.length).assertEqual(3);
      expect(chunks[0].byteLength).assertEqual(1024);
      expect(chunks[1].byteLength).assertEqual(1024);
      expect(chunks[2].byteLength).assertEqual(452);

      const joined = joinSecretChunks(chunks);
      expect(joined.byteLength).assertEqual(payload.byteLength);
      expect(joined[0]).assertEqual(payload[0]);
      expect(joined[1024]).assertEqual(payload[1024]);
      expect(joined[2499]).assertEqual(payload[2499]);
    });

    it('parseChunkCount_validatesManifestFormat', 0, () => {
      expect(parseChunkCount('__yoin_chunks_v1__:3')).assertEqual(3);
      expect(parseChunkCount('__yoin_chunks_v1__:1')).assertEqual(1);
      expect(parseChunkCount('__yoin_chunks_v1__:0')).assertNull();
      expect(parseChunkCount('__yoin_chunks_v1__:-2')).assertNull();
      expect(parseChunkCount('__yoin_chunks_v1__:1.5')).assertNull();
      expect(parseChunkCount('plain-secret-value')).assertNull();
    });
  });
}
