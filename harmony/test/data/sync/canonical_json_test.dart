import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/identity/account_fingerprints.dart';

void main() {
  group('CanonicalJson', () {
    test('should_sortKeysRecursively_when_canonicalised', () {
      final a = {
        'c': {'d': 7.0},
        'b': 'x"y',
        'a': [1, 2.5, null, true],
      };
      expect(CanonicalJson.canonical(a), r'{"a":[1,2.5,null,true],"b":"x\"y","c":{"d":7.0}}');
      // The same text Kotlin hashes (sha256 of the canonical UTF-8).
      expect(CanonicalJson.hash(a), '81cf73e63b6b2bc8ac46627e3f2feeedd23ad8272613d0d98cb529779d363526');
    });

    test('should_hashAlike_when_keyOrderDiffers', () {
      expect(CanonicalJson.hash({'x': 1, 'y': 'z'}), CanonicalJson.hash({'y': 'z', 'x': 1}));
    });

    test('should_keepWholeDoublesAsFloats_when_ratingsAreWritten', () {
      // Kotlin prints 7f as 7.0: a rating must never hash as an integer.
      expect(CanonicalJson.canonical({'rating': 7.0}), '{"rating":7.0}');
      expect(CanonicalJson.canonical({'rating': 7}), '{"rating":7}');
      expect(jsonDecode(compactJson({'rating': 7.5})), {'rating': 7.5});
    });

    test('should_escapeLikeKotlinx_when_stringsHaveControlCharacters', () {
      expect(CanonicalJson.canonical('a\u0001\n\\/é'), r'"a\u0001\n\\/é"');
    });

    test('should_matchKotlinStableHash_when_routingArtifactShards', () {
      expect(CanonicalJson.stableHash('provider\u001Fsong'), 2102830874);
      expect(SyncEngine.artifactShard('provider\u001Fsong'), 2);
    });

    test('should_keepKeyOrder_when_compactJsonWritten', () {
      expect(compactJson({'b': 1, 'a': null}), '{"b":1,"a":null}');
    });
  });

  group('AccountFingerprints', () {
    test('should_normalizeSchemeCaseAndSlash_when_subsonicFingerprinted', () {
      final a = AccountFingerprints.subsonic('https://Music.Example.com:4533/navidrome/', ' Alice ');
      final b = AccountFingerprints.subsonic('http://music.example.com:4533/navidrome', 'alice');
      final c = AccountFingerprints.subsonic('music.example.com:4533/navidrome', 'ALICE');
      expect(a, 'subsonic:v1:music.example.com:4533/navidrome\u001Falice');
      expect(b, a);
      expect(c, a);
    });

    test('should_dropDefaultPort_when_https443', () {
      expect(
        AccountFingerprints.subsonic('https://music.example.com:443', 'bob'),
        'subsonic:v1:music.example.com\u001Fbob',
      );
      expect(
        AccountFingerprints.subsonic('http://music.example.com', 'bob'),
        AccountFingerprints.subsonic('https://music.example.com/', 'bob'),
      );
    });

    test('should_returnNull_when_userBlankOrAddressUnusable', () {
      expect(AccountFingerprints.subsonic('https://music.example.com', '  '), isNull);
      expect(AccountFingerprints.subsonic('ftp://music.example.com', 'bob'), isNull);
      expect(AccountFingerprints.subsonic('', 'bob'), isNull);
    });

    test('should_mintAndroidsSyncId_when_deterministicIdComputed', () {
      expect(
        AccountFingerprints.deterministicId('subsonic:v1:music.example.com:4533/navidrome\u001Falice'),
        's-e7bf3b718c59d1eaa3f6b859',
      );
    });

    test('should_showUserAtHost_when_hintBuilt', () {
      expect(
        AccountFingerprints.subsonicHint('https://u:p@music.example.com:8443/x?y=1', 'Alice'),
        'Alice @ music.example.com:8443',
      );
    });
  });
}
