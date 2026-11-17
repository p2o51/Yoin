import { http } from '@kit.NetworkKit';
import { image } from '@kit.ImageKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN = 0x5901;
const TAG = 'YoinArtwork';
/** The lock screen's cover size; Dart asks the server for the same (getCoverArt size=512). */
const ARTWORK_PX = 512;
/** Covers kept decoded (current, previous, next in a quick skip). */
const CACHE_SIZE = 3;

class Entry {
  url: string;
  pixelMap: image.PixelMap;

  constructor(url: string, pixelMap: image.PixelMap) {
    this.url = url;
    this.pixelMap = pixelMap;
  }
}

/**
 * Fetches and decodes covers for AVSession itself: the system cannot be relied on to load a LAN or token URL, so
 * the session gets a PixelMap. Keeps the last [CACHE_SIZE] by URL and releases what falls out.
 */
export class ArtworkLoader {
  private cache: Entry[] = [];

  /** The decoded cover for [url], or null when it can't be fetched (the caller keeps the URL string). */
  async load(url: string): Promise<image.PixelMap | null> {
    const hit = this.cache.find((e: Entry) => e.url === url);
    if (hit !== undefined) {
      this.cache = this.cache.filter((e: Entry) => e !== hit);
      this.cache.push(hit);
      return hit.pixelMap;
    }
    const request = http.createHttp();
    try {
      const response = await request.request(url, {
        method: http.RequestMethod.GET,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 10000,
        readTimeout: 15000
      });
      if (response.responseCode !== 200 || !(response.result instanceof ArrayBuffer)) {
        hilog.warn(DOMAIN, TAG, 'cover fetch answered %{public}d', response.responseCode as number);
        return null;
      }
      const source = image.createImageSource(response.result as ArrayBuffer);
      try {
        const options: image.DecodingOptions = { desiredSize: { width: ARTWORK_PX, height: ARTWORK_PX } };
        const pixelMap = await source.createPixelMap(options);
        this.remember(url, pixelMap);
        return pixelMap;
      } finally {
        source.release();
      }
    } catch (e) {
      // Never log the URL (it carries the account's token).
      const err = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'cover fetch failed: %{public}s', `${err.code}`);
      return null;
    } finally {
      request.destroy();
    }
  }

  /** Releases every cached PixelMap. */
  clear(): void {
    const entries = this.cache;
    this.cache = [];
    entries.forEach((e: Entry) => {
      e.pixelMap.release();
    });
  }

  private remember(url: string, pixelMap: image.PixelMap): void {
    this.cache.push(new Entry(url, pixelMap));
    while (this.cache.length > CACHE_SIZE) {
      const old = this.cache.shift();
      if (old !== undefined) {
        old.pixelMap.release();
      }
    }
  }
}
