package org.sudu.experiments.editor;

import org.sudu.experiments.diff.DiffTypes;
import org.sudu.experiments.diff.LineDiff;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
import org.sudu.experiments.editor.worker.diff.DiffRange;

// Builds the unified inline-review layout from a pair of (left, right) documents
// and a DiffInfo. Each non-default DiffRange contributes its left rows first
// (rendered as red "ghost" deletions) and then its right rows (rendered green
// as insertions). Default ranges contribute only their right rows.
public final class InlineDocumentBuilder {

  public static final class Result {
    public final CodeLine[] lines;
    public final LineDiff[] diffs;
    public final int[] hunkAnchorRows;
    public final int[] hunkEndRows;
    public final int[] hunkRangeIndices;
    // Per-range synthetic row span, parallel to DiffInfo.ranges.
    // rangeStart[i] is inclusive; rangeEnd[i] is exclusive.
    public final int[] rangeStartRows;
    public final int[] rangeEndRows;
    public final int[] rangeTypes;

    public Result(
        CodeLine[] lines, LineDiff[] diffs,
        int[] hunkAnchorRows, int[] hunkEndRows, int[] hunkRangeIndices,
        int[] rangeStartRows, int[] rangeEndRows, int[] rangeTypes
    ) {
      this.lines = lines;
      this.diffs = diffs;
      this.hunkAnchorRows = hunkAnchorRows;
      this.hunkEndRows = hunkEndRows;
      this.hunkRangeIndices = hunkRangeIndices;
      this.rangeStartRows = rangeStartRows;
      this.rangeEndRows = rangeEndRows;
      this.rangeTypes = rangeTypes;
    }

    public int hunkCount() {
      return hunkAnchorRows.length;
    }
  }

  public static Result build(Model mL, Model mR, DiffInfo diffInfo) {
    DiffRange[] ranges = diffInfo.ranges;
    int size = UnifiedDiffOp.unifiedSize(ranges);

    CodeLine[] lines = new CodeLine[size];
    LineDiff[] diffs = new LineDiff[size];

    int hunkCount = 0;
    for (DiffRange r : ranges) if (r.type != DiffTypes.DEFAULT) hunkCount++;

    int[] anchors = new int[hunkCount];
    int[] ends = new int[hunkCount];
    int[] rangeIdx = new int[hunkCount];

    int n = ranges.length;
    int[] rangeStartRows = new int[n];
    int[] rangeEndRows = new int[n];
    int[] rangeTypes = new int[n];

    int row = 0;
    int hunk = 0;
    for (int i = 0; i < n; i++) {
      DiffRange r = ranges[i];
      int startRow = row;
      switch (r.type) {
        case DiffTypes.DEFAULT -> {
          row = copyRight(mR, r, lines, diffs, row, null);
        }
        case DiffTypes.INSERTED -> {
          row = copyRight(mR, r, lines, diffs, row, DiffTypes.INSERTED);
        }
        case DiffTypes.DELETED -> {
          row = copyLeft(mL, r, lines, diffs, row, DiffTypes.DELETED);
        }
        case DiffTypes.EDITED, DiffTypes.EDITED2 -> {
          row = copyLeft(mL, r, lines, diffs, row, DiffTypes.DELETED);
          row = copyRight(mR, r, lines, diffs, row, DiffTypes.INSERTED);
        }
      }
      rangeStartRows[i] = startRow;
      rangeEndRows[i] = row;
      rangeTypes[i] = r.type;
      if (r.type != DiffTypes.DEFAULT) {
        anchors[hunk] = startRow;
        ends[hunk] = row;
        rangeIdx[hunk] = i;
        hunk++;
      }
    }

    return new Result(
        lines, diffs, anchors, ends, rangeIdx,
        rangeStartRows, rangeEndRows, rangeTypes
    );
  }

  // Builds the CompactViewRange[] over the synthetic-row space for compact view.
  // For each diff range we emit one CompactViewRange spanning the same rows.
  // Default (unchanged) ranges start collapsed; hunk ranges stay visible.
  // Up to `contextLines` rows at each end of a default range that touches a hunk
  // are transferred to the adjacent visible range so the user sees context.
  public static CompactViewRange[] buildCompactRanges(Result r, int contextLines) {
    int n = r.rangeStartRows.length;
    if (n == 0) return new CompactViewRange[0];

    CompactViewRange[] cvr = new CompactViewRange[n];
    for (int i = 0; i < n; i++) {
      boolean isDefault = r.rangeTypes[i] == DiffTypes.DEFAULT;
      cvr[i] = new CompactViewRange(r.rangeStartRows[i], r.rangeEndRows[i], !isDefault);
    }

    // Expand collapsed regions: transfer up to `contextLines` rows from each
    // default range into the adjacent hunk range(s). If a default range is too
    // short to keep at least one row hidden, mark it fully visible.
    for (int i = 0; i < n; i++) {
      if (r.rangeTypes[i] != DiffTypes.DEFAULT) continue;
      CompactViewRange.expand(contextLines, i, cvr);
    }
    return cvr;
  }

  private static int copyRight(
      Model mR, DiffRange r,
      CodeLine[] lines, LineDiff[] diffs,
      int row, Integer diffType
  ) {
    CodeLine[] src = mR.document.lines;
    for (int k = 0; k < r.lenR; k++) {
      lines[row] = src[r.fromR + k];
      diffs[row] = diffType == null ? null : new LineDiff(diffType);
      row++;
    }
    return row;
  }

  private static int copyLeft(
      Model mL, DiffRange r,
      CodeLine[] lines, LineDiff[] diffs,
      int row, int diffType
  ) {
    CodeLine[] src = mL.document.lines;
    for (int k = 0; k < r.lenL; k++) {
      lines[row] = src[r.fromL + k];
      diffs[row] = new LineDiff(diffType);
      row++;
    }
    return row;
  }

  private InlineDocumentBuilder() {}
}
