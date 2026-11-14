import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { formBindingData, formInfo, formProvider } from '@kit.FormKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';

const DOMAIN = 0x5901;
const TAG = 'YoinCards';

/** One Play card item as the card page reads it (image = its formImages key, '' for none). */
export interface PlayCardEntry {
  entity: string;
  id: string;
  title: string;
  subtitle: string;
  image: string;
}

/** What Dart published, as kept on disk (cards/play.json). */
interface PlaySaved {
  rev: number;
  items: PlayCardEntry[];
  glyphLight: string;
  glyphDark: string;
}

/** The incoming item from Dart (yoin/cards publishPlay). */
export interface PlayPublishItem {
  entity: string;
  id: string;
  title: string;
  subtitle: string;
  cover: Uint8Array | null;
}

/** Card layout for a form dimension (Android PlayWidget: strip / stack / shelf). */
export function layoutFor(dimension: number): string {
  switch (dimension) {
    case formInfo.FormDimension.Dimension_1_2:
      return 'strip';
    case formInfo.FormDimension.Dimension_2_4:
    case formInfo.FormDimension.Dimension_4_4:
      return 'shelf';
    default:
      return 'stack';
  }
}

/**
 * The Play card's data, shared by the app process (CardsPlugin, which writes
 * it) and the form extension (EntryFormAbility, which reads it on add). Kept as
 * JSON + image files under filesDir/cards so both processes see the same
 * thing (Preferences caches per process). Only display data lives here: titles,
 * MediaId strings and cover bytes — never a URL or a credential.
 */
export class CardStore {
  private static dir(ctx: common.Context): string {
    return `${ctx.filesDir}/cards`;
  }

  private static ensureDir(ctx: common.Context): string {
    const dir = CardStore.dir(ctx);
    if (!fileIo.accessSync(dir)) {
      fileIo.mkdirSync(dir);
    }
    return dir;
  }

  private static readJson(path: string): Object | null {
    try {
      if (!fileIo.accessSync(path)) {
        return null;
      }
      return JSON.parse(fileIo.readTextSync(path)) as Object;
    } catch (e) {
      return null;
    }
  }

  /** Write-then-rename, so the other process never reads half a file. */
  private static writeJson(path: string, value: Object): void {
    const tmp = `${path}.tmp`;
    const file = fileIo.openSync(tmp, fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC | fileIo.OpenMode.WRITE_ONLY);
    try {
      fileIo.writeSync(file.fd, JSON.stringify(value));
    } finally {
      fileIo.closeSync(file);
    }
    fileIo.renameSync(tmp, path);
  }

  private static writeBytes(path: string, bytes: Uint8Array): void {
    const file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC | fileIo.OpenMode.WRITE_ONLY);
    try {
      fileIo.writeSync(file.fd, bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    } finally {
      fileIo.closeSync(file);
    }
  }

  // ---- Play content ------------------------------------------------------------------------------

  /**
   * Replaces the Play card's content. Image names carry the revision: the card
   * caches images by name, so a new cover needs a new name.
   */
  static savePlay(ctx: common.Context, items: PlayPublishItem[], glyphLight: Uint8Array, glyphDark: Uint8Array): void {
    const dir = CardStore.ensureDir(ctx);
    const rev = Date.now();
    const old = CardStore.readJson(`${dir}/play.json`) as PlaySaved | null;
    const entries: PlayCardEntry[] = [];
    items.forEach((item: PlayPublishItem, i: number) => {
      let image = '';
      if (item.cover !== null && item.cover.byteLength > 0) {
        image = `cover_${rev}_${i}`;
        CardStore.writeBytes(`${dir}/${image}.img`, item.cover);
      }
      entries.push({ entity: item.entity, id: item.id, title: item.title, subtitle: item.subtitle, image: image });
    });
    const saved: PlaySaved = { rev: rev, items: entries, glyphLight: `glyphL_${rev}`, glyphDark: `glyphD_${rev}` };
    CardStore.writeBytes(`${dir}/${saved.glyphLight}.img`, glyphLight);
    CardStore.writeBytes(`${dir}/${saved.glyphDark}.img`, glyphDark);
    CardStore.writeJson(`${dir}/play.json`, saved);
    // Old images go once nothing points at them.
    if (old !== null) {
      const keep = new Set<string>([saved.glyphLight, saved.glyphDark, ...entries.map((e: PlayCardEntry) => e.image)]);
      const stale = [old.glyphLight, old.glyphDark, ...old.items.map((e: PlayCardEntry) => e.image)];
      stale.filter((name: string) => name.length > 0 && !keep.has(name)).forEach((name: string) => {
        try {
          fileIo.unlinkSync(`${dir}/${name}.img`);
        } catch (e) {
          // already gone
        }
      });
    }
    hilog.info(DOMAIN, TAG, 'play card saved: %{public}d items', entries.length);
  }

  /**
   * The binding data for one card, plus the fds it opened (formImages). The
   * caller closes them once the system has the data.
   */
  static binding(ctx: common.Context, layout: string): BindingWithFds {
    const dir = CardStore.dir(ctx);
    const saved = CardStore.readJson(`${dir}/play.json`) as PlaySaved | null;
    const fds: number[] = [];
    const images: Record<string, number> = {};
    const open = (name: string): boolean => {
      if (name.length === 0 || images[name] !== undefined) {
        return name.length > 0;
      }
      try {
        const file = fileIo.openSync(`${dir}/${name}.img`, fileIo.OpenMode.READ_ONLY);
        images[name] = file.fd;
        fds.push(file.fd);
        return true;
      } catch (e) {
        return false;
      }
    };
    const items: PlayCardEntry[] = [];
    if (saved !== null) {
      saved.items.forEach((item: PlayCardEntry) => {
        items.push({
          entity: item.entity,
          id: item.id,
          title: item.title,
          subtitle: item.subtitle,
          image: open(item.image) ? item.image : '',
        });
      });
    }
    const glyphLight = saved !== null && open(saved.glyphLight) ? saved.glyphLight : '';
    const glyphDark = saved !== null && open(saved.glyphDark) ? saved.glyphDark : '';
    const data: Record<string, Object> = {
      'layout': layout,
      'items': items,
      'hasItems': items.length > 0,
      'glyphLight': glyphLight,
      'glyphDark': glyphDark,
      'formImages': images,
    };
    return { binding: formBindingData.createFormBindingData(data), fds: fds };
  }

  static closeFds(fds: number[]): void {
    fds.forEach((fd: number) => {
      try {
        fileIo.closeSync(fd);
      } catch (e) {
        // already closed
      }
    });
  }

  // ---- placed cards ------------------------------------------------------------------------------

  private static forms(ctx: common.Context): Record<string, number> {
    const saved = CardStore.readJson(`${CardStore.dir(ctx)}/forms.json`);
    return saved === null ? {} : saved as Record<string, number>;
  }

  static addForm(ctx: common.Context, formId: string, dimension: number): void {
    const dir = CardStore.ensureDir(ctx);
    const forms = CardStore.forms(ctx);
    forms[formId] = dimension;
    CardStore.writeJson(`${dir}/forms.json`, forms);
  }

  static removeForm(ctx: common.Context, formId: string): void {
    const dir = CardStore.ensureDir(ctx);
    const forms = CardStore.forms(ctx);
    if (forms[formId] === undefined) {
      return;
    }
    const kept: Record<string, number> = {};
    Object.keys(forms).filter((id: string) => id !== formId).forEach((id: string) => {
      kept[id] = forms[id];
    });
    CardStore.writeJson(`${dir}/forms.json`, kept);
  }

  /** Pushes the current data to one placed card. */
  static async refreshOne(ctx: common.Context, formId: string): Promise<void> {
    const dimension = CardStore.forms(ctx)[formId];
    const b = CardStore.binding(ctx, layoutFor(dimension ?? formInfo.FormDimension.Dimension_2_2));
    try {
      await formProvider.updateForm(formId, b.binding);
    } catch (e) {
      const err = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'updateForm %{public}s failed: %{public}d', formId, err.code ?? -1);
      // 16501001: the card was removed while we weren't looking.
      if (err.code === 16501001) {
        CardStore.removeForm(ctx, formId);
      }
    } finally {
      CardStore.closeFds(b.fds);
    }
  }

  /** Pushes the current data to every placed card. */
  static async refreshAll(ctx: common.Context): Promise<void> {
    const ids = Object.keys(CardStore.forms(ctx));
    for (const id of ids) {
      await CardStore.refreshOne(ctx, id);
    }
    hilog.info(DOMAIN, TAG, 'refreshed %{public}d placed card(s)', ids.length);
  }
}

export interface BindingWithFds {
  binding: formBindingData.FormBindingData;
  fds: number[];
}
