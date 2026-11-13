import { Any } from '@ohos/flutter_ohos';

/**
 * One queue entry as Dart's `EngineItem.toMap()` sends it. Optional fields are `undefined` (not null) so they
 * can be copied straight into AVSession's optional metadata fields.
 */
export class QueueItem {
  id: string = '';
  uri: string = '';
  headers: Record<string, string> = {};
  headerCount: number = 0;
  title: string = '';
  artist: string | undefined = undefined;
  album: string | undefined = undefined;
  artworkUri: string | undefined = undefined;
  durationMs: number | undefined = undefined;

  /** Parses one item map; returns null when the required `id`/`uri` are missing so a bad entry cannot crash the queue. */
  static fromMap(value: Any): QueueItem | null {
    if (!(value instanceof Map)) {
      return null;
    }
    const map: Map<Any, Any> = value as Map<Any, Any>;
    const id = readString(map, 'id');
    const uri = readString(map, 'uri');
    if (id === undefined || uri === undefined) {
      return null;
    }
    const item = new QueueItem();
    item.id = id;
    item.uri = uri;
    item.title = readString(map, 'title') ?? '';
    item.artist = readString(map, 'artist');
    item.album = readString(map, 'album');
    item.artworkUri = readString(map, 'artworkUri');
    item.durationMs = readNumber(map, 'durationMs');
    const headers: Any = map.get('headers');
    if (headers instanceof Map) {
      (headers as Map<Any, Any>).forEach((v: Any, k: Any) => {
        if (typeof k === 'string' && typeof v === 'string') {
          item.headers[k as string] = v as string;
          item.headerCount++;
        }
      });
    }
    return item;
  }

  /** Parses the `items` list of `setQueue`, dropping malformed entries. */
  static listFrom(value: Any): QueueItem[] {
    const out: QueueItem[] = [];
    if (value instanceof Array) {
      (value as Array<Any>).forEach((entry: Any) => {
        const item = QueueItem.fromMap(entry);
        if (item !== null) {
          out.push(item);
        }
      });
    }
    return out;
  }
}

export function readString(map: Map<Any, Any>, key: string): string | undefined {
  const v: Any = map.get(key);
  return typeof v === 'string' ? v as string : undefined;
}

export function readNumber(map: Map<Any, Any>, key: string): number | undefined {
  const v: Any = map.get(key);
  return typeof v === 'number' ? v as number : undefined;
}

export function readBool(map: Map<Any, Any>, key: string): boolean | undefined {
  const v: Any = map.get(key);
  return typeof v === 'boolean' ? v as boolean : undefined;
}
