from pathlib import Path
import json, re, xml.etree.ElementTree as ET

root = Path(__file__).parent
if not (root / 'decoded').exists():
    root = Path(__file__).resolve().parents[3] / 'build/research/gridart-2.454.225-20261029'
decoded = root / 'decoded'
tables = {}
for locale, folder in [('en', 'values'), ('ko', 'values-ko')]:
    tables[locale] = {n.attrib['name']: ''.join(n.itertext()) for n in ET.parse(decoded / 'res' / folder / 'strings.xml').getroot().findall('string')}

def label(res_id, locale):
    return tables[locale].get('APKTOOL_DUPLICATE_string_' + res_id, res_id)

menus = {}
for cls in ['j20', 'dt0']:
    path = root / 'sources' / (cls + '_clinit.txt' if cls == 'j20' else 'dt0_clinit.txt')
    source = path.read_text(encoding='utf-8')
    entries = []
    cursor = 0
    for m in re.finditer(r'sput-object\s+\w+, L' + cls + r';->(\w+):L' + cls + r';', source):
        chunk = source[cursor:m.start()]
        ids = re.findall(r'const(?:/16)?\s+\w+,\s+(0x7f12[0-9a-f]+)', chunk)
        entries.append({'field': m[1], 'resource_ids': ids, 'en': [label(i, 'en') for i in ids], 'ko': [label(i, 'ko') for i in ids]})
        cursor = m.end()
    menus[cls] = entries

android = '{http://schemas.android.com/apk/res/android}'
layouts = {}
names = ['activity_edit', 'edit_layout_view', 'top_tool_bar_layout', 'fragment_grid_layout', 'fragment_margin_layout', 'fragment_border_layout', 'fragment_ratio_layout', 'fragment_free_grid_layout', 'fragment_gallery_layout', 'activity_image_selector', 'fragment_template_filter', 'fragment_text_layout', 'fragment_text_adjust_layout']
for name in names:
    layouts[name] = []
    for node in ET.parse(decoded / 'res/layout' / (name + '.xml')).getroot().iter():
        attrs = {k.replace(android, ''): v for k, v in node.attrib.items() if k.startswith(android)}
        if 'id' not in attrs and 'text' not in attrs:
            continue
        raw = attrs.get('text', '')
        if raw.startswith('@string/'):
            attrs['text_ko'] = tables['ko'].get(raw[8:], raw)
            attrs['text_en'] = tables['en'].get(raw[8:], raw)
        layouts[name].append({'widget': node.tag, **attrs})

result = {'source_apk_sha256': 'abcfbb5d40281dafcc1826afbbaf10c5d1d3785c232342dded30045391df271c', 'basis': 'REA JADX smali and Apktool decoded resources. Menu rows list string constants preceding each field assignment, not a register-level binding. Multiple labels in one row are candidates, and reused registers may omit a label. Constructor arrays and runtime conditions must be consulted separately.', 'menus': menus, 'layouts': layouts}
(root / 'resource-index.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'menus': menus, 'layout_count': len(layouts)}, ensure_ascii=False, indent=2))
