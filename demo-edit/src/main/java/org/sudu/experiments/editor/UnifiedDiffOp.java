package org.sudu.experiments.editor;

import org.sudu.experiments.diff.DiffTypes;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
import org.sudu.experiments.editor.worker.diff.DiffRange;
import org.sudu.experiments.math.ArrayOp;

import java.util.Arrays;

interface UnifiedDiffOp {
  int defaultContextLines = 3;

  class Mapping {
    public int[] docLines = new int[0];
    public boolean[] rightSide = new boolean[0];
    public int[] leftLineNumbers = new int[0];
    public int[] rightLineNumbers = new int[0];
    public byte[] viewLineTypes = new byte[0];
    public int[] actionLines = new int[0];
    public DiffRange[] actionRanges = new DiffRange[0];

    public int length() {
      return docLines.length;
    }
  }

  static int unifiedSize(DiffRange[] ranges) {
    int size = 0;
    for (DiffRange range : ranges) {
      switch (range.type) {
        case DiffTypes.DEFAULT, DiffTypes.INSERTED -> size += range.lenR;
        case DiffTypes.DELETED -> size += range.lenL;
        case DiffTypes.EDITED -> size += range.lenL + range.lenR;
      }
    }
    return size;
  }

  static Mapping buildMapping(DiffInfo diffInfo, boolean compact) {
    Mapping mapping = new Mapping();
    int size = compact
        ? unifiedSize(diffInfo.ranges, defaultContextLines)
        : unifiedSize(diffInfo.ranges);
    mapping.docLines = new int[size];
    mapping.rightSide = new boolean[size];
    mapping.leftLineNumbers = new int[size];
    mapping.rightLineNumbers = new int[size];
    mapping.viewLineTypes = new byte[size];
    ActionBuilder actions = new ActionBuilder(diffInfo.ranges);
    buildDocIndex(
        diffInfo.ranges,
        mapping.docLines,
        mapping.rightSide,
        mapping.leftLineNumbers,
        mapping.rightLineNumbers,
        mapping.viewLineTypes,
        actions,
        compact ? defaultContextLines : -1);
    mapping.actionLines = actions.lines();
    mapping.actionRanges = actions.ranges();
    return mapping;
  }

  static int unifiedSize(DiffRange[] ranges, int compactContext) {
    IntRef size = new IntRef();
    forVisibleRanges(ranges, compactContext, (_range, _fromL, lenL, _fromR, lenR) ->
        size.value += unifiedSize(lenL, lenR, _range.type));
    return size.value;
  }

  static void buildDocIndex(
      DiffRange[] ranges,
      int[] lines, boolean[] index,
      int[] lLn, int[] rLn
  ) {
    buildDocIndex(ranges, lines, index, lLn, rLn, null, null, -1);
  }

  static void buildDocIndex(
      DiffRange[] ranges,
      int[] lines, boolean[] index,
      int[] lLn, int[] rLn,
      byte[] viewLineTypes,
      ActionBuilder actions,
      int compactContext
  ) {
    IntRef pos = new IntRef();
    forVisibleRanges(ranges, compactContext, (range, fromL, lenL, fromR, lenR) -> {
      if (actions != null && range.type != DiffTypes.DEFAULT) {
        actions.add(pos.value, range);
      }
      switch (range.type) {
        case DiffTypes.DEFAULT ->
            pos.value = addDefaultRange(fromL, lenL, fromR, lenR, lines, index, lLn, rLn,
                viewLineTypes, pos.value);
        case DiffTypes.DELETED ->
            pos.value = addLeftRange(fromL, lenL, lines, index, lLn, rLn,
                viewLineTypes, DiffTypes.DELETED, pos.value);
        case DiffTypes.INSERTED ->
            pos.value = addRightRange(fromR, lenR, lines, index, lLn, rLn,
                viewLineTypes, DiffTypes.INSERTED, pos.value);
        case DiffTypes.EDITED -> {
          pos.value = addLeftRange(fromL, lenL, lines, index, lLn, rLn,
              viewLineTypes, DiffTypes.DELETED, pos.value);
          pos.value = addRightRange(fromR, lenR, lines, index, lLn, rLn,
              viewLineTypes, DiffTypes.INSERTED, pos.value);
        }
      }
    });
  }

  private static int addDefaultRange(
      int fromL, int lenL,
      int fromR, int lenR,
      int[] lines, boolean[] index,
      int[] lLn, int[] rLn,
      byte[] viewLineTypes,
      int pos
  ) {
    int end = pos + lenR;
    Arrays.fill(index, pos, end, true);
    ArrayOp.fillSequence(lines, pos, end, fromR);
    ArrayOp.fillSequence(rLn, pos, end, fromR);
    ArrayOp.fillSequence(lLn, pos, end, fromL);
    if (viewLineTypes != null) Arrays.fill(viewLineTypes, pos, end, (byte) DiffTypes.DEFAULT);
    pos = end;
    return pos;
  }

  static int addRightRange(
      DiffRange range, int[] lines, boolean[] index,
      int[] lLn, int[] rLn, int pos
  ) {
    return addRightRange(range.fromR, range.lenR, lines, index, lLn, rLn,
        null, DiffTypes.INSERTED, pos);
  }

  static int addRightRange(
      int fromR, int lenR,
      int[] lines, boolean[] index,
      int[] lLn, int[] rLn,
      byte[] viewLineTypes,
      int lineType,
      int pos
  ) {
    int end = pos + lenR;
    Arrays.fill(index, pos, end, true);
    ArrayOp.fillSequence(lines, pos, end, fromR);
    ArrayOp.fillSequence(rLn, pos, end, fromR);
    Arrays.fill(lLn, pos, end, -1);
    if (viewLineTypes != null) Arrays.fill(viewLineTypes, pos, end, (byte) lineType);
    return end;
  }

  static int addLeftRange(
      DiffRange range, int[] lines, boolean[] index,
      int[] lLn, int[] rLn, int pos
  ) {
    return addLeftRange(range.fromL, range.lenL, lines, index, lLn, rLn,
        null, DiffTypes.DELETED, pos);
  }

  static int addLeftRange(
      int fromL, int lenL,
      int[] lines, boolean[] index,
      int[] lLn, int[] rLn,
      byte[] viewLineTypes,
      int lineType,
      int pos
  ) {
    int end = pos + lenL;
    Arrays.fill(index, pos, end, false);
    ArrayOp.fillSequence(lines, pos, end, fromL);
    ArrayOp.fillSequence(lLn, pos, end, fromL);
    Arrays.fill(rLn, pos, end, -1);
    if (viewLineTypes != null) Arrays.fill(viewLineTypes, pos, end, (byte) lineType);
    return end;
  }

  static boolean applyReviewAction(Model left, Model right, DiffRange range, boolean accept) {
    boolean sourceLeft = !accept;
    boolean targetLeft = accept;
    Model source = sourceLeft ? left : right;
    Model target = targetLeft ? left : right;
    int sourceFrom = range.from(sourceLeft);
    int sourceTo = range.to(sourceLeft);
    int targetFrom = range.from(targetLeft);
    int targetTo = range.to(targetLeft);
    int version = target.document.version();
    target.document.applyChange(
        targetFrom,
        targetTo,
        source.document.getLines(sourceFrom, sourceTo));
    return target.document.version() != version;
  }

  private static int unifiedSize(int lenL, int lenR, int type) {
    return switch (type) {
      case DiffTypes.DEFAULT, DiffTypes.INSERTED -> lenR;
      case DiffTypes.DELETED -> lenL;
      case DiffTypes.EDITED -> lenL + lenR;
      default -> 0;
    };
  }

  private static void forVisibleRanges(
      DiffRange[] ranges,
      int compactContext,
      RangeConsumer consumer
  ) {
    boolean compact = compactContext >= 0 && hasDiffs(ranges);
    for (int i = 0; i < ranges.length; i++) {
      DiffRange range = ranges[i];
      if (!compact || range.type != DiffTypes.DEFAULT) {
        consumer.accept(range, range.fromL, range.lenL, range.fromR, range.lenR);
        continue;
      }
      int len = range.lenR;
      boolean before = hasDiffBefore(ranges, i);
      boolean after = hasDiffAfter(ranges, i);
      if (!before && !after) {
        consumer.accept(range, range.fromL, range.lenL, range.fromR, range.lenR);
      } else if (before && after && len > compactContext * 2) {
        consumer.accept(range, range.fromL, compactContext, range.fromR, compactContext);
        consumer.accept(
            range,
            range.toL() - compactContext,
            compactContext,
            range.toR() - compactContext,
            compactContext);
      } else {
        int visible = Math.min(len, compactContext);
        if (before && !after) {
          consumer.accept(range, range.fromL, visible, range.fromR, visible);
        } else if (!before) {
          consumer.accept(
              range,
              range.toL() - visible,
              visible,
              range.toR() - visible,
              visible);
        } else {
          consumer.accept(range, range.fromL, len, range.fromR, len);
        }
      }
    }
  }

  private static boolean hasDiffs(DiffRange[] ranges) {
    for (DiffRange range : ranges)
      if (range.type != DiffTypes.DEFAULT)
        return true;
    return false;
  }

  private static boolean hasDiffBefore(DiffRange[] ranges, int index) {
    for (int i = index - 1; i >= 0; i--)
      if (ranges[i].type != DiffTypes.DEFAULT)
        return true;
    return false;
  }

  private static boolean hasDiffAfter(DiffRange[] ranges, int index) {
    for (int i = index + 1; i < ranges.length; i++)
      if (ranges[i].type != DiffTypes.DEFAULT)
        return true;
    return false;
  }

  interface RangeConsumer {
    void accept(DiffRange range, int fromL, int lenL, int fromR, int lenR);
  }

  class IntRef {
    int value;
  }

  class ActionBuilder {
    private final int[] lines;
    private final DiffRange[] ranges;
    private int size;

    ActionBuilder(DiffRange[] sourceRanges) {
      int n = 0;
      for (DiffRange range : sourceRanges)
        if (range.type != DiffTypes.DEFAULT)
          n++;
      lines = new int[n];
      ranges = new DiffRange[n];
    }

    void add(int line, DiffRange range) {
      lines[size] = line;
      ranges[size] = range;
      size++;
    }

    int[] lines() {
      return Arrays.copyOf(lines, size);
    }

    DiffRange[] ranges() {
      return Arrays.copyOf(ranges, size);
    }
  }
}
