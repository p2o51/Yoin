import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { fileIo, fileUri } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/share';
const DOMAIN = 0x5907;
const TAG = 'YoinShare';

/** The images handed to the share sheet / the gallery dialog live here; the previous ones are dropped. */
const SHARE_DIR = 'shared';
const SHARE_PREFIX = 'yoin-lyrics-';

/**
 * Serves `yoin/share` for Dart's `ChannelTextShare` (a page's or a song's name and link, Android DetailMenu's
 * ACTION_SEND text) and `ChannelImageShare` (the lyrics card, Android LyricsShareCard's FileProvider share and
 * MediaStore save):
 * - `shareText` {text, title} opens the system share sheet on plain text (Share Kit);
 * - `shareImage` {bytes, name, caption} writes the PNG to the cache and opens the system share sheet (Share Kit);
 * - `saveImage` {bytes, name} writes it to the cache and asks the system's save dialog to put it in the gallery
 *   (photoAccessHelper.showAssetsCreationDialog — the user confirms, no storage permission needed).
 * Each answers true when the sheet / the save went through, false otherwise (declined, failed).
 */
export default class YoinSharePlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinSharePlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method === 'shareText') {
      const text: Any = call.argument('text');
      const title: Any = call.argument('title');
      if (typeof text !== 'string' || (text as string).length === 0) {
        result.error('bad_args', 'shareText needs text', null);
        return;
      }
      this.shareText(text as string, typeof title === 'string' ? title as string : '')
        .then((ok: boolean) => result.success(ok))
        .catch(() => result.success(false));
      return;
    }
    const bytes: Any = call.argument('bytes');
    const name: Any = call.argument('name');
    if (!(bytes instanceof Uint8Array)) {
      result.error('bad_args', `${call.method} needs bytes`, null);
      return;
    }
    const fileName = typeof name === 'string' && (name as string).length > 0 ? name as string : 'Yoin lyrics.png';
    switch (call.method) {
      case 'shareImage': {
        const caption: Any = call.argument('caption');
        this.share(bytes as Uint8Array, typeof caption === 'string' ? caption as string : '')
          .then((ok: boolean) => result.success(ok))
          .catch(() => result.success(false));
        return;
      }
      case 'saveImage':
        this.save(bytes as Uint8Array, fileName)
          .then((ok: boolean) => result.success(ok))
          .catch(() => result.success(false));
        return;
      default:
        result.notImplemented();
    }
  }

  /** Writes [bytes] under cacheDir/shared (older lyric images removed first); returns the path. */
  private writeCache(bytes: Uint8Array): string {
    const dir = `${this.context.cacheDir}/${SHARE_DIR}`;
    if (!fileIo.accessSync(dir)) {
      fileIo.mkdirSync(dir);
    }
    fileIo.listFileSync(dir).forEach((f: string) => {
      if (f.startsWith(SHARE_PREFIX)) {
        try {
          fileIo.unlinkSync(`${dir}/${f}`);
        } catch (e) {
          // A file still held by a share target: leave it.
        }
      }
    });
    const path = `${dir}/${SHARE_PREFIX}${Date.now()}.png`;
    const file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC | fileIo.OpenMode.WRITE_ONLY);
    try {
      fileIo.writeSync(file.fd, bytes.buffer as ArrayBuffer);
    } finally {
      fileIo.closeSync(file);
    }
    return path;
  }

  private async share(bytes: Uint8Array, caption: string): Promise<boolean> {
    try {
      const path = this.writeCache(bytes);
      const data = new systemShare.SharedData({
        utd: uniformTypeDescriptor.UniformDataType.PNG,
        uri: fileUri.getUriFromPath(path),
        title: caption,
        description: caption,
      });
      const controller = new systemShare.ShareController(data);
      await controller.show(this.context, {
        previewMode: systemShare.SharePreviewMode.DETAIL,
        selectionMode: systemShare.SelectionMode.SINGLE,
      });
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'share failed: %{public}d', (e as BusinessError).code ?? -1);
      return false;
    }
  }

  /**
   * A page's or a song's name with its link, as plain text (Android's ACTION_SEND text/plain): the share sheet
   * (Share Kit) shows [title] in its preview, targets get the whole text with the link on its own line.
   */
  private async shareText(text: string, title: string): Promise<boolean> {
    try {
      const data = new systemShare.SharedData({
        utd: uniformTypeDescriptor.UniformDataType.PLAIN_TEXT,
        content: text,
        title: title.length > 0 ? title : undefined,
      });
      const controller = new systemShare.ShareController(data);
      await controller.show(this.context, {
        previewMode: systemShare.SharePreviewMode.DEFAULT,
        selectionMode: systemShare.SelectionMode.SINGLE,
      });
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'shareText failed: %{public}d', (e as BusinessError).code ?? -1);
      return false;
    }
  }

  private async save(bytes: Uint8Array, fileName: string): Promise<boolean> {
    try {
      const path = this.writeCache(bytes);
      const helper = photoAccessHelper.getPhotoAccessHelper(this.context);
      const dot = fileName.lastIndexOf('.');
      const title = (dot > 0 ? fileName.substring(0, dot) : fileName).replace(/[\\/:*?"<>|.]/g, ' ').trim();
      const config: photoAccessHelper.PhotoCreationConfig = {
        title: title.length > 0 ? title : 'Yoin lyrics',
        fileNameExtension: 'png',
        photoType: photoAccessHelper.PhotoType.IMAGE,
        subtype: photoAccessHelper.PhotoSubtype.DEFAULT,
      };
      const targets: string[] = await helper.showAssetsCreationDialog([fileUri.getUriFromPath(path)], [config]);
      if (targets.length === 0) {
        return false;
      }
      const target = fileIo.openSync(targets[0], fileIo.OpenMode.READ_WRITE);
      try {
        fileIo.writeSync(target.fd, bytes.buffer as ArrayBuffer);
      } finally {
        fileIo.closeSync(target);
      }
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'save failed: %{public}d', (e as BusinessError).code ?? -1);
      return false;
    }
  }
}
