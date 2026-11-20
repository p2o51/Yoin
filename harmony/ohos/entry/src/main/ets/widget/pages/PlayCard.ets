// The Play service card (Android widget/PlayWidget.kt): pick up recent listening.
// Data comes from the app (CardStore binding): layout = strip (1*2) | stack (2*2) | shelf (2*4),
// items (newest first), the play glyph rendered by Dart from Yoin Symbols for each theme.
// Colours are app resources with dark variants, so the card follows the system theme.
// Covers keep their rounded-rect shape (4); MaterialShapes (Bun / Ghostish) are only backdrops.

interface CardItem {
  entity: string;
  id: string;
  title: string;
  subtitle: string;
  image: string;
}

// MaterialShapes.Bun / Ghostish fitted into a 100 x 100 box (Android WidgetArt.path), generated from
// lib/ui/shapes (the graphics-shapes port) so they are the exact outlines Home draws.
const BUN = 'M79.60 50.00 C79.96 50.12 80.33 50.23 80.69 50.35 C83.74 51.31 86.57 52.83 89.05 54.85 ' +
  'C95.42 60.02 98.88 67.97 98.35 76.16 C98.35 76.17 98.35 76.19 98.35 76.21 ' +
  'C97.48 89.59 86.37 100.00 72.96 100.00 C57.65 100.00 42.35 100.00 27.04 100.00 ' +
  'C13.63 100.00 2.52 89.59 1.65 76.21 C1.65 76.19 1.65 76.17 1.65 76.16 ' +
  'C1.12 67.97 4.58 60.02 10.95 54.85 C13.43 52.83 16.26 51.31 19.31 50.35 ' +
  'C19.67 50.23 20.04 50.12 20.40 50.00 C20.04 49.88 19.67 49.77 19.31 49.65 ' +
  'C16.26 48.69 13.43 47.17 10.95 45.15 C4.58 39.98 1.12 32.03 1.65 23.84 ' +
  'C1.65 23.83 1.65 23.81 1.65 23.79 C2.52 10.41 13.63 0.00 27.04 0.00 ' +
  'C42.35 0.00 57.65 0.00 72.96 0.00 C86.37 0.00 97.48 10.41 98.35 23.79 ' +
  'C98.35 23.81 98.35 23.83 98.35 23.84 C98.88 32.03 95.42 39.98 89.05 45.15 ' +
  'C86.57 47.17 83.74 48.69 80.69 49.65 C80.33 49.77 79.96 49.88 79.60 50.00 Z';
const GHOSTISH = 'M50.00 0.00 C76.32 0.00 97.66 21.34 97.66 47.66 C97.66 57.11 97.66 66.56 97.66 76.00 ' +
  'C97.66 90.69 81.91 100.00 69.05 92.92 C66.86 91.71 64.67 90.50 62.47 89.30 ' +
  'C58.98 87.37 55.06 86.37 51.07 86.37 C50.36 86.37 49.64 86.37 48.93 86.37 ' +
  'C44.94 86.37 41.02 87.37 37.53 89.30 C35.33 90.50 33.14 91.71 30.95 92.92 ' +
  'C18.09 100.00 2.34 90.69 2.34 76.00 C2.34 66.56 2.34 57.11 2.34 47.66 ' +
  'C2.34 21.34 23.68 0.00 50.00 0.00 Z';

const storage = new LocalStorage();

@Entry(storage)
@Component
struct PlayCard {
  @LocalStorageProp('layout') layout: string = 'stack';
  @LocalStorageProp('items') items: CardItem[] = [];
  @LocalStorageProp('hasItems') hasItems: boolean = false;
  @LocalStorageProp('glyphLight') glyphLight: string = '';
  @LocalStorageProp('glyphDark') glyphDark: string = '';

  private open(item: CardItem): void {
    postCardAction(this, {
      action: 'router',
      abilityName: 'EntryAbility',
      params: { 'yoinCard': 'open', 'entity': item.entity, 'id': item.id }
    });
  }

  /**
   * Plays from the home screen without bringing Yoin forward (Android WidgetPlayActivity): a 'call' starts
   * EntryAbility in the background if needed (CardsPlugin CARD_PLAY_METHOD). If the play can't start, the app
   * comes forward on the album / playlist page.
   */
  private play(item: CardItem): void {
    postCardAction(this, {
      action: 'call',
      abilityName: 'EntryAbility',
      params: { 'method': 'yoinCardPlay', 'entity': item.entity, 'id': item.id }
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
  Cover(item: CardItem, radius: number) {
    if (item.image.length > 0) {
      Image('memory://' + item.image)
        .width('100%')
        .height('100%')
        .objectFit(ImageFit.Cover)
        .borderRadius(radius)
    } else {
      Column()
        .width('100%')
        .height('100%')
        .borderRadius(radius)
        .backgroundColor($r('app.color.card_placeholder'))
    }
  }

  /** Square play key (WidgetPlayButton): primary fill, the glyph at 44 %. */
  @Builder
  PlayKey(item: CardItem, radius: number) {
    Stack() {
      if (this.glyphLight.length > 0) {
        Image('memory://' + this.glyphLight)
          .width('44%')
          .height('44%')
          .opacity($r('app.float.card_light_only'))
      }
      if (this.glyphDark.length > 0) {
        Image('memory://' + this.glyphDark)
          .width('44%')
          .height('44%')
          .opacity($r('app.float.card_dark_only'))
      }
    }
    .width('100%')
    .height('100%')
    .borderRadius(radius)
    .backgroundColor($r('app.color.card_primary'))
    .onClick(() => this.play(item))
  }

  @Builder
  Backdrop(entity: string, fill: ResourceColor) {
    Shape() {
      Path()
        .width('100px')
        .height('100px')
        .commands(entity === 'playlist' ? GHOSTISH : BUN)
        .fill(fill)
        // Path strokes black by default: a backdrop is a fill only.
        .stroke(Color.Transparent)
        .strokeWidth(0)
    }
    .viewPort({ x: 0, y: 0, width: '100px', height: '100px' })
    .width('100%')
    .height('100%')
  }

  /** 1*2: a Bun lead holding the cover, the container running on to the play key. */
  @Builder
  Strip(item: CardItem) {
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
        this.Backdrop('album', $r('app.color.card_surface'))
      }
      .height('100%')
      .aspectRatio(1)

      Row() {
        Stack() {
          Stack() {
            this.Cover(item, 4)
          }
          .width('62%')
          .height('62%')
        }
        .height('100%')
        .aspectRatio(1)

        Blank()

        Stack() {
          this.PlayKey(item, 20)
        }
        .height('70%')
        .aspectRatio(1)
        .margin({ right: '6%' })
      }
      .width('100%')
      .height('100%')
    }
    .width('100%')
    .height('100%')
    .onClick(() => this.open(item))
  }

  @Builder
  StackItem(item: CardItem, fill: ResourceColor) {
    Stack() {
      this.Backdrop(item.entity, fill)
      Stack() {
        this.Cover(item, 4)
      }
      .width('60%')
      .height('60%')
    }
    .width('100%')
    .height('100%')
  }

  /** 2*2: up to three backdrops stacked, the newest in front, play in the cleared corner. */
  @Builder
  StackLayout() {
    Stack({ alignContent: Alignment.TopStart }) {
      if (this.items.length > 2) {
        Stack() {
          this.StackItem(this.items[2], $r('app.color.card_backdrop_3'))
        }
        .width('42%')
        .height('42%')
        .position({ x: '52%', y: '8%' })
        .onClick(() => this.open(this.items[2]))
      }
      if (this.items.length > 1) {
        Stack() {
          this.StackItem(this.items[1], $r('app.color.card_backdrop_2'))
        }
        .width('46%')
        .height('46%')
        .position({ x: '6%', y: '5%' })
        .onClick(() => this.open(this.items[1]))
      }
      Stack() {
        this.StackItem(this.items[0], $r('app.color.card_backdrop_1'))
      }
      .width('62%')
      .height('62%')
      .position({ x: '7%', y: '33%' })
      .onClick(() => this.open(this.items[0]))

      Stack() {
        this.PlayKey(this.items[0], 18)
      }
      .width('27%')
      .height('27%')
      .position({ x: '67%', y: '67%' })
    }
    .width('100%')
    .height('100%')
    .borderRadius(36)
    .backgroundColor($r('app.color.card_surface'))
  }

  /** 2*4: hero (latest + play) over a shelf of the next ones; connected segments, seam radius 16. */
  @Builder
  Shelf() {
    Column() {
      Row() {
        Stack() {
          this.Cover(this.items[0], 4)
        }
        .height('100%')
        .aspectRatio(1)

        Column() {
          Text(this.items[0].title)
            .fontSize(18)
            .fontWeight(FontWeight.Medium)
            .fontColor($r('app.color.card_on_surface'))
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          if (this.items[0].subtitle.length > 0) {
            Text(this.items[0].subtitle)
              .fontSize(13)
              .fontColor($r('app.color.card_on_surface_variant'))
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        .padding({ left: 12, right: 12 })

        Stack() {
          this.PlayKey(this.items[0], 20)
        }
        .height('100%')
        .aspectRatio(1)
      }
      .width('100%')
      .height('54%')
      .padding(16)
      .backgroundColor($r('app.color.card_surface'))
      .borderRadius({ topLeft: 28, topRight: 28, bottomLeft: 16, bottomRight: 16 })
      .onClick(() => this.open(this.items[0]))

      Row() {
        ForEach(this.items.slice(1, 5), (item: CardItem) => {
          Stack() {
            this.Cover(item, 4)
          }
          .height('100%')
          .aspectRatio(1)
          .onClick(() => this.open(item))
        }, (item: CardItem) => `${item.id}|${item.image}|${item.title}|${item.subtitle}`)
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .justifyContent(this.items.length > 2 ? FlexAlign.SpaceBetween : FlexAlign.Start)
      .backgroundColor($r('app.color.card_shelf'))
      .borderRadius({ topLeft: 16, topRight: 16, bottomLeft: 28, bottomRight: 28 })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  Empty() {
    Column() {
      Text($r('app.string.card_play_empty'))
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
      } else if (this.layout === 'shelf') {
        this.Shelf()
      } else {
        this.StackLayout()
      }
    }
    .width('100%')
    .height('100%')
  }
}
