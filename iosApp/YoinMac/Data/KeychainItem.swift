import Foundation
import Security

/// The Mac's copy of iOS ServerSession's helper (that file isn't on the Mac): updates the item in place,
/// adding it only when there is none, so a failure never loses the previous value.
enum KeychainItem {
    static func upsert(query: [String: Any], data: Data) -> OSStatus {
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        guard status == errSecItemNotFound else { return status }
        var add = query
        add.merge(update) { $1 }
        return SecItemAdd(add as CFDictionary, nil)
    }
}
