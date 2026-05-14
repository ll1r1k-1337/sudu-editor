package org.sudu.experiments.editor;

import org.junit.jupiter.api.Test;
import org.sudu.experiments.diff.DiffTypes;
import org.sudu.experiments.diff.LineDiff;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
import org.sudu.experiments.editor.worker.diff.DiffRange;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InlineDocumentBuilderTest {

  @Test
  void defaultRangeOnly() {
    Model left = model("a\nb\nc");
    Model right = model("a\nb\nc");
    DiffInfo info = info(
        new DiffRange(0, 3, 0, 3, DiffTypes.DEFAULT)
    );

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(left, right, info);

    assertEquals(3, r.lines.length);
    assertEquals(3, r.diffs.length);
    assertNull(r.diffs[0]);
    assertNull(r.diffs[1]);
    assertNull(r.diffs[2]);
    assertEquals(0, r.hunkCount());
  }

  @Test
  void insertedRangeRendersGreen() {
    Model left = model("a");
    Model right = model("a\nx\ny");
    DiffInfo info = info(
        new DiffRange(0, 1, 0, 1, DiffTypes.DEFAULT),
        new DiffRange(1, 0, 1, 2, DiffTypes.INSERTED)
    );

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(left, right, info);

    assertEquals(3, r.lines.length);
    assertNull(r.diffs[0]);
    assertType(DiffTypes.INSERTED, r.diffs[1]);
    assertType(DiffTypes.INSERTED, r.diffs[2]);

    assertEquals(1, r.hunkCount());
    assertEquals(1, r.hunkAnchorRows[0]);
    assertEquals(3, r.hunkEndRows[0]);
    assertEquals(1, r.hunkRangeIndices[0]);
  }

  @Test
  void deletedRangeRendersGhost() {
    Model left = model("a\nx\ny");
    Model right = model("a");
    DiffInfo info = info(
        new DiffRange(0, 1, 0, 1, DiffTypes.DEFAULT),
        new DiffRange(1, 2, 1, 0, DiffTypes.DELETED)
    );

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(left, right, info);

    assertEquals(3, r.lines.length);
    assertNull(r.diffs[0]);
    assertType(DiffTypes.DELETED, r.diffs[1]);
    assertType(DiffTypes.DELETED, r.diffs[2]);

    // ghost lines reference left model's CodeLines
    assertSame(left.document.lines[1], r.lines[1]);
    assertSame(left.document.lines[2], r.lines[2]);

    assertEquals(1, r.hunkCount());
    assertEquals(1, r.hunkAnchorRows[0]);
    assertEquals(3, r.hunkEndRows[0]);
  }

  @Test
  void editedRangeEmitsGhostThenInsertion() {
    Model left = model("a\nx\nb");
    Model right = model("a\ny\nz\nb");
    DiffInfo info = info(
        new DiffRange(0, 1, 0, 1, DiffTypes.DEFAULT),
        new DiffRange(1, 1, 1, 2, DiffTypes.EDITED),
        new DiffRange(2, 1, 3, 1, DiffTypes.DEFAULT)
    );

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(left, right, info);

    // 1 default + 1 ghost + 2 inserted + 1 default = 5 rows
    assertEquals(5, r.lines.length);
    assertNull(r.diffs[0]);
    assertType(DiffTypes.DELETED, r.diffs[1]);
    assertType(DiffTypes.INSERTED, r.diffs[2]);
    assertType(DiffTypes.INSERTED, r.diffs[3]);
    assertNull(r.diffs[4]);

    assertSame(left.document.lines[1], r.lines[1]);
    assertSame(right.document.lines[1], r.lines[2]);
    assertSame(right.document.lines[2], r.lines[3]);

    assertEquals(1, r.hunkCount());
    assertEquals(1, r.hunkAnchorRows[0]);
    assertEquals(4, r.hunkEndRows[0]);
  }

  @Test
  void compactRangesCollapseUnchangedRegions() {
    // 5 unchanged + 2 inserted + 5 unchanged
    Model left = model("a\nb\nc\nd\ne\nf\ng\nh\ni\nj");
    Model right = model("a\nb\nc\nd\ne\nx\ny\nf\ng\nh\ni\nj");
    DiffInfo info = info(
        new DiffRange(0, 5, 0, 5, DiffTypes.DEFAULT),
        new DiffRange(5, 0, 5, 2, DiffTypes.INSERTED),
        new DiffRange(5, 5, 7, 5, DiffTypes.DEFAULT)
    );

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(left, right, info);
    CompactViewRange[] cvr = InlineDocumentBuilder.buildCompactRanges(r, 3);

    assertEquals(3, cvr.length);
    // Inserted hunk stays fully visible
    assertEquals(true, cvr[1].visible, "hunk range should be visible");
    // Each 5-row default range with 3 context rows transferred keeps 2 rows
    // collapsed, so both stay marked invisible.
    assertEquals(false, cvr[0].visible, "leading default range stays collapsed");
    assertEquals(false, cvr[2].visible, "trailing default range stays collapsed");
  }

  @Test
  void emptyRangesProducesEmptyOutput() {
    Model left = model("");
    Model right = model("");
    DiffInfo info = info();

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(left, right, info);

    assertEquals(0, r.lines.length);
    assertEquals(0, r.diffs.length);
    assertEquals(0, r.hunkCount());
    assertArrayEquals(new int[0], r.hunkAnchorRows);
  }

  private static void assertType(int expected, LineDiff actual) {
    assertNotNull(actual, "expected LineDiff with type " + DiffTypes.name(expected));
    assertEquals(expected, actual.type,
        "expected " + DiffTypes.name(expected) + " got " + DiffTypes.name(actual.type));
  }

  private static void assertSame(CodeLine expected, CodeLine actual) {
    assertEquals(true, expected == actual, "expected same CodeLine reference");
  }

  private static Model model(String text) {
    return new Model(text, null);
  }

  private static DiffInfo info(DiffRange... ranges) {
    return new DiffInfo(null, null, ranges);
  }
}
