package org.sudu.experiments.diff;

import org.sudu.experiments.BooleanConsumer;
import org.sudu.experiments.diff.LineDiff;
import org.sudu.experiments.editor.CodeLine;
import org.sudu.experiments.editor.CompactViewRange;
import org.sudu.experiments.editor.CpxDiff;
import org.sudu.experiments.editor.Diff;
import org.sudu.experiments.editor.EditorComponent;
import org.sudu.experiments.editor.EditorUi;
import org.sudu.experiments.editor.FontApi1;
import org.sudu.experiments.editor.InlineDocumentBuilder;
import org.sudu.experiments.editor.Model;
import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
import org.sudu.experiments.editor.worker.diff.DiffRange;
import org.sudu.experiments.editor.worker.diff.DiffUtils;
import org.sudu.experiments.math.V2i;
import org.sudu.experiments.ui.window.ViewArray;
import org.sudu.experiments.ui.window.WindowManager;

import java.util.Arrays;
import java.util.function.IntConsumer;

class InlineDiffRootView extends ViewArray {

  final EditorUi ui;
  final EditorComponent editor;
  final Model synthetic = new Model();

  Model leftModel, rightModel;
  DiffInfo diffInfo;
  InlineDocumentBuilder.Result lastBuild;

  // Independent read-only flags per side. Defaults to fully read-only,
  // matching the v1 behavior of the inline view.
  boolean leftReadonly = true;
  boolean rightReadonly = true;

  IntConsumer onAcceptHunk;
  IntConsumer onRejectHunk;
  Runnable onDocumentSizeChange;
  Runnable onRefresh;

  boolean compactViewRequest;
  static final int compactContextLines = 3;

  boolean firstDiffRevealed;
  boolean disposed;

  // Set while we are applying a synthetic edit onto an origin Document, so
  // we ignore the cascading updateModelOnDiff event the origin doc emits.
  private boolean applyingToOrigin;

  InlineDiffRootView(WindowManager wm, boolean disableParser) {
    ui = new EditorUi(wm);
    editor = new EditorComponent(ui);
    editor.setDisableParser(disableParser);
    editor.highlightResolveError(false);
    editor.setModel(synthetic);
    editor.setReadonlyRowPredicate(this::isRowReadonly);
    editor.setUpdateModelOnDiffListener(this::onSyntheticDiff);
    recomputeEditorReadonly();
    setViews(editor);
  }

  @Override
  protected void layoutViews() {
    views[0].setPosition(pos, size, dpr);
  }

  @Override
  public void dispose() {
    disposed = true;
    super.dispose();
  }

  public Model getLeftModel() { return leftModel; }
  public Model getRightModel() { return rightModel; }

  public void setReadonly(boolean readonly) {
    setReadonly(readonly, readonly);
  }

  public void setReadonly(boolean leftReadonly, boolean rightReadonly) {
    this.leftReadonly = leftReadonly;
    this.rightReadonly = rightReadonly;
    recomputeEditorReadonly();
    if (lastBuild != null) {
      // Rebuild merge-button bindings so Accept/Reject reflect new flags.
      installMergeButtons(lastBuild);
    }
  }

  private void recomputeEditorReadonly() {
    editor.readonly = leftReadonly && rightReadonly;
  }

  // Per-row readonly predicate. Rows from the left document are gated by
  // leftReadonly; rows from the right by rightReadonly. Mixed-side
  // operations are also blocked: when the caret sits on a row of one
  // side, rows from the other side are treated as read-only so that
  // selection-spanning edits and backspace/delete merges across the
  // boundary are rejected instead of corrupting the wrong origin doc.
  private boolean isRowReadonly(int row) {
    if (lastBuild == null) return false;
    int[] side = lastBuild.originSide;
    if (row < 0 || row >= side.length) return false;
    boolean locked = side[row] == InlineDocumentBuilder.SIDE_LEFT ? leftReadonly : rightReadonly;
    if (locked) return true;
    int caretRow = editor.caretLine();
    if (caretRow >= 0 && caretRow < side.length && caretRow != row
        && side[caretRow] != side[row]) {
      return true;
    }
    return false;
  }

  public void setDisableParser(boolean disableParser) {
    editor.setDisableParser(disableParser);
  }

  public EditorUi.FontApi fontApi() {
    return new FontApi1(editor, ui.windowManager.uiContext);
  }

  public void applyTheme(EditorColorScheme theme) {
    ui.setTheme(theme);
    int oldLineHeight = editor.lineHeight();
    editor.setTheme(theme);
    if (oldLineHeight != editor.lineHeight() && onDocumentSizeChange != null) {
      onDocumentSizeChange.run();
    }
  }

  public void setModel(Model m1, Model m2) {
    leftModel = m1;
    rightModel = m2;
    diffInfo = null;
    lastBuild = null;
    firstDiffRevealed = false;
    if (m1 != null && m2 != null) {
      sendToDiff();
    }
  }

  void sendToDiff() {
    if (leftModel == null || rightModel == null) return;
    DiffUtils.findDiffs(
        leftModel.document,
        rightModel.document,
        true,
        new int[0], new int[0],
        this::onDiffResult,
        ui.windowManager.uiContext.window.worker()
    );
  }

  private int[] docVersions() {
    return new int[]{
        leftModel.document.version(),
        rightModel.document.version()
    };
  }

  void onDiffResult(DiffInfo info, int[] versions) {
    if (disposed) return;
    if (leftModel == null || rightModel == null) return;
    if (!Arrays.equals(versions, docVersions())) return;
    diffInfo = info;
    rebuild();
  }

  private void rebuild() {
    // Snapshot caret in origin coordinates so we can restore it after the
    // synthetic doc is replaced.
    int snapSide = -1, snapOriginLine = -1;
    int snapCol = editor.caretCharPos();
    if (lastBuild != null) {
      int row = editor.caretLine();
      if (row >= 0 && row < lastBuild.originSide.length) {
        snapSide = lastBuild.originSide[row];
        snapOriginLine = lastBuild.originLine[row];
      }
    }

    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(leftModel, rightModel, diffInfo);
    lastBuild = r;
    installSyntheticLines(r);
    editor.setDiffModel(r.diffs.length == 0 ? new LineDiff[0] : r.diffs);
    installMergeButtons(r);
    applyCompactViewIfRequested(r);

    if (snapSide >= 0) {
      int newRow = findRowForOrigin(r, snapSide, snapOriginLine);
      if (newRow >= 0) {
        int maxCol = r.lines[newRow].totalStrLength;
        editor.setPosition(Math.min(snapCol, maxCol), newRow);
      }
    } else if (!firstDiffRevealed && r.hunkCount() > 0) {
      moveCaretTo(r.hunkAnchorRows[0]);
      firstDiffRevealed = true;
    }
    ui.windowManager.uiContext.window.repaint();
    if (onDocumentSizeChange != null) onDocumentSizeChange.run();
  }

  // Locates the synthetic row that maps back to the given origin (side,
  // line). For an EDITED hunk the same origin line can produce multiple
  // synthetic rows on one side — we return the first match, which is the
  // row the caret was most likely on before the rebuild.
  private int findRowForOrigin(InlineDocumentBuilder.Result r, int side, int originLine) {
    int[] s = r.originSide;
    int[] l = r.originLine;
    for (int i = 0; i < s.length; i++) {
      if (s[i] == side && l[i] == originLine) return i;
    }
    return -1;
  }

  private void applyCompactViewIfRequested(InlineDocumentBuilder.Result r) {
    if (!compactViewRequest || r.hunkCount() == 0) {
      editor.clearCompactViewModel();
      return;
    }
    CompactViewRange[] cvr = InlineDocumentBuilder.buildCompactRanges(r, compactContextLines);
    editor.setCompactViewModel(cvr, null);
  }

  public boolean isCompactedView() {
    return compactViewRequest;
  }

  public void setCompactView(boolean compact) {
    if (compactViewRequest == compact) return;
    compactViewRequest = compact;
    if (lastBuild == null) return;
    applyCompactViewIfRequested(lastBuild);
    ui.windowManager.uiContext.window.repaint();
    if (onDocumentSizeChange != null) onDocumentSizeChange.run();
  }

  private void installSyntheticLines(InlineDocumentBuilder.Result r) {
    // The Model() default has exactly one empty CodeLine; keep it if r is empty.
    if (r.lines.length > 0) {
      synthetic.document.lines = r.lines;
      synthetic.document.linePrefixSum = null;
    }
    int max = Math.max(0, synthetic.document.length() - 1);
    if (editor.caretLine() > max) {
      editor.setPosition(0, max);
    }
  }

  private void installMergeButtons(InlineDocumentBuilder.Result r) {
    BooleanConsumer[] acceptReject = new BooleanConsumer[r.hunkCount()];
    for (int i = 0; i < acceptReject.length; i++) {
      final int hunkIdx = i;
      acceptReject[i] = accept -> applyHunkAction(hunkIdx, accept);
    }
    editor.setMergeButtons(null, acceptReject, r.hunkAnchorRows, true);
  }

  // Accept: copy right-side lines onto the left model (the proposed change
  // wins). Reject: copy left-side lines onto the right model (revert).
  // No-op when the destination side is read-only.
  private void applyHunkAction(int hunkIdx, boolean accept) {
    if (lastBuild == null || diffInfo == null) return;
    if (hunkIdx < 0 || hunkIdx >= lastBuild.hunkRangeIndices.length) return;
    int rangeIdx = lastBuild.hunkRangeIndices[hunkIdx];
    if (rangeIdx < 0 || rangeIdx >= diffInfo.ranges.length) return;
    DiffRange range = diffInfo.ranges[rangeIdx];

    if (accept && !leftReadonly) {
      applyRangeAcrossSides(range, /*srcIsL=*/false);
    } else if (!accept && !rightReadonly) {
      applyRangeAcrossSides(range, /*srcIsL=*/true);
    }

    if (accept) { if (onAcceptHunk != null) onAcceptHunk.accept(hunkIdx); }
    else { if (onRejectHunk != null) onRejectHunk.accept(hunkIdx); }

    // Recompute the diff so colors and remaining hunks reflect the change.
    sendToDiff();
  }

  // Mirrors FileDiffRootView.applyDiff: copies a hunk's lines from one
  // origin document onto the other.
  private void applyRangeAcrossSides(DiffRange range, boolean srcIsL) {
    Model fromModel = srcIsL ? leftModel : rightModel;
    CodeLine[] lines = fromModel.document.getLines(range.from(srcIsL), range.to(srcIsL));

    Model toModel = srcIsL ? rightModel : leftModel;
    toModel.document.applyChange(range.from(!srcIsL), range.to(!srcIsL), lines);
  }

  // Propagates a synthetic-doc edit back to its origin document.
  private void onSyntheticDiff(EditorComponent ec, Diff diff, Boolean isUndoBoxed) {
    if (applyingToOrigin) return;
    if (lastBuild == null) return;
    int row = diff.line;
    int[] side = lastBuild.originSide;
    int[] line = lastBuild.originLine;
    if (row < 0 || row >= side.length) return;

    boolean isUndo = isUndoBoxed != null && isUndoBoxed;
    boolean netDelete = diff.isDelete ^ isUndo;

    int originLine = line[row];
    Model target = side[row] == InlineDocumentBuilder.SIDE_LEFT ? leftModel : rightModel;
    if (target == null) return;

    Diff originDiff = new Diff(originLine, diff.pos, netDelete, diff.change);
    CpxDiff cpxDiff = new CpxDiff(new Diff[]{originDiff}, null, null, null, null);

    applyingToOrigin = true;
    try {
      target.document.doCpxDiff(cpxDiff, /*isRedo=*/true);
    } finally {
      applyingToOrigin = false;
    }

    sendToDiff();
  }

  public void setOnAcceptHunk(IntConsumer cb) { onAcceptHunk = cb; }
  public void setOnRejectHunk(IntConsumer cb) { onRejectHunk = cb; }
  public void setOnDocumentSizeChange(Runnable r) { onDocumentSizeChange = r; }
  public void setOnRefresh(Runnable r) { onRefresh = r; }

  public void refresh() {
    if (onRefresh != null) onRefresh.run();
  }

  public int hunkCount() {
    return lastBuild == null ? 0 : lastBuild.hunkCount();
  }

  public boolean canNavigateUp() {
    if (lastBuild == null || lastBuild.hunkCount() == 0) return false;
    int caret = editor.caretLine();
    for (int i = lastBuild.hunkCount() - 1; i >= 0; i--) {
      if (lastBuild.hunkAnchorRows[i] < caret) return true;
    }
    return false;
  }

  public boolean canNavigateDown() {
    if (lastBuild == null || lastBuild.hunkCount() == 0) return false;
    int caret = editor.caretLine();
    for (int i = 0; i < lastBuild.hunkCount(); i++) {
      if (lastBuild.hunkAnchorRows[i] > caret) return true;
    }
    return false;
  }

  public void navigateUp() {
    if (lastBuild == null) return;
    int caret = editor.caretLine();
    for (int i = lastBuild.hunkCount() - 1; i >= 0; i--) {
      int row = lastBuild.hunkAnchorRows[i];
      if (row < caret) {
        moveCaretTo(row);
        return;
      }
    }
  }

  public void navigateDown() {
    if (lastBuild == null) return;
    int caret = editor.caretLine();
    for (int i = 0; i < lastBuild.hunkCount(); i++) {
      int row = lastBuild.hunkAnchorRows[i];
      if (row > caret) {
        moveCaretTo(row);
        return;
      }
    }
  }

  private void moveCaretTo(int row) {
    editor.setPosition(0, row);
    editor.revealLineInCenter(row);
    ui.windowManager.uiContext.window.repaint();
  }

  @Override
  public void setPosition(V2i newPos, V2i newSize, float newDpr) {
    float oldDpr = this.dpr;
    super.setPosition(newPos, newSize, newDpr);
    if (oldDpr != newDpr && onDocumentSizeChange != null) onDocumentSizeChange.run();
  }
}
