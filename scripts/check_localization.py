#!/usr/bin/env python3
"""Validate the core locale resources without an Android SDK."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
STRICT_LOCALES = ('es',)
PARTIAL_LOCALES = ('de', 'it', 'pt', 'zh-rCN')


def resources(path):
    nodes = [n for file in path.glob("*.xml") for n in ET.parse(file).getroot()
             if n.tag in ("string", "string-array", "plurals")]
    keys = [(n.tag, n.attrib['name']) for n in nodes]
    assert len(keys) == len(set(keys)), f'Duplicate resources in {path}'
    return {(n.tag, n.attrib['name']): n for n in nodes}


def placeholders(text):
    # Preserve argument position and type; Android allows implicit position for a single argument.
    matches = re.findall(r'%(?:(\d+)\$)?[\-+0 ,(#]*\d*(?:\.\d+)?([sdf])', text)
    return sorted((int(pos) if pos else i + 1, kind) for i, (pos, kind) in enumerate(matches))


def validate_entries(locale, required, translated):
    for key in required.keys() & translated.keys():
        node = required[key]
        other = translated[key]
        assert node.tag == other.tag, (locale, key, 'resource type')
        if node.tag == 'string':
            assert placeholders(''.join(node.itertext())) == placeholders(''.join(other.itertext())), (locale, key, 'format arguments')
        elif node.tag == 'string-array':
            assert len(node) == len(other), (locale, key, 'array size')
        elif node.tag == 'plurals':
            for item in other:
                assert placeholders(''.join(item.itertext())) == placeholders(''.join(node[-1].itertext())), (locale, key, 'plural arguments')


base = resources(RES / 'values')
required = {k: n for k, n in base.items() if n.get('translatable') != 'false'}
for locale in STRICT_LOCALES:
    translated = resources(RES / f'values-{locale}')
    assert not required.keys() - translated.keys(), (locale, 'missing', required.keys() - translated.keys())
    validate_entries(locale, required, translated)
    print(f'{locale}: {len(required)} translatable resources, 100% coverage and format arguments OK')

for locale in PARTIAL_LOCALES:
    translated = resources(RES / f'values-{locale}')
    validate_entries(locale, required, translated)
    present = len(required.keys() & translated.keys())
    print(f'{locale}: {present}/{len(required)} translatable resources, format arguments OK (rest fall back to en)')

ns = '{http://schemas.android.com/apk/res/android}'
configured = {e.attrib[ns + 'name'] for e in ET.parse(RES / 'xml/locales_config.xml').getroot()}
expected = {'en'} | {p.parent.name.removeprefix('values-').replace('-r', '-') for p in RES.glob('values-*/strings.xml')}
assert configured == expected, ('system locale configuration mismatch', configured ^ expected)
print('System language configuration matches available translations')
