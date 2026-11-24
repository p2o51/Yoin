import Foundation

/// The zip container of a "download all data" backup (docs/cloud-sync.md「导出与导入」), the iOS side of
/// Android's `BackupWriter` / `BackupReader` (java.util.zip). What goes in each entry is built and checked by
/// :shared (`YoinBackup`); this only packs and unpacks it.
///
/// Writing: the entries in the order given (`manifest.json`, `data/snapshot.json`, then the readable files),
/// each DEFLATEd (stored when empty), UTF-8 names (flag bit 11), sizes and CRC in the local header, no data
/// descriptors, no zip64, no extra fields. java.util.zip.ZipInputStream reads it as it reads its own.
///
/// Reading: through the central directory, so Android's zips (sizes in data descriptors after each entry)
/// read as well as ours. Stored and DEFLATEd entries only; anything encrypted or zip64 is refused.
enum BackupZip {
    struct Entry: Equatable, Sendable {
        var path: String
        var data: Data
    }

    /// An entry as the central directory lists it.
    struct Listed: Equatable, Sendable {
        var path: String
        var method: UInt16
        var flags: UInt16
        var crc: UInt32
        var compressedSize: Int
        var size: Int
        var localHeaderOffset: Int
        var isDirectory: Bool { path.hasSuffix("/") }
    }

    enum Failure: Error, Equatable {
        /// No zip structure at all (Android: ZipException → "not a backup").
        case notAZip
        /// A zip this reader can't open (encrypted, zip64, an unknown method) or a damaged entry.
        case unreadable(String)
        /// An entry larger than the limit asked for (Android: `readLimited`).
        case tooLarge(String)
    }

    // MARK: Writing

    /// The zip of [entries]. [modified] is every entry's DOS time (Android stamps the export's time too).
    static func write(_ entries: [Entry], modified: Date = Date()) throws -> Data {
        let (time, date) = dosTime(modified)
        var out = Data()
        var central = Data()
        for entry in entries {
            let name = Data(entry.path.utf8)
            let crc = CRC32.checksum(entry.data)
            let deflated = entry.data.isEmpty ? nil : try (entry.data as NSData).compressed(using: .zlib) as Data
            let method: UInt16 = deflated == nil ? 0 : 8
            let body = deflated ?? entry.data
            guard body.count < Int(UInt32.max), entry.data.count < Int(UInt32.max), out.count < Int(UInt32.max) else {
                throw Failure.unreadable("Entry too large for a zip without zip64")
            }
            let offset = UInt32(out.count)
            // Local file header.
            out.append(le32: 0x0403_4B50)
            out.append(le16: 20)
            out.append(le16: utf8Flag)
            out.append(le16: method)
            out.append(le16: time)
            out.append(le16: date)
            out.append(le32: crc)
            out.append(le32: UInt32(body.count))
            out.append(le32: UInt32(entry.data.count))
            out.append(le16: UInt16(name.count))
            out.append(le16: 0)
            out.append(name)
            out.append(body)
            // Its central directory record.
            central.append(le32: 0x0201_4B50)
            central.append(le16: 20)
            central.append(le16: 20)
            central.append(le16: utf8Flag)
            central.append(le16: method)
            central.append(le16: time)
            central.append(le16: date)
            central.append(le32: crc)
            central.append(le32: UInt32(body.count))
            central.append(le32: UInt32(entry.data.count))
            central.append(le16: UInt16(name.count))
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le16: 0)
            central.append(le32: 0)
            central.append(le32: offset)
            central.append(name)
        }
        guard entries.count < Int(UInt16.max) else { throw Failure.unreadable("Too many entries") }
        let centralOffset = UInt32(out.count)
        out.append(central)
        // End of central directory.
        out.append(le32: 0x0605_4B50)
        out.append(le16: 0)
        out.append(le16: 0)
        out.append(le16: UInt16(entries.count))
        out.append(le16: UInt16(entries.count))
        out.append(le32: UInt32(central.count))
        out.append(le32: centralOffset)
        out.append(le16: 0)
        return out
    }

    // MARK: Reading

    /// Every entry the central directory lists, in its order.
    static func list(_ zip: Data) throws -> [Listed] {
        let bytes = [UInt8](zip)
        guard let end = endOfCentralDirectory(bytes) else { throw Failure.notAZip }
        let count = Int(bytes.le16(end + 10))
        let size = Int(bytes.le32(end + 12))
        let start = Int(bytes.le32(end + 16))
        guard count != 0xFFFF, size != 0xFFFF_FFFF, start != 0xFFFF_FFFF else { throw Failure.unreadable("zip64") }
        guard start + size <= end else { throw Failure.notAZip }
        var out: [Listed] = []
        var at = start
        for _ in 0..<count {
            guard at + 46 <= bytes.count, bytes.le32(at) == 0x0201_4B50 else { throw Failure.unreadable("Central directory") }
            let flags = bytes.le16(at + 8)
            let method = bytes.le16(at + 10)
            let crc = bytes.le32(at + 16)
            let compressed = Int(bytes.le32(at + 20))
            let plain = Int(bytes.le32(at + 24))
            let nameLength = Int(bytes.le16(at + 28))
            let extraLength = Int(bytes.le16(at + 30))
            let commentLength = Int(bytes.le16(at + 32))
            let local = Int(bytes.le32(at + 42))
            guard at + 46 + nameLength <= bytes.count else { throw Failure.unreadable("Central directory") }
            let nameBytes = Data(bytes[(at + 46)..<(at + 46 + nameLength)])
            let name = String(data: nameBytes, encoding: .utf8) ?? String(decoding: nameBytes, as: UTF8.self)
            out.append(Listed(path: name, method: method, flags: flags, crc: crc, compressedSize: compressed,
                              size: plain, localHeaderOffset: local))
            at += 46 + nameLength + extraLength + commentLength
        }
        return out
    }

    /// [entry]'s bytes, checked against its CRC; refuses one larger than [limit] bytes.
    static func extract(_ entry: Listed, from zip: Data, limit: Int) throws -> Data {
        guard entry.size <= limit, entry.compressedSize <= limit else { throw Failure.tooLarge(entry.path) }
        guard entry.flags & 1 == 0 else { throw Failure.unreadable("Encrypted entry") }
        let bytes = zip
        let base = bytes.startIndex
        let at = entry.localHeaderOffset
        guard at + 30 <= bytes.count, bytes.le32At(base + at) == 0x0403_4B50 else { throw Failure.unreadable(entry.path) }
        let nameLength = Int(bytes.le16At(base + at + 26))
        let extraLength = Int(bytes.le16At(base + at + 28))
        let start = at + 30 + nameLength + extraLength
        guard start + entry.compressedSize <= bytes.count else { throw Failure.unreadable(entry.path) }
        let body = bytes.subdata(in: (base + start)..<(base + start + entry.compressedSize))
        let data: Data
        switch entry.method {
        case 0: data = body
        case 8:
            if entry.size == 0 {
                data = Data()
            } else {
                guard let inflated = try? (body as NSData).decompressed(using: .zlib) as Data else {
                    throw Failure.unreadable(entry.path)
                }
                data = inflated
            }
        default: throw Failure.unreadable("Compression method \(entry.method)")
        }
        guard data.count == entry.size, CRC32.checksum(data) == entry.crc else { throw Failure.unreadable(entry.path) }
        return data
    }

    // MARK: Pieces

    private static let utf8Flag: UInt16 = 0x0800

    /// The End Of Central Directory record's offset (searched from the end, past a comment of up to 64 KB).
    private static func endOfCentralDirectory(_ bytes: [UInt8]) -> Int? {
        guard bytes.count >= 22 else { return nil }
        var at = bytes.count - 22
        let stop = max(0, bytes.count - 22 - 0xFFFF)
        while at >= stop {
            if bytes[at] == 0x50, bytes[at + 1] == 0x4B, bytes[at + 2] == 0x05, bytes[at + 3] == 0x06 { return at }
            at -= 1
        }
        return nil
    }

    /// MS-DOS time and date (local time, 2-second steps, from 1980).
    static func dosTime(_ when: Date) -> (time: UInt16, date: UInt16) {
        let c = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day, .hour, .minute, .second], from: when)
        let year = max(1980, min(2107, c.year ?? 1980))
        let time = UInt16((c.hour ?? 0) << 11 | (c.minute ?? 0) << 5 | (c.second ?? 0) / 2)
        let date = UInt16((year - 1980) << 9 | (c.month ?? 1) << 5 | (c.day ?? 1))
        return (time, date)
    }
}

/// The zip CRC-32 (IEEE 802.3, reflected 0xEDB88320).
enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { raw in
            for byte in raw.bindMemory(to: UInt8.self) {
                crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func append(le16 v: UInt16) { append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8)]) }
    mutating func append(le32 v: UInt32) {
        append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)])
    }
    func le16At(_ i: Int) -> UInt16 { UInt16(self[i]) | UInt16(self[i + 1]) << 8 }
    func le32At(_ i: Int) -> UInt32 {
        UInt32(self[i]) | UInt32(self[i + 1]) << 8 | UInt32(self[i + 2]) << 16 | UInt32(self[i + 3]) << 24
    }
}

private extension Array where Element == UInt8 {
    func le16(_ i: Int) -> UInt16 { UInt16(self[i]) | UInt16(self[i + 1]) << 8 }
    func le32(_ i: Int) -> UInt32 {
        UInt32(self[i]) | UInt32(self[i + 1]) << 8 | UInt32(self[i + 2]) << 16 | UInt32(self[i + 3]) << 24
    }
}
