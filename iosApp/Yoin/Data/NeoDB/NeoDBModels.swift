import Foundation

/// NeoDB OpenAPI 0.14.0.5 subset — Android `data/integration/neodb/NeoDBModels.kt`. Yoin handles album
/// Marks and Reviews only. Every field decodes leniently (absent → nil / default) so an older instance's
/// shape never fails a read.
struct NeoDBShelfItem: Decodable, Equatable, Sendable {
    var uuid: String?
    var shelfType: String?
    var visibility: Int
    var item: NeoDBItem?
    var ratingGrade: Int?
    var commentText: String?
    var tags: [String]

    init(uuid: String? = nil, shelfType: String? = nil, visibility: Int = 0, item: NeoDBItem? = nil,
         ratingGrade: Int? = nil, commentText: String? = nil, tags: [String] = []) {
        self.uuid = uuid
        self.shelfType = shelfType
        self.visibility = visibility
        self.item = item
        self.ratingGrade = ratingGrade
        self.commentText = commentText
        self.tags = tags
    }

    private enum CodingKeys: String, CodingKey {
        case uuid, visibility, item, tags
        case shelfType = "shelf_type"
        case ratingGrade = "rating_grade"
        case commentText = "comment_text"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decodeIfPresent(String.self, forKey: .uuid)
        shelfType = try c.decodeIfPresent(String.self, forKey: .shelfType)
        visibility = try c.decodeIfPresent(Int.self, forKey: .visibility) ?? 0
        item = try c.decodeIfPresent(NeoDBItem.self, forKey: .item)
        ratingGrade = try c.decodeIfPresent(Int.self, forKey: .ratingGrade)
        commentText = try c.decodeIfPresent(String.self, forKey: .commentText)
        tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
    }
}

struct NeoDBItem: Decodable, Equatable, Sendable {
    var uuid: String?
    var displayTitle: String?
    var category: String?
    // Catalog fields (search / item detail). Absent on shelf payloads.
    var title: String?
    var url: String?
    var artist: [String]
    var rating: Double?
    var ratingCount: Int?
    /// Five buckets, low to high, in percent.
    var ratingDistribution: [Int]?
    var externalResources: [ExternalResource]?

    struct ExternalResource: Decodable, Equatable, Sendable {
        var url: String

        init(url: String = "") { self.url = url }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            url = (try? c.decodeIfPresent(String.self, forKey: .url)) ?? ""
        }

        private enum CodingKeys: String, CodingKey { case url }
    }

    init(uuid: String? = nil, displayTitle: String? = nil, category: String? = nil, title: String? = nil,
         url: String? = nil, artist: [String] = [], rating: Double? = nil, ratingCount: Int? = nil,
         ratingDistribution: [Int]? = nil, externalResources: [ExternalResource]? = nil) {
        self.uuid = uuid
        self.displayTitle = displayTitle
        self.category = category
        self.title = title
        self.url = url
        self.artist = artist
        self.rating = rating
        self.ratingCount = ratingCount
        self.ratingDistribution = ratingDistribution
        self.externalResources = externalResources
    }

    private enum CodingKeys: String, CodingKey {
        case uuid, category, title, url, artist, rating
        case displayTitle = "display_title"
        case ratingCount = "rating_count"
        case ratingDistribution = "rating_distribution"
        case externalResources = "external_resources"
    }

    /// Lenient: a catalog field of an unexpected shape reads as absent, never fails the item.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try c.decodeIfPresent(String.self, forKey: .uuid)
        displayTitle = try c.decodeIfPresent(String.self, forKey: .displayTitle)
        category = try? c.decodeIfPresent(String.self, forKey: .category)
        title = try? c.decodeIfPresent(String.self, forKey: .title)
        url = try? c.decodeIfPresent(String.self, forKey: .url)
        artist = ((try? c.decodeIfPresent([String].self, forKey: .artist)) ?? nil) ?? []
        rating = (try? c.decodeIfPresent(Double.self, forKey: .rating)) ?? nil
        ratingCount = (try? c.decodeIfPresent(Int.self, forKey: .ratingCount)) ?? nil
        ratingDistribution = (try? c.decodeIfPresent([Int].self, forKey: .ratingDistribution)) ?? nil
        externalResources = (try? c.decodeIfPresent([ExternalResource].self, forKey: .externalResources)) ?? nil
    }
}

/// `GET /api/item/{uuid}/posts/` — everyone's posts on an item (short comments, reviews, marks). The posts
/// are Mastodon-shaped; NeoDB's own meaning sits in `ext_neodb.relatedWith` (a Comment's text, a Rating's
/// score, a Review's title and body), read loosely and interpreted by NeoDBVoicesParsing (Android
/// `NeoDbPostPage`).
struct NeoDBPostPage: Decodable, Equatable, Sendable {
    var data: [NeoDBPost]
    var pages: Int
    var count: Int

    init(data: [NeoDBPost] = [], pages: Int = 0, count: Int = 0) {
        self.data = data
        self.pages = pages
        self.count = count
    }

    private enum CodingKeys: String, CodingKey { case data, pages, count }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        data = try c.decodeIfPresent([NeoDBPost].self, forKey: .data) ?? []
        pages = (try? c.decodeIfPresent(Int.self, forKey: .pages)) ?? 0
        count = (try? c.decodeIfPresent(Int.self, forKey: .count)) ?? 0
    }
}

struct NeoDBPost: Decodable, Equatable, Sendable {
    var id: String
    var url: String?
    var createdAt: String?
    var sensitive: Bool
    var spoilerText: String?
    var account: NeoDBPostAccount?
    var relatedWith: [[String: NeoDBLooseValue]]

    init(id: String = "", url: String? = nil, createdAt: String? = nil, sensitive: Bool = false,
         spoilerText: String? = nil, account: NeoDBPostAccount? = nil, relatedWith: [[String: NeoDBLooseValue]] = []) {
        self.id = id
        self.url = url
        self.createdAt = createdAt
        self.sensitive = sensitive
        self.spoilerText = spoilerText
        self.account = account
        self.relatedWith = relatedWith
    }

    private enum CodingKeys: String, CodingKey {
        case id, url, sensitive, account
        case createdAt = "created_at"
        case spoilerText = "spoiler_text"
        case ext = "ext_neodb"
    }

    private enum ExtKeys: String, CodingKey { case relatedWith }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        url = (try? c.decodeIfPresent(String.self, forKey: .url)) ?? nil
        createdAt = (try? c.decodeIfPresent(String.self, forKey: .createdAt)) ?? nil
        sensitive = (try? c.decodeIfPresent(Bool.self, forKey: .sensitive)) ?? false
        spoilerText = (try? c.decodeIfPresent(String.self, forKey: .spoilerText)) ?? nil
        account = (try? c.decodeIfPresent(NeoDBPostAccount.self, forKey: .account)) ?? nil
        if let ext = try? c.nestedContainer(keyedBy: ExtKeys.self, forKey: .ext) {
            relatedWith = (try? ext.decodeIfPresent([[String: NeoDBLooseValue]].self, forKey: .relatedWith)) ?? nil ?? []
        } else {
            relatedWith = []
        }
    }
}

struct NeoDBPostAccount: Decodable, Equatable, Sendable {
    var acct: String?
    var username: String?
    var displayName: String?
    var avatar: String?
    var url: String?

    init(acct: String? = nil, username: String? = nil, displayName: String? = nil, avatar: String? = nil,
         url: String? = nil) {
        self.acct = acct
        self.username = username
        self.displayName = displayName
        self.avatar = avatar
        self.url = url
    }

    private enum CodingKeys: String, CodingKey {
        case acct, username, avatar, url
        case displayName = "display_name"
    }
}

/// One value inside `relatedWith`, read as kotlinx's `jsonPrimitive` reads it: a string or a number gives
/// its text and, when it parses, a number; an object, an array or null give neither.
struct NeoDBLooseValue: Decodable, Equatable, Sendable {
    var string: String?
    var double: Double?

    init(string: String? = nil, double: Double? = nil) {
        self.string = string
        self.double = double
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self.init()
        } else if let b = try? c.decode(Bool.self) {
            self.init(string: b ? "true" : "false")
        } else if let n = try? c.decode(Double.self) {
            let text = n == n.rounded() && abs(n) < 1e15 ? String(Int64(n)) : String(n)
            self.init(string: text, double: n)
        } else if let s = try? c.decode(String.self) {
            self.init(string: s, double: Double(s.trimmingCharacters(in: .whitespaces)))
        } else {
            self.init()
        }
    }
}

/// POST body of `/api/me/shelf/item/{uuid}` (MarkInSchema). `shelf_type` and `visibility` are required
/// and always sent (Android once lost `visibility: 0` to encodeDefaults=false and got a 422). The rest
/// follows Android's encoding: nil, empty tags and `post_to_fediverse: false` stay out of the JSON;
/// `comment_text: ""` is sent on purpose (it clears the short comment).
struct NeoDBShelfMarkRequest: Encodable, Equatable, Sendable {
    var shelfType: String
    var visibility: Int
    var ratingGrade: Int?
    var commentText: String?
    var tags: [String] = []
    var postToFediverse = false
    var createdTime: String?

    private enum CodingKeys: String, CodingKey {
        case visibility, tags
        case shelfType = "shelf_type"
        case ratingGrade = "rating_grade"
        case commentText = "comment_text"
        case postToFediverse = "post_to_fediverse"
        case createdTime = "created_time"
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(shelfType, forKey: .shelfType)
        try c.encode(visibility, forKey: .visibility)
        try c.encodeIfPresent(ratingGrade, forKey: .ratingGrade)
        try c.encodeIfPresent(commentText, forKey: .commentText)
        if !tags.isEmpty { try c.encode(tags, forKey: .tags) }
        if postToFediverse { try c.encode(true, forKey: .postToFediverse) }
        try c.encodeIfPresent(createdTime, forKey: .createdTime)
    }
}

/// POST body of `/api/me/review/item/{item_uuid}` (ReviewInSchema); the item is in the path.
struct NeoDBReviewRequest: Encodable, Equatable, Sendable {
    var visibility: Int
    var title: String
    var body: String
    var postToFediverse = false

    private enum CodingKeys: String, CodingKey {
        case visibility, title, body
        case postToFediverse = "post_to_fediverse"
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(visibility, forKey: .visibility)
        try c.encode(title, forKey: .title)
        try c.encode(body, forKey: .body)
        if postToFediverse { try c.encode(true, forKey: .postToFediverse) }
    }
}

struct NeoDBReviewResponse: Decodable, Equatable, Sendable {
    var uuid: String?
    var visibility: Int?
    var title: String?
    var body: String?
    var item: NeoDBItem?

    init(uuid: String? = nil, visibility: Int? = nil, title: String? = nil, body: String? = nil, item: NeoDBItem? = nil) {
        self.uuid = uuid
        self.visibility = visibility
        self.title = title
        self.body = body
        self.item = item
    }
}

struct NeoDBSearchResponse: Decodable, Sendable {
    var data: [NeoDBItem]

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        data = try c.decodeIfPresent([NeoDBItem].self, forKey: .data) ?? []
    }

    private enum CodingKeys: String, CodingKey { case data }
}

struct NeoDBClientRegistration: Decodable, Equatable, Sendable {
    var clientId: String
    var clientSecret: String

    private enum CodingKeys: String, CodingKey {
        case clientId = "client_id"
        case clientSecret = "client_secret"
    }
}

struct NeoDBTokenResponse: Decodable, Equatable, Sendable {
    var accessToken: String
    var tokenType: String?
    var scope: String?

    private enum CodingKeys: String, CodingKey {
        case scope
        case accessToken = "access_token"
        case tokenType = "token_type"
    }
}

/// A NeoDB answer outside 2xx: the status and the server's own message (Android `NeoDBException`).
struct NeoDBError: Error, Equatable, LocalizedError {
    var code: Int
    var message: String
    var rawBody: String = ""

    var errorDescription: String? { message }
}

/// Yoin's one album text goes to NeoDB as the mark's short comment up to this many characters, as a
/// Review beyond (Android `NeoDbShortCommentMax`, owner R2 2026-10-06).
let neoDBShortCommentMax = 360

/// [text] fits NeoDB's short comment: counted in code points, as Android does (an emoji is one).
func isNeoDBShortComment(_ text: String) -> Bool { text.unicodeScalars.count <= neoDBShortCommentMax }

extension String {
    /// Kotlin `isNotBlank()`: something other than whitespace.
    var neoDBHasText: Bool { !trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
