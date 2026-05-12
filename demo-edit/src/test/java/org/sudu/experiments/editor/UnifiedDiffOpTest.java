package org.sudu.experiments.editor;

import org.junit.jupiter.api.Test;
import org.sudu.experiments.diff.DiffModel;
import org.sudu.experiments.diff.DiffTypes;
import org.sudu.experiments.diff.LineDiff;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
import org.sudu.experiments.editor.worker.diff.DiffRange;
import org.sudu.experiments.editor.worker.diff.DiffUtils;
import org.sudu.experiments.text.SplitText;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class UnifiedDiffOpTest {

  @Test
  void buildsUnchangedMapping() {
    var mapping = mapping("a\nb", "a\nb");

    assertArrayEquals(new int[] {0, 1}, mapping.docLines);
    assertArrayEquals(new boolean[] {true, true}, mapping.rightSide);
    assertArrayEquals(new int[] {0, 1}, mapping.leftLineNumbers);
    assertArrayEquals(new int[] {0, 1}, mapping.rightLineNumbers);
    assertArrayEquals(new byte[] {0, 0}, mapping.viewLineTypes);
    assertEquals(0, mapping.actionLines.length);
  }

  @Test
  void buildsInsertionMapping() {
    var mapping = mapping("a\nc", "a\nb\nc");

    assertArrayEquals(new int[] {0, 1, 2}, mapping.docLines);
    assertArrayEquals(new boolean[] {true, true, true}, mapping.rightSide);
    assertArrayEquals(new int[] {0, -1, 1}, mapping.leftLineNumbers);
    assertArrayEquals(new int[] {0, 1, 2}, mapping.rightLineNumbers);
    assertArrayEquals(new byte[] {0, DiffTypes.INSERTED, 0}, mapping.viewLineTypes);
    assertArrayEquals(new int[] {1}, mapping.actionLines);
  }

  @Test
  void buildsDeletionMapping() {
    var mapping = mapping("a\nb\nc", "a\nc");

    assertArrayEquals(new int[] {0, 1, 1}, mapping.docLines);
    assertArrayEquals(new boolean[] {true, false, true}, mapping.rightSide);
    assertArrayEquals(new int[] {0, 1, 2}, mapping.leftLineNumbers);
    assertArrayEquals(new int[] {0, -1, 1}, mapping.rightLineNumbers);
    assertArrayEquals(new byte[] {0, DiffTypes.DELETED, 0}, mapping.viewLineTypes);
    assertArrayEquals(new int[] {1}, mapping.actionLines);
  }

  @Test
  void buildsEditedBlockMapping() {
    var mapping = mapping("a\nleft\nc", "a\nright\nc");

    assertArrayEquals(new int[] {0, 1, 1, 2}, mapping.docLines);
    assertArrayEquals(new boolean[] {true, false, true, true}, mapping.rightSide);
    assertArrayEquals(new int[] {0, 1, -1, 2}, mapping.leftLineNumbers);
    assertArrayEquals(new int[] {0, -1, 1, 2}, mapping.rightLineNumbers);
    assertArrayEquals(new byte[] {0, DiffTypes.DELETED, DiffTypes.INSERTED, 0},
        mapping.viewLineTypes);
    assertArrayEquals(new int[] {1}, mapping.actionLines);
    assertEquals(DiffTypes.EDITED, mapping.actionRanges[0].type);
  }

  @Test
  void compactMappingKeepsThreeContextLinesAroundDiff() {
    DiffRange[] ranges = {
        new DiffRange(0, 10, 0, 10, DiffTypes.DEFAULT),
        new DiffRange(10, 1, 10, 1, DiffTypes.EDITED),
        new DiffRange(11, 10, 11, 10, DiffTypes.DEFAULT)
    };
    DiffInfo info = new DiffInfo(new LineDiff[21], new LineDiff[21], ranges);

    var mapping = UnifiedDiffOp.buildMapping(info, true);

    assertArrayEquals(new int[] {7, 8, 9, 10, 10, 11, 12, 13}, mapping.docLines);
    assertArrayEquals(new boolean[] {true, true, true, false, true, true, true, true},
        mapping.rightSide);
    assertArrayEquals(new int[] {7, 8, 9, 10, -1, 11, 12, 13},
        mapping.leftLineNumbers);
    assertArrayEquals(new int[] {7, 8, 9, -1, 10, 11, 12, 13},
        mapping.rightLineNumbers);
    assertArrayEquals(new int[] {3}, mapping.actionLines);
  }

  @Test
  void acceptCopiesRightHunkIntoLeftModel() {
    Model left = model("a\nold\nc");
    Model right = model("a\nnew\nc");
    DiffRange range = firstDiff(left, right);

    assertTrue(UnifiedDiffOp.applyReviewAction(left, right, range, true));

    assertEquals(text(right), text(left));
    assertTrue(diff(left, right).isEmpty());
  }

  @Test
  void rejectCopiesLeftHunkIntoRightModel() {
    Model left = model("a\nold\nc");
    Model right = model("a\nnew\nc");
    DiffRange range = firstDiff(left, right);

    assertTrue(UnifiedDiffOp.applyReviewAction(left, right, range, false));

    assertEquals(text(left), text(right));
    assertTrue(diff(left, right).isEmpty());
  }

  @Test
  void acceptThenRejectKeepsInlineMappingInBounds() {
    Model left = model(sampleLeftText());
    Model right = model(sampleRightText());

    assertTrue(UnifiedDiffOp.applyReviewAction(left, right, firstDiff(left, right), true));
    assertMappingInBounds(UnifiedDiffOp.buildMapping(diff(left, right), false), left, right);

    assertTrue(UnifiedDiffOp.applyReviewAction(left, right, firstDiff(left, right), false));
    assertMappingInBounds(UnifiedDiffOp.buildMapping(diff(left, right), false), left, right);
  }

  private static UnifiedDiffOp.Mapping mapping(String left, String right) {
    return UnifiedDiffOp.buildMapping(diff(model(left), model(right)), false);
  }

  private static DiffRange firstDiff(Model left, Model right) {
    return Arrays.stream(diff(left, right).ranges)
        .filter(range -> range.type != DiffTypes.DEFAULT)
        .findFirst()
        .orElseThrow();
  }

  private static DiffInfo diff(Model left, Model right) {
    DiffModel model = new DiffModel();
    model.compareLinesOnly = true;
    return DiffUtils.readDiffInfo(model.findDiffs(
        left.document.getChars(),
        DiffUtils.makeIntervals(left.document, true),
        right.document.getChars(),
        DiffUtils.makeIntervals(right.document, true)));
  }

  private static Model model(String text) {
    return new Model(SplitText.split(text), (Uri) null);
  }

  private static String text(Model model) {
    return Arrays.stream(model.document.lines)
        .map(CodeLine::makeString)
        .collect(Collectors.joining("\n"));
  }

  private static void assertMappingInBounds(
      UnifiedDiffOp.Mapping mapping,
      Model left,
      Model right
  ) {
    for (int i = 0; i < mapping.length(); i++) {
      int docLine = mapping.docLines[i];
      int length = mapping.rightSide[i] ? right.document.length() : left.document.length();
      assertTrue(0 <= docLine && docLine < length,
          "view line " + i + " maps to " + docLine + " of " + length);
    }
  }

  private static String sampleLeftText() {
    return "function formatTitle(value) {\n" +
        "  return value.trim().toLowerCase();\n" +
        "}\n" +
        "\n" +
        "function renderUser(user) {\n" +
        "  const title = formatTitle(user.title);\n" +
        "  return `${title}: ${user.name}`;\n" +
        "}\n" +
        "\n" +
        "export function renderUsers(users) {\n" +
        "  return users.map(renderUser).join('\\n');\n" +
        "}\n";
  }

  private static String sampleRightText() {
    return "function formatTitle(value) {\n" +
        "  return value.trim().toUpperCase();\n" +
        "}\n" +
        "\n" +
        "function renderUser(user) {\n" +
        "  const title = formatTitle(user.role);\n" +
        "  const name = user.displayName ?? user.name;\n" +
        "  return `${title}: ${name}`;\n" +
        "}\n" +
        "\n" +
        "export function renderUsers(users) {\n" +
        "  return users.filter(Boolean).map(renderUser).join('\\n');\n" +
        "}\n";
  }
}
