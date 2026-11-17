import { Any } from '@ohos/flutter_ohos';

/**
 * Dart's stream cache is a loopback server in the Flutter engine's isolate: it goes when that engine detaches
 * (the ability destroyed while the process plays on). Every detach starts a new epoch; a [QueueItem.cacheUri]
 * handed over in an earlier epoch points at a server that is gone, so that item streams from [QueueItem.uri].
 */
export class StreamCacheEpoch {
  private static value: number = 0;

  static current(): number {
    return StreamCacheEpoch.value;
  }

  /** The Flutter engine (and the cache server with it) went away. */
  static end(): void {
    StreamCacheEpoch.value++;
  }
}

/**
 * One queue entry as Dart's `EngineItem.toMap()` sends it. Optional fields are `undefined` (not null) so they
 * can be copied straight into AVSession's optional metadata fields.
 */
export class QueueItem {
  id: string = '';
  uri: string = '';
  headers: Record<string, string> = {};
  headerCount: number = 0;
  /** A downloaded copy (`file://`) played in place of [uri] while the file is there; [uri] streams otherwise. */
  localUri: string | undefined = undefined;
  /**
   * Dart's stream cache (`http://127.0.0.1:<port>/…`): played in place of [uri] so the song is stored as it plays
   * (Android CacheDataSource). [localUri] still comes first; [uri] plays once this failed ([cacheBypassed]) or its
   * server is gone ([cacheEpoch]).
   */
  cacheUri: string | undefined = undefined;
  cacheEpoch: number = 0;
  cacheBypassed: boolean = false;
  title: string = '';
  artist: string | undefined = undefined;
  album: string | undefined = undefined;
  artworkUri: string | undefined = undefined;
  durationMs: number | undefined = undefined;
  /** Dart's per-entry id (the same song queued twice is two entries). */
  entryId: string | undefined = undefined;
  /** Opaque to native (the full Dart Track as JSON); echoed by getSnapshot. */
  extra: string | undefined = undefined;
  /**
   * Volume leveling (ReplayGain), worked out by Dart: the player's volume for this entry, 0–1. The engine sets it
   * when the entry starts (or is swapped in gapless), so it changes with the song.
   */
  gain: number = 1;
  /**
   * What the stream ([uri] / [cacheUri]) and the downloaded [localUri] deliver (Dart's `StreamDelivery` maps:
   * the file, or a transcode). Opaque here: echoed by getSnapshot so a re-created UI still knows what arrives.
   */
  delivery: Map<Any, Any> | null = null;
  fileDelivery: Map<Any, Any> | null = null;

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
    item.localUri = readString(map, 'localUri');
    item.cacheUri = readString(map, 'cacheUri');
    item.cacheEpoch = StreamCacheEpoch.current();
    item.title = readString(map, 'title') ?? '';
    item.artist = readString(map, 'artist');
    item.album = readString(map, 'album');
    item.artworkUri = readString(map, 'artworkUri');
    item.durationMs = readNumber(map, 'durationMs');
    item.entryId = readString(map, 'entryId');
    item.extra = readString(map, 'extra');
    item.gain = clampGain(readNumber(map, 'gain'));
    item.delivery = readMap(map, 'delivery');
    item.fileDelivery = readMap(map, 'fileDelivery');
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

  /** Parses the `items` list of `setQueue` / `insert`, dropping malformed entries. */
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

  /** The address the player opens when it streams: the stream cache's while it is there, else [uri]. */
  streamUri(): string {
    const cache = this.cacheUri;
    if (cache !== undefined && !this.cacheBypassed && this.cacheEpoch === StreamCacheEpoch.current()) {
      return cache;
    }
    return this.uri;
  }

  /** Whether the player opens [cacheUri] now. */
  viaCache(): boolean {
    return this.streamUri() !== this.uri;
  }

  /** What `getSnapshot` echoes (no stream URL or headers: they carry credentials). */
  toMap(): Map<string, Any> {
    const map: Map<string, Any> = new Map();
    map.set('id', this.id);
    map.set('title', this.title);
    map.set('artist', this.artist ?? null);
    map.set('album', this.album ?? null);
    map.set('artworkUri', this.artworkUri ?? null);
    map.set('durationMs', this.durationMs ?? null);
    map.set('entryId', this.entryId ?? null);
    map.set('extra', this.extra ?? null);
    map.set('gain', this.gain);
    if (this.delivery !== null) {
      map.set('delivery', this.delivery);
    }
    if (this.fileDelivery !== null) {
      map.set('fileDelivery', this.fileDelivery);
    }
    return map;
  }
}

/** A gain as AVPlayer takes it: 0–1; anything missing or malformed is 1 (the file as it is). */
export function clampGain(value: number | undefined): number {
  if (value === undefined || !Number.isFinite(value)) {
    return 1;
  }
  return Math.min(1, Math.max(0, value));
}

export function readString(map: Map<Any, Any>, key: string): string | undefined {
  const v: Any = map.get(key);
  return typeof v === 'string' ? v as string : undefined;
}

export function readNumber(map: Map<Any, Any>, key: string): number | undefined {
  const v: Any = map.get(key);
  return typeof v === 'number' ? v as number : undefined;
}

export function readMap(map: Map<Any, Any>, key: string): Map<Any, Any> | null {
  const v: Any = map.get(key);
  return v instanceof Map ? v as Map<Any, Any> : null;
}

export function readBool(map: Map<Any, Any>, key: string): boolean | undefined {
  const v: Any = map.get(key);
  return typeof v === 'boolean' ? v as boolean : undefined;
}
