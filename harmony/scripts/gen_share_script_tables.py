#!/usr/bin/env python3
"""Copies :shared ShareScriptTables.kt into lib/data/share/share_script_tables.dart.

The tables are the JVM's Character data (Java 21 / Unicode 15.0), which Kotlin's ShareScriptsTableTest writes;
Dart has no script classes of its own that answer alike, so the port reads the same ranges. Run from harmony/:

    python3 scripts/gen_share_script_tables.py
"""
import re
from pathlib import Path

SRC = Path('../shared/src/commonMain/kotlin/com/gpo/yoin/shared/share/ShareScriptTables.kt')
OUT = Path('lib/data/share/share_script_tables.dart')


def array(src: str, name: str) -> list[str]:
    match = re.search(r'val %s: IntArray = intArrayOf\((.*?)\)' % name, src, re.S)
    return [x.strip() for x in match.group(1).replace('\n', ' ').split(',') if x.strip()]


def main() -> None:
    src = SRC.read_text()
    out = [
        "// Copied from :shared ShareScriptTables.kt (generated from the JVM's Character, Java 21 / Unicode 15.0,",
        '// by ShareScriptsTableTest). Do not edit: regenerate with harmony/scripts/gen_share_script_tables.py.',
        '',
        '/// The two code point sets the CJK rule reads ([ShareScripts]), as inclusive',
        '/// [first, last] pairs in order: every letter (`Character.isLetter`, general',
        '/// category L*), and the letters whose script (`Character.UnicodeScript`, not',
        '/// Script_Extensions) is Han, Hiragana, Katakana, Hangul or Bopomofo.',
        'abstract final class ShareScriptTables {',
    ]
    for name, doc in [('letters', 'Every letter.'), ('cjkLetters', 'The CJK-script letters.')]:
        nums = array(src, name)
        out.append('  /// %s' % doc)
        out.append('  static const List<int> %s = [' % name)
        for i in range(0, len(nums), 10):
            out.append('    ' + ', '.join(nums[i:i + 10]) + ',')
        out.append('  ];')
        out.append('')
    out[-1] = '}'
    OUT.write_text('\n'.join(out) + '\n')


if __name__ == '__main__':
    main()
