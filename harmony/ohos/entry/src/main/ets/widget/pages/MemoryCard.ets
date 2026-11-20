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

// MaterialShapes.Clover4Leaf / Cookie12Sided fitted into a 100 x 100 box (Android WidgetArt.path and
// cookieBadge), generated from lib/ui/shapes (the graphics-shapes port).
const CLOVER = 'M50.00 9.81 C50.48 9.44 50.97 9.07 51.45 8.70 C62.76 0.00 78.77 1.04 88.87 11.13 ' +
  'C98.96 21.23 100.00 37.24 91.30 48.55 C90.93 49.03 90.56 49.52 90.19 50.00 ' +
  'C90.56 50.48 90.93 50.97 91.30 51.45 C100.00 62.76 98.96 78.77 88.87 88.87 ' +
  'C78.77 98.96 62.76 100.00 51.45 91.30 C50.97 90.93 50.48 90.56 50.00 90.19 ' +
  'C49.52 90.56 49.03 90.93 48.55 91.30 C37.24 100.00 21.23 98.96 11.13 88.87 ' +
  'C1.04 78.77 0.00 62.76 8.70 51.45 C9.07 50.97 9.44 50.48 9.81 50.00 ' +
  'C9.44 49.52 9.07 49.03 8.70 48.55 C0.00 37.24 1.04 21.23 11.13 11.13 ' +
  'C21.23 1.04 37.24 0.00 48.55 8.70 C49.03 9.07 49.52 9.44 50.00 9.81 Z';
const COOKIE = 'M50.00 0.52 C52.56 0.52 55.12 1.55 57.00 3.61 C59.49 6.34 63.35 7.38 66.88 6.25 ' +
  'C72.19 4.56 77.81 7.81 79.00 13.25 C79.79 16.87 82.62 19.69 86.23 20.48 ' +
  'C91.68 21.67 94.92 27.29 93.23 32.61 C92.11 36.13 93.14 39.99 95.88 42.48 ' +
  'C100.00 46.24 100.00 52.73 95.88 56.49 C93.14 58.98 92.11 62.83 93.23 66.36 ' +
  'C94.92 71.67 91.68 77.29 86.23 78.49 C82.62 79.28 79.79 82.10 79.00 85.71 ' +
  'C77.81 91.16 72.19 94.41 66.88 92.72 C63.35 91.59 59.49 92.63 57.00 95.36 ' +
  'C53.24 99.48 46.76 99.48 43.00 95.36 C40.51 92.63 36.65 91.59 33.12 92.72 ' +
  'C27.81 94.41 22.19 91.16 21.00 85.71 C20.21 82.10 17.38 79.28 13.77 78.49 ' +
  'C8.32 77.29 5.08 71.67 6.77 66.36 C7.89 62.83 6.86 58.98 4.12 56.49 ' +
  'C0.00 52.73 0.00 46.24 4.12 42.48 C6.86 39.99 7.89 36.13 6.77 32.61 ' +
  'C5.08 27.29 8.32 21.67 13.77 20.48 C17.38 19.69 20.21 16.87 21.00 13.25 ' +
  'C22.19 7.81 27.81 4.56 33.12 6.25 C36.65 7.38 40.51 6.34 43.00 3.61 ' +
  'C44.88 1.55 47.44 0.52 50.00 0.52 Z';

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
        }, (item: MemoryItem) => `${item.session}|${item.image}|${item.score}|${item.lastHeard}`)
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
