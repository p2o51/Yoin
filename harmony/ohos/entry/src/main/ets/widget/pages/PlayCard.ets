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

// MaterialShapes approximations in a 100 x 100 box (lib/home/widgets/backdrop_shapes.dart).
const BUN = 'M50 4 C78 4 94 16 94 32 C94 42 88 47 86 50 C88 53 94 58 94 68 C94 84 78 96 50 96 ' +
  'C22 96 6 84 6 68 C6 58 12 53 14 50 C12 47 6 42 6 32 C6 16 22 4 50 4 Z';
const GHOSTISH = 'M50 5 C75 5 92 22 92 47 L92 84 C92 93 84 97 77 91 C72 87 67 87 62 91 C57 96 43 96 38 91 ' +
  'C33 87 28 87 23 91 C16 97 8 93 8 84 L8 47 C8 22 25 5 50 5 Z';

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

  private play(item: CardItem): void {
    postCardAction(this, {
      action: 'router',
      abilityName: 'EntryAbility',
      params: { 'yoinCard': 'play', 'entity': item.entity, 'id': item.id }
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
        }, (item: CardItem) => item.id)
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
