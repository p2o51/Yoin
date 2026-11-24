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
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
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
/** A file the user picked (Import from backup), copied here; Dart deletes it once read. */
const PICK_PREFIX = 'yoin-picked-';

/**
 * Serves `yoin/share` for Dart's `ChannelTextShare` (a page's or a song's name and link, Android DetailMenu's
 * ACTION_SEND text), `ChannelFileShare` (Help and about's Export log) and `ChannelImageShare` (the lyrics card,
 * Android LyricsShareCard's FileProvider share and MediaStore save):
 * - `shareText` {text, title} opens the system share sheet on plain text (Share Kit);
 * - `shareFile` {path, kind, title} opens it on a file the app wrote in its cache or files folder (Share Kit);
 * - `shareImage` {bytes, name, caption} writes the PNG to the cache and opens the system share sheet (Share Kit);
 * - `saveImage` {bytes, name} writes it to the cache and asks the system's save dialog to put it in the gallery
 *   (photoAccessHelper.showAssetsCreationDialog — the user confirms, no storage permission needed).
 * - `saveFile` {path, name} asks where to keep a file the app wrote (the system's document picker, Android's
 *   SAF CreateDocument: Settings › Export all data) and copies it there;
 * - `pickFile` {} lets the user pick one file (the document picker, Android's OpenDocument: Import from backup) and
 *   copies it into the cache, answering its path, or null when nothing was picked.
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
    if (call.method === 'shareFile') {
      const path: Any = call.argument('path');
      const kind: Any = call.argument('kind');
      const title: Any = call.argument('title');
      if (typeof path !== 'string' || !this.isSandboxed(path as string)) {
        result.error('bad_args', 'shareFile needs a path in the app sandbox', null);
        return;
      }
      this.shareFile(path as string, typeof kind === 'string' ? kind as string : 'text',
        typeof title === 'string' ? title as string : '')
        .then((ok: boolean) => result.success(ok))
        .catch(() => result.success(false));
      return;
    }
    if (call.method === 'saveFile') {
      const path: Any = call.argument('path');
      const name: Any = call.argument('name');
      if (typeof path !== 'string' || !this.isSandboxed(path as string) || typeof name !== 'string') {
        result.error('bad_args', 'saveFile needs a path in the app sandbox and a name', null);
        return;
      }
      this.saveFile(path as string, name as string)
        .then((ok: boolean) => result.success(ok))
        .catch(() => result.success(false));
      return;
    }
    if (call.method === 'pickFile') {
      this.pickFile()
        .then((path: string | null) => result.success(path))
        .catch(() => result.success(null));
      return;
    }
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
      fileIo.writeSync(file.fd, sliceBytes(bytes));
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

  /** Whether [path] is a file in this app's cache or files folder (nothing else is handed to the sheet). */
  private isSandboxed(path: string): boolean {
    // No step up out of the folder ("…/cache/../…" or a trailing "/.."), and a file, not a folder.
    if (path.split('/').some((segment: string) => segment === '..' || segment === '.')) {
      return false;
    }
    const inside = (dir: string): boolean => path.startsWith(`${dir}/`);
    // Dart reads its folders from the application context (`yoin/storage`), this plugin holds the ability's.
    const app = this.context.getApplicationContext();
    if (!(inside(this.context.cacheDir) || inside(this.context.filesDir) || inside(app.cacheDir) ||
      inside(app.filesDir))) {
      return false;
    }
    try {
      return fileIo.accessSync(path) && fileIo.statSync(path).isFile();
    } catch (e) {
      return false;
    }
  }

  /**
   * A file the app wrote (Help and about's Export log, Android's ACTION_SEND of a FileProvider uri): the share
   * sheet (Share Kit) on its uri, as plain text for `kind` 'text'; [title] heads the preview.
   */
  private async shareFile(path: string, kind: string, title: string): Promise<boolean> {
    try {
      const data = new systemShare.SharedData({
        utd: kind === 'text' ? uniformTypeDescriptor.UniformDataType.PLAIN_TEXT
          : uniformTypeDescriptor.UniformDataType.FILE,
        uri: fileUri.getUriFromPath(path),
        title: title.length > 0 ? title : undefined,
      });
      const controller = new systemShare.ShareController(data);
      await controller.show(this.context, {
        previewMode: systemShare.SharePreviewMode.DEFAULT,
        selectionMode: systemShare.SelectionMode.SINGLE,
      });
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'shareFile failed: %{public}d', (e as BusinessError).code ?? -1);
      return false;
    }
  }

  /** The document picker's save dialog for [path], suggested as [name]; the file is copied where the user chose. */
  private async saveFile(path: string, name: string): Promise<boolean> {
    try {
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [name];
      const uris: string[] = await new picker.DocumentViewPicker(this.context).save(options);
      if (uris.length === 0) {
        return false;
      }
      const source = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      const target = fileIo.openSync(uris[0], fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
      try {
        fileIo.copyFileSync(source.fd, target.fd);
      } finally {
        fileIo.closeSync(source);
        fileIo.closeSync(target);
      }
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'saveFile failed: %{public}d', (e as BusinessError).code ?? -1);
      return false;
    }
  }

  /** The document picker's select dialog (one file); the pick is copied to cacheDir/shared and its path answered. */
  private async pickFile(): Promise<string | null> {
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      const uris: string[] = await new picker.DocumentViewPicker(this.context).select(options);
      if (uris.length === 0) {
        return null;
      }
      const dir = `${this.context.cacheDir}/${SHARE_DIR}`;
      if (!fileIo.accessSync(dir)) {
        fileIo.mkdirSync(dir);
      }
      const path = `${dir}/${PICK_PREFIX}${Date.now()}`;
      const source = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
      const target = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC | fileIo.OpenMode.WRITE_ONLY);
      try {
        fileIo.copyFileSync(source.fd, target.fd);
      } finally {
        fileIo.closeSync(source);
        fileIo.closeSync(target);
      }
      return path;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'pickFile failed: %{public}d', (e as BusinessError).code ?? -1);
      return null;
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
        fileIo.writeSync(target.fd, sliceBytes(bytes));
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

/**
 * Extracts only the view's slice from a [Uint8Array]. StandardMethodCodec often returns a subarray view over the
 * shared method-call envelope buffer, so writing `bytes.buffer` directly leaks surrounding envelope bytes into the PNG.
 */
export function sliceBytes(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}
