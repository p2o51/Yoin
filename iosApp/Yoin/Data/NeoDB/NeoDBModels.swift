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

    init(uuid: String? = nil, displayTitle: String? = nil, category: String? = nil) {
        self.uuid = uuid
        self.displayTitle = displayTitle
        self.category = category
    }

    private enum CodingKeys: String, CodingKey {
        case uuid, category
        case displayTitle = "display_title"
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
