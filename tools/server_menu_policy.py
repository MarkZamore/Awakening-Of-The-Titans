"""Pack-owned removal of authored public-server shortcuts, never personal lists."""
import re
from pathlib import Path

POLICY = 'no-public-server-shortcuts-v1'
TITLE = 'config/fancymenu/customization/title_screen_layout1.txt'
ACTION = re.compile(r'\[action_type:joinserver\]')
ELEMENT = re.compile(r'^element \{\r?\n.*?^\}\r?\n?', re.MULTILINE | re.DOTALL)


def strip_shortcuts(raw):
    text = raw.decode('utf-8')
    removed = []

    def replace(match):
        block = match.group()
        if ACTION.search(block):
            removed.append(block)
            return ''
        return block

    updated = ELEMENT.sub(replace, text)
    if ACTION.search(updated):
        raise ValueError('Public-server action outside a complete FancyMenu element')
    if not removed:
        raise ValueError('Author menu has no reviewed public-server shortcut')
    return updated.encode('utf-8'), removed


def verify(root, overlay):
    if overlay.get('publicServerPolicy') != POLICY:
        raise ValueError('Mandatory public-server menu policy is missing')
    root = Path(root)
    title = root / TITLE
    text = title.read_text(encoding='utf-8-sig')
    if 'instance_identifier = mc_titlescreen_multiplayer_button' not in text:
        raise ValueError('Normal multiplayer entry must remain available')
    for menu in (root / 'config/fancymenu/customization').glob('*.txt'):
        if ACTION.search(menu.read_text(encoding='utf-8-sig')):
            raise ValueError('Automatic public-server shortcut returned: ' + menu.name)
