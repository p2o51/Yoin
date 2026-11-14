// The Memory service card (Android widget/MemoryWidget.kt; Figma: the "Play + Memory" families).
// Data comes from the app (CardStore memoryBinding): layout = strip (1*2) | anchor (2*2) | timeline (2*4),
// items most recently heard first, each already worded by Dart (score "8.5", last heard "3 Days Ago").
// Score and time since you last heard it lead every size; album + artist join on the strip.
// Covers keep their rounded-rect shape (4); the Clover / Cookie shapes are only backdrops and badges.

interface MemoryItem {
  session: string;
  album: string;
  artist: string;
  score: string;
  lastHeard: string;
  image: string;
}

// MaterialShapes approximations in a 100 x 100 box.
const CLOVER = 'M50 6 C62 6 70 14 72 26 C84 28 94 36 94 50 C94 64 84 72 72 74 C70 86 62 94 50 94 ' +
  'C38 94 30 86 28 74 C16 72 6 64 6 50 C6 36 16 28 28 26 C30 14 38 6 50 6 Z';

/** Cookie12: twelve soft scallops (Android WidgetArt.cookieBadge). */
function cookiePath(lobes: number): string {
  const step = Math.PI * 2 / lobes;
  const inner = 44;
  const outer = 53;
  const at = (r: number, a: number): string =>
    `${(50 + r * Math.cos(a)).toFixed(2)} ${(50 + r * Math.sin(a)).toFixed(2)}`;
  let d = `M${at(inner, -step / 2)}`;
  for (let i = 0; i < lobes; i++) {
    d += ` Q${at(outer, i * step)} ${at(inner, i * step + step / 2)}`;
  }
  return d + ' Z';
}

const COOKIE = cookiePath(12);

// Every card page shares one module scope: its LocalStorage needs its own name.
const memoryStorage = new LocalStorage();

@Entry(memoryStorage)
@Component
struct MemoryCard {
  @LocalStorageProp('layout') layout: string = 'anchor';
  @LocalStorageProp('items') items: MemoryItem[] = [];
  @LocalStorageProp('hasItems') hasItems: boolean = false;

  private open(item: MemoryItem): void {
    postCardAction(this, {
      action: 'router',
      abilityName: 'EntryAbility',
      params: { 'yoinCard': 'memory', 'id': item.session }
    });
  }

  private openApp(): void {
    postCardAction(this, {
      action: 'router',
      abilityName: 'EntryAbility',
      params: { 'yoinCard': 'app' }
    });
  }

  @Builder
  Cover(item: MemoryItem) {
    if (item.image.length > 0) {
      Image('memory://' + item.image)
        .width('100%')
        .height('100%')
        .objectFit(ImageFit.Cover)
        .borderRadius(4)
    } else {
      Column()
        .width('100%')
        .height('100%')
        .borderRadius(4)
        .backgroundColor($r('app.color.card_placeholder'))
    }
  }

  @Builder
  ShapeFill(commands: string, fill: ResourceColor) {
    Shape() {
      Path()
        .width('100px')
        .height('100px')
        .commands(commands)
        .fill(fill)
        .stroke(Color.Transparent)
        .strokeWidth(0)
    }
    .viewPort({ x: 0, y: 0, width: '100px', height: '100px' })
    .width('100%')
    .height('100%')
  }

  /** 1*2: a Clover lead holding the cover, the container running on; score + time, then album + artist. */
  @Builder
  Strip(item: MemoryItem) {
    Stack({ alignContent: Alignment.Start }) {
      Row() {
        Column().height('100%').aspectRatio(0.5)
        Column()
          .layoutWeight(1)
          .height('92%')
          .backgroundColor($r('app.color.card_surface'))
          .borderRadius({ topRight: 24, bottomRight: 24 })
      }
      .width('100%')
      .height('100%')

      Stack() {
        this.ShapeFill(CLOVER, $r('app.color.card_surface'))
      }
      .height('100%')
      .aspectRatio(1)

      Row() {
        Stack() {
          Stack() {
            this.Cover(item)
          }
          .width('60%')
          .height('60%')
        }
        .height('100%')
        .aspectRatio(1)

        Column() {
          if (item.score.length > 0) {
            Text(item.score)
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.card_primary'))
              .maxLines(1)
            if (item.lastHeard.length > 0) {
              Text(item.lastHeard)
                .fontSize(12)
                .fontWeight(FontWeight.Medium)
                .fontColor($r('app.color.card_primary'))
                .maxLines(1)
            }
          } else {
            Text(item.lastHeard.length > 0 ? item.lastHeard : item.album)
              .fontSize(16)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.card_primary'))
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .margin({ left: 8 })

        Column() {
          Text(item.album)
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor($r('app.color.card_on_surface'))
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          if (item.artist.length > 0) {
            Text(item.artist)
              .fontSize(12)
              .fontColor($r('app.color.card_on_surface_variant'))
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        .margin({ left: 16, right: 14 })
      }
      .width('100%')
      .height('100%')
    }
    .width('100%')
    .height('100%')
    .onClick(() => this.open(item))
  }

  /** 2*2: a big cover bottom-left, the Cookie12 score badge top-right biting its corner. */
  @Builder
  Anchor(item: MemoryItem) {
    Stack({ alignContent: Alignment.TopStart }) {
      Stack() {
        this.Cover(item)
      }
      .width('65%')
      .height('65%')
      .position({ x: '4%', y: '31%' })

      Stack() {
        this.ShapeFill(COOKIE, $r('app.color.card_backdrop_1'))
        Column() {
          if (item.score.length > 0) {
            Text(item.score)
              .fontSize(30)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.card_badge_ink'))
              .maxLines(1)
            if (item.lastHeard.length > 0) {
              Text(item.lastHeard)
                .fontSize(10)
                .fontWeight(FontWeight.Medium)
                .fontColor($r('app.color.card_badge_ink'))
                .maxLines(1)
            }
          } else {
            Text(item.lastHeard.length > 0 ? item.lastHeard : item.album)
              .fontSize(13)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.card_badge_ink'))
              .textAlign(TextAlign.Center)
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .width('70%')
        .justifyContent(FlexAlign.Center)
      }
      .width('60%')
      .height('60%')
      .position({ x: '38%', y: '2%' })
    }
    .width('100%')
    .height('100%')
    .onClick(() => this.open(item))
  }

  /** One memory on the time axis: cover with its score chip, the tick, how long ago. */
  @Builder
  TimelineItem(item: MemoryItem) {
    Column() {
      Stack({ alignContent: Alignment.TopEnd }) {
        Stack() {
          this.Cover(item)
        }
        .width(64)
        .height(64)
        .margin({ top: 12, right: 14 })

        if (item.score.length > 0) {
          Stack() {
            this.ShapeFill(COOKIE, $r('app.color.card_backdrop_1'))
            Text(item.score)
              .fontSize(12)
              .fontWeight(FontWeight.Bold)
              .fontColor($r('app.color.card_badge_ink'))
              .maxLines(1)
          }
          .width(34)
          .height(34)
        }
      }
      .width(78)
      .height(76)

      Column()
        .width(8)
        .height(8)
        .borderRadius(4)
        .backgroundColor($r('app.color.card_on_surface'))
        .margin({ top: 8, bottom: 8 })

      Text(item.lastHeard)
        .fontSize(12)
        .fontWeight(FontWeight.Medium)
        .fontColor($r('app.color.card_on_surface_variant'))
        .maxLines(2)
        .width(78)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .alignItems(HorizontalAlign.Start)
    .onClick(() => this.open(item))
  }

  /** 2*4: memories on a time axis, newest first, every cover the same size. */
  @Builder
  Timeline() {
    Stack({ alignContent: Alignment.TopStart }) {
      // The axis, through the ticks (cover 76 + 8 above the dot's centre).
      Column()
        .width('100%')
        .height(2)
        .backgroundColor($r('app.color.card_on_surface_variant'))
        .opacity(0.4)
        .margin({ top: 87 })

      Row() {
        ForEach(this.items.slice(0, 4), (item: MemoryItem) => {
          this.TimelineItem(item)
        }, (item: MemoryItem) => item.session)
      }
      .width('100%')
      .justifyContent(this.items.length > 2 ? FlexAlign.SpaceBetween : FlexAlign.SpaceAround)
      .alignItems(VerticalAlign.Top)
    }
    .width('100%')
    .height('100%')
    .padding(16)
    .borderRadius(32)
    .backgroundColor($r('app.color.card_surface'))
  }

  @Builder
  Empty() {
    Column() {
      Text($r('app.string.card_memory_empty'))
        .fontSize(14)
        .fontColor($r('app.color.card_on_surface_variant'))
        .textAlign(TextAlign.Center)
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .padding(16)
    .borderRadius(32)
    .backgroundColor($r('app.color.card_surface'))
    .onClick(() => this.openApp())
  }

  build() {
    Stack() {
      if (!this.hasItems || this.items.length === 0) {
        this.Empty()
      } else if (this.layout === 'strip') {
        this.Strip(this.items[0])
      } else if (this.layout === 'timeline') {
        this.Timeline()
      } else {
        this.Anchor(this.items[0])
      }
    }
    .width('100%')
    .height('100%')
  }
}
