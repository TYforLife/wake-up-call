"""Tests for tools/check_pr.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import unittest

from check_pr import problems

APK = "https://github.com/tyforlife/wake-up-call/releases/download/debug-latest/app-debug.apk"
BODY = f"## Why\nBecause.\n\n## Test on phone\n- [ ] Ride a bus.\n\n{APK}\n"


class CheckPrTest(unittest.TestCase):
    def test_a_good_feature_pr_passes(self):
        self.assertEqual(problems("feat(map): drag the circle", BODY, ["feat(map): drag"], ["app/src/main/A.kt"]), [])

    def test_a_title_that_is_not_a_conventional_commit_fails(self):
        for title in ["Drag the circle", "fix + feat: two things", "feat(Map): capital scope", "feat:no space"]:
            with self.subTest(title=title):
                self.assertIn("conventional commit", problems(title, BODY, [], [])[0])

    def test_a_body_without_test_on_phone_fails(self):
        found = problems("docs: words", "## Why\nBecause.\n" + APK, [], [])
        self.assertEqual(len(found), 1)
        self.assertIn("Test on phone", found[0])

    def test_test_on_phone_without_the_apk_link_fails(self):
        found = problems("docs: words", "## Test on phone\n- [ ] Ride a bus.\n", [], [])
        self.assertEqual(len(found), 1)
        self.assertIn("APK link", found[0])

    def test_an_apk_link_above_the_section_does_not_count(self):
        found = problems("docs: words", f"{APK}\n## Test on phone\n- [ ] Ride.\n", [], [])
        self.assertIn("APK link", found[0])

    def test_a_fix_without_a_test_fails(self):
        found = problems("fix(alarm): ring on mute", BODY, ["fix(alarm): ring on mute"], ["app/src/main/R.kt"])
        self.assertEqual(len(found), 1)
        self.assertIn("failing test", found[0])

    def test_a_fix_commit_inside_a_feat_pr_still_needs_a_test(self):
        found = problems("feat(map): drag", BODY, ["feat(map): drag", "fix(map): off by one"], ["app/src/main/M.kt"])
        self.assertIn("failing test", found[0])

    def test_a_fix_with_a_test_passes_in_every_test_source_set(self):
        for path in [
            "engine/src/test/kotlin/E.kt",
            "app/src/testDebug/kotlin/A.kt",
            "app/src/screenshotTest/kotlin/app/nodstop/alarm/RingPathTest.kt",
            "tools/test_check_pr.py",
        ]:
            with self.subTest(path=path):
                self.assertEqual(problems("fix(x): y", BODY, ["fix(x): y"], ["app/src/main/A.kt", path]), [])

    def test_a_fix_with_a_reason_for_no_test_passes(self):
        body = BODY + "\nNo test: Samsung's battery dialog only exists on a phone; the readiness check would show it.\n"
        self.assertEqual(problems("fix(ready): open the battery page", body, [], ["app/src/main/R.kt"]), [])

    def test_an_empty_no_test_line_does_not_count(self):
        body = BODY + "\nNo test:\n"
        self.assertIn("failing test", problems("fix(x): y", body, [], ["app/src/main/A.kt"])[0])

    def test_a_feature_needs_no_test_from_this_check(self):
        self.assertEqual(problems("feat(x): y", BODY, ["feat(x): y"], ["app/src/main/A.kt"]), [])


if __name__ == "__main__":
    unittest.main()
