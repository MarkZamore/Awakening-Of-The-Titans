"""The Steam menu policy must not remove ordinary multiplayer or other author UI."""
import tempfile
import unittest
from pathlib import Path
import server_menu_policy as policy


class ServerMenuPolicyTests(unittest.TestCase):
    def test_removes_only_complete_shortcut_element_and_preserves_author_bytes(self):
        normal = b'element {\r\n  instance_identifier = mc_titlescreen_multiplayer_button\r\n}\r\n'
        shortcut = b'element {\r\n  [action_type:joinserver] = public.example.invalid\r\n  label = Public server\r\n}\r\n'
        prefix = b'meta {\r\n  name = author\r\n}\r\n'
        suffix = b'element {\r\n  label = Worlds and quests\r\n}\r\n'
        result, removed = policy.strip_shortcuts(prefix + shortcut + normal + suffix)
        self.assertEqual(prefix + normal + suffix, result)
        self.assertEqual([shortcut.decode('utf-8')], removed)

    def test_unknown_action_structure_and_missing_author_anchor_fail_closed(self):
        for raw in (b'element {\n  label = World\n}\n', b'[action_type:joinserver] = public.example.invalid\n'):
            with self.assertRaises(ValueError):
                policy.strip_shortcuts(raw)

    def test_new_author_menu_cannot_reintroduce_a_public_shortcut(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            title = root / policy.TITLE
            title.parent.mkdir(parents=True)
            title.write_text('element {\n  instance_identifier = mc_titlescreen_multiplayer_button\n}\n', encoding='utf-8')
            overlay = {'publicServerPolicy': policy.POLICY}
            policy.verify(root, overlay)
            # Personal server lists are outside this policy, even when nonempty.
            personal = root / 'servers.dat'
            personal.write_bytes(b'player-owned data')
            policy.verify(root, overlay)
            self.assertEqual(b'player-owned data', personal.read_bytes())
            (title.parent / 'new-author-menu.txt').write_text('[action_type:joinserver] = public.example.invalid\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                policy.verify(root, overlay)

    def test_policy_and_normal_multiplayer_cannot_be_removed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            title = root / policy.TITLE
            title.parent.mkdir(parents=True)
            title.write_text('element {\n  label = Other screen\n}\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                policy.verify(root, {'publicServerPolicy': policy.POLICY})
            with self.assertRaises(ValueError):
                policy.verify(root, {})
