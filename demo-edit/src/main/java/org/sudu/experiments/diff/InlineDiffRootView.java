package org.sudu.experiments.diff;

import org.sudu.experiments.BooleanConsumer;
import org.sudu.experiments.diff.LineDiff;
import org.sudu.experiments.editor.CompactViewRange;
import org.sudu.experiments.editor.EditorComponent;
import org.sudu.experiments.editor.EditorUi;
import org.sudu.experiments.editor.FontApi1;
import org.sudu.experiments.editor.InlineDocumentBuilder;
import org.sudu.experiments.editor.Model;
import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
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

  IntConsumer onAcceptHunk;
  IntConsumer onRejectHunk;
  Runnable onDocumentSizeChange;
  Runnable onRefresh;

  boolean compactViewRequest;
  static final int compactContextLines = 3;

  boolean firstDiffRevealed;
  boolean disposed;

  InlineDiffRootView(WindowManager wm, boolean disableParser) {
    ui = new EditorUi(wm);
    editor = new EditorComponent(ui);
    editor.setDisableParser(disableParser);
    editor.highlightResolveError(false);
    editor.readonly = true;
    editor.setModel(synthetic);
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
    editor.readonly = readonly;
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
    InlineDocumentBuilder.Result r = InlineDocumentBuilder.build(leftModel, rightModel, diffInfo);
    lastBuild = r;
    installSyntheticLines(r);
    editor.setDiffModel(r.diffs.length == 0 ? new LineDiff[0] : r.diffs);
    installMergeButtons(r);
    applyCompactViewIfRequested(r);
    if (!firstDiffRevealed && r.hunkCount() > 0) {
      moveCaretTo(r.hunkAnchorRows[0]);
      firstDiffRevealed = true;
    }
    ui.windowManager.uiContext.window.repaint();
    if (onDocumentSizeChange != null) onDocumentSizeChange.run();
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
      acceptReject[i] = accept -> {
        if (accept) { if (onAcceptHunk != null) onAcceptHunk.accept(hunkIdx); }
        else { if (onRejectHunk != null) onRejectHunk.accept(hunkIdx); }
      };
    }
    editor.setMergeButtons(null, acceptReject, r.hunkAnchorRows, true);
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
