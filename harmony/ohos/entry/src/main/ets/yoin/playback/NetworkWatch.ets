import { connection } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN = 0x5901;
const TAG = 'YoinNetwork';
/** Wi-Fi ⇄ cellular hand-overs report a burst of networks; one flush per burst is enough. */
const BURST_MS = 3000;

/**
 * Tells the process when a network becomes usable (Android YoinApplication's ConnectivityManager onAvailable), so
 * plays queued while offline are sent without waiting for the next app resume. Needs GET_NETWORK_INFO.
 */
export class NetworkWatch {
  private conn: connection.NetConnection | null = null;
  private lastFiredAt: number = 0;

  start(onAvailable: () => void): void {
    if (this.conn !== null) {
      return;
    }
    try {
      const conn = connection.createNetConnection();
      // Registered once, then fires for the network already up and for every one that comes up later.
      conn.on('netAvailable', (_: connection.NetHandle) => {
        const now = Date.now();
        if (now - this.lastFiredAt < BURST_MS) {
          return;
        }
        this.lastFiredAt = now;
        onAvailable();
      });
      conn.register((e: BusinessError) => {
        if (e !== undefined && e !== null && e.code !== 0 && e.code !== undefined) {
          hilog.warn(DOMAIN, TAG, 'network watch not registered: %{public}d', e.code);
        }
      });
      this.conn = conn;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'network watch failed: %{public}s', JSON.stringify(e));
    }
  }

  stop(): void {
    const conn = this.conn;
    this.conn = null;
    if (conn === null) {
      return;
    }
    try {
      conn.unregister((_: BusinessError) => {
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'network watch stop failed: %{public}s', JSON.stringify(e));
    }
  }
}
