# Copyright (C) 2026 Javier Sánchez
# SPDX-License-Identifier: GPL-3.0-or-later
import unittest

from release_version import check_title, decide, parse_commit, version_code


class ParseCommit(unittest.TestCase):
    def test_types(self):
        self.assertEqual(parse_commit("feat: add widget").kind, "feat")
        self.assertEqual(parse_commit("fix(sync): crash on 404").kind, "fix")
        self.assertEqual(parse_commit("Docs: update readme").kind, "docs")
        self.assertEqual(parse_commit("wip stuff").kind, "other")
        self.assertEqual(parse_commit("banana: thing").kind, "other")

    def test_breaking(self):
        self.assertTrue(parse_commit("feat!: drop api").breaking)
        self.assertTrue(parse_commit("fix(x)!: y").breaking)
        self.assertTrue(parse_commit("feat: a\n\nBREAKING CHANGE: removes b").breaking)
        self.assertFalse(parse_commit("feat: a").breaking)

    def test_squash_suffix_and_merge_commits(self):
        self.assertEqual(parse_commit("feat: add widget (#12)").kind, "feat")
        self.assertEqual(parse_commit("Merge pull request #5 from a/b\n\nfix: repair thing").kind, "fix")

    def test_skip(self):
        self.assertTrue(parse_commit("fix: typo [skip release]").skip)


class Decide(unittest.TestCase):
    def test_first_release(self):
        self.assertEqual(decide(None, ["feat: x"]), ("initial", (0, 1, 0)))
        self.assertEqual(decide(None, ["docs: x", "ci: y"]), ("none", None))

    def test_bumps(self):
        self.assertEqual(decide((1, 2, 3), ["fix: a"]), ("patch", (1, 2, 4)))
        self.assertEqual(decide((1, 2, 3), ["fix: a", "feat: b"]), ("minor", (1, 3, 0)))
        self.assertEqual(decide((1, 2, 3), ["feat: a", "fix!: b"]), ("major", (2, 0, 0)))
        self.assertEqual(decide((1, 2, 3), ["other thing"]), ("patch", (1, 2, 4)))

    def test_no_release_types_and_skip(self):
        self.assertEqual(decide((1, 2, 3), ["docs: a", "chore: b", "test: c", "ci: d"]), ("none", None))
        self.assertEqual(decide((1, 2, 3), ["feat: a [skip release]"]), ("none", None))
        self.assertEqual(decide((1, 2, 3), []), ("none", None))

    def test_zero_major_breaking_is_minor(self):
        self.assertEqual(decide((0, 4, 2), ["feat!: a"]), ("minor", (0, 5, 0)))

    def test_docs_do_not_hide_a_fix(self):
        self.assertEqual(decide((0, 1, 0), ["docs: a", "fix: b"]), ("patch", (0, 1, 1)))


class Misc(unittest.TestCase):
    def test_version_code_monotonic(self):
        self.assertLess(version_code((0, 1, 0)), version_code((0, 1, 1)))
        self.assertLess(version_code((0, 9, 999)), version_code((0, 10, 0)))
        self.assertLess(version_code((0, 999, 999)), version_code((1, 0, 0)))
        self.assertEqual(version_code((1, 2, 3)), 1_002_003)
        with self.assertRaises(ValueError):
            version_code((0, 1000, 0))

    def test_titles(self):
        self.assertIsNone(check_title("feat: add offline mode"))
        self.assertIsNone(check_title("fix(ui)!: change thing"))
        self.assertIsNotNone(check_title("Add offline mode"))
        self.assertIsNotNone(check_title("feat:missing space"))
        self.assertIsNotNone(check_title("nonsense: x"))


if __name__ == "__main__":
    unittest.main()
