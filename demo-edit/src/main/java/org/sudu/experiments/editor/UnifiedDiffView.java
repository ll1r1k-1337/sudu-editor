package org.sudu.experiments.editor;

import org.sudu.experiments.Canvas;
import org.sudu.experiments.Cursor;
import org.sudu.experiments.Disposable;
import org.sudu.experiments.WglGraphics;
import org.sudu.experiments.diff.DiffTypes;
import org.sudu.experiments.diff.LineDiff;
import org.sudu.experiments.editor.ui.colors.CodeLineColorScheme;
import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.editor.ui.colors.MergeButtonsColors;
import org.sudu.experiments.editor.worker.diff.DiffInfo;
import org.sudu.experiments.editor.worker.diff.DiffRange;
import org.sudu.experiments.editor.worker.diff.DiffUtils;
import org.sudu.experiments.input.KeyCode;
import org.sudu.experiments.input.KeyEvent;
import org.sudu.experiments.input.MouseEvent;
import org.sudu.experiments.input.MouseListener;
import org.sudu.experiments.math.Color;
import org.sudu.experiments.math.Numbers;
import org.sudu.experiments.math.V2i;
import org.sudu.experiments.ui.Focusable;
import org.sudu.experiments.ui.ScrollBar;
import org.sudu.experiments.ui.SetCursor;
import org.sudu.experiments.ui.UiContext;
import org.sudu.experiments.ui.window.View;

import java.util.Objects;
import java.util.function.Consumer;

public class UnifiedDiffView extends View
    implements Focusable, Model.EditorToModel {

  static final float vLineWDp = 1;
  static final float vLineTextOffsetDp = 10;
  static final float scrollBarWidthDp = 12;

  final UiContext context;
  final ClrContext lrContext;
  final LineNumbersComponent lineNumbers1 = new LineNumbersComponent();
  final LineNumbersComponent lineNumbers2 = new LineNumbersComponent();
  final ScrollBar vScroll = new ScrollBar();
  final ScrollBar hScroll = new ScrollBar();
  final V2i tmpSize = new V2i();

  MergeButtons acceptButtons, rejectButtons;
  MergeButtonsColors mergeButtonColors;

  EditorColorScheme colors;
  CodeLineColorScheme codeLineColors;
  float fontVirtualSize = EditorConst.DEFAULT_FONT_SIZE;
  String fontFamilyName = EditorConst.FONT;

  Model model1 = new Model(), model2 = new Model();
  DiffInfo diffInfo;
  UnifiedDiffOp.Mapping mapping = new UnifiedDiffOp.Mapping();
  CodeLines docWrapper = docWrapper();

  boolean leftReadonly, rightReadonly;
  boolean compactView;
  boolean firstDiffRevealed;
  boolean hasFocus;
  final boolean disableParser;

  int vScrollPos, hScrollPos;
  int vLineX, vLineW, scrollBarWidth;
  int vLineTextOffset, textBaseX, textViewWidth;
  int fullWidth;
  int currentRangeIndex = -1;

  CodeLineRenderer[] lines = new CodeLineRenderer[0];
  int firstViewLine, lastViewLine;
  private int frameId;
  private Runnable onDocumentSizeChange;

  public UnifiedDiffView(UiContext uiContext) {
    this(uiContext, EditorConst.DEFAULT_DISABLE_PARSER);
  }

  public UnifiedDiffView(UiContext uiContext, boolean disableParser) {
    context = uiContext;
    this.disableParser = disableParser;
    lrContext = new ClrContext(uiContext.cleartype);
  }

  @Override
  public void dispose() {
    detachModels(null, null);
    CodeLineRenderer.disposeLines(lines);
    acceptButtons = Disposable.dispose(acceptButtons);
    rejectButtons = Disposable.dispose(rejectButtons);
    lrContext.dispose();
    lineNumbers1.dispose();
    lineNumbers2.dispose();
  }

  public void setTheme(EditorColorScheme theme) {
    colors = theme;
    codeLineColors = colors.editorCodeLineScheme();
    mergeButtonColors = colors.codeDiffMergeButtons();
    vScroll.setColor(theme.editor.scrollBarLine, theme.editor.scrollBarBg);
    hScroll.setColor(theme.editor.scrollBarLine, theme.editor.scrollBarBg);
    if (!theme.editorFont.equals(fontFamilyName, fontVirtualSize)) {
      changeFont(theme.editorFont.familyName, theme.editorFont.size);
    }
    if (acceptButtons != null) acceptButtons.setColors(mapping.viewLineTypes);
    if (rejectButtons != null) rejectButtons.setColors(mapping.viewLineTypes);
  }

  public void changeFont(String name, float virtualSize) {
    if (context.dpr != 0) {
      int oldLineHeight = lrContext.lineHeight;
      doChangeFont(name, virtualSize);
      if (oldLineHeight != lrContext.lineHeight) fireDocumentSizeChange();
      context.window.repaint();
    }
    fontVirtualSize = virtualSize;
    fontFamilyName = name;
  }

  private void doChangeFont(String name, float virtualSize) {
    float newPixelFontSize = virtualSize * dpr;
    float oldPixelFontSize = lrContext.fontSize();
    if (newPixelFontSize != oldPixelFontSize || !Objects.equals(name, fontFamilyName)) {
      lineNumbers1.dispose();
      lineNumbers2.dispose();
      invalidateFont();
      setFont(name, newPixelFontSize);
      updateLineNumbersFont();
      updateButtonsFont();
      internalLayout();
    }
  }

  private void setFont(String name, float pixelSize) {
    lrContext.setFonts(name, pixelSize, context.graphics);
    lrContext.setLineHeight(EditorConst.LINE_HEIGHT_MULTI, context.graphics);
  }

  private void updateLineNumbersFont() {
    lineNumbers1.setFont(lrContext);
    lineNumbers2.setFont(lrContext);
  }

  private void updateButtonsFont() {
    if (lrContext.lineHeight == 0) return;
    if (acceptButtons != null)
      acceptButtons.setFont(lrContext.lineHeight, lrContext.fonts[CodeElement.bold]);
    if (rejectButtons != null)
      rejectButtons.setFont(lrContext.lineHeight, lrContext.fonts[CodeElement.bold]);
  }

  private void invalidateFont() {
    CodeLineRenderer.disposeLines(lines);
    lines = new CodeLineRenderer[0];
    model1.document.invalidateFont();
    model2.document.invalidateFont();
  }

  @Override
  protected void onTextRenderingSettingsChange() {
    lrContext.enableCleartype(context.cleartype, context.graphics);
    CodeLineRenderer.makeContentDirty(lines);
    lineNumbers1.dispose();
    lineNumbers2.dispose();
    updateLineNumbersFont();
    if (acceptButtons != null) acceptButtons.onTextRenderingSettingsChange();
    if (rejectButtons != null) rejectButtons.onTextRenderingSettingsChange();
  }

  private void internalLayout() {
    if (colors == null || dpr == 0) return;

    vLineTextOffset = toPx(vLineTextOffsetDp);
    vLineW = toPx(vLineWDp);
    scrollBarWidth = toPx(scrollBarWidthDp);

    int numDigits1 = Numbers.numDecimalDigits(model1.document.length());
    int numDigits2 = Numbers.numDecimalDigits(model2.document.length());
    Canvas mCanvas = context.graphics.mCanvas;
    int lnWidth1 = lineNumbers1.measureDigits(numDigits1, mCanvas, dpr);
    int lnWidth2 = lineNumbers2.measureDigits(numDigits2, mCanvas, dpr);
    int acceptWidth = buttonWidth(acceptButtons);
    int rejectWidth = buttonWidth(rejectButtons);

    int x = pos.x;
    lineNumbers1.setPosition(x, pos.y, lnWidth1, size.y, dpr);
    x += lnWidth1;
    lineNumbers2.setPosition(x, pos.y, lnWidth2, size.y, dpr);
    x += lnWidth2;
    if (acceptButtons != null) {
      acceptButtons.setPosition(x, pos.y, acceptWidth, size.y, dpr);
      x += acceptWidth;
    }
    if (rejectButtons != null) {
      rejectButtons.setPosition(x, pos.y, rejectWidth, size.y, dpr);
      x += rejectWidth;
    }

    vLineX = x;
    textBaseX = x - pos.x + vLineW + vLineTextOffset;
    textViewWidth = Math.max(1, size.x - textBaseX - scrollBarWidth);
    clampScrollPositions();
  }

  private int buttonWidth(MergeButtons buttons) {
    return buttons == null || lrContext.lineHeight == 0
        ? 0 : buttons.measure(lrContext.fonts[CodeElement.bold], context.graphics.mCanvas, dpr);
  }

  @Override
  public void setPosition(V2i pos, V2i size, float newDpr) {
    float oldDpr = dpr;
    super.setPosition(pos, size, newDpr);
    if (oldDpr != newDpr) {
      doChangeFont(fontFamilyName, fontVirtualSize);
      lrContext.setDpr(dpr);
      fireDocumentSizeChange();
    } else {
      internalLayout();
    }
  }

  public void setModel(Model left, Model right) {
    if (left == null || right == null) throw new IllegalArgumentException("model is null");
    Model old1 = model1, old2 = model2;
    model1 = left;
    model2 = right;
    detachModelIfUnused(old1, left, right);
    detachModelIfUnused(old2, left, right);
    attachModel(left);
    if (right != left) attachModel(right);
    firstDiffRevealed = false;
    currentRangeIndex = -1;
    clearDiffModel();
    sendToDiff(true);
  }

  public void setModel(Model model, int index) {
    setModel(index == 0 ? model : model1, index == 0 ? model2 : model);
  }

  public Model getLeftModel() {
    return model1;
  }

  public Model getRightModel() {
    return model2;
  }

  public void setReadonly(boolean leftReadonly, boolean rightReadonly) {
    this.leftReadonly = leftReadonly;
    this.rightReadonly = rightReadonly;
    updateReviewButtons();
    internalLayout();
    context.window.repaint();
  }

  public void setCompactView(boolean compact) {
    if (compactView == compact) return;
    compactView = compact;
    rebuildMapping();
    revealCurrentRange();
    fireDocumentSizeChange();
    context.window.repaint();
  }

  public boolean isCompactedView() {
    return compactView;
  }

  public void refresh() {
    sendToDiff(false);
  }

  public void setOnDocumentSizeChange(Runnable onDocumentSizeChange) {
    this.onDocumentSizeChange = onDocumentSizeChange;
  }

  public int getNumLines() {
    return mapping.length();
  }

  public int lineHeight() {
    return lrContext.lineHeight;
  }

  private void clearDiffModel() {
    diffInfo = null;
    mapping = new UnifiedDiffOp.Mapping();
    model1.diffModel = null;
    model2.diffModel = null;
    lineNumbers1.setColors(null);
    lineNumbers2.setColors(null);
    updateReviewButtons();
    fireDocumentSizeChange();
  }

  private void sendToDiff(boolean cmpOnlyLines) {
    if (model1 == null || model2 == null) return;
    DiffUtils.findDiffs(
        model1.document,
        model2.document,
        cmpOnlyLines,
        new int[0],
        new int[0],
        this::setDiffModel,
        context.window.worker());
  }

  public void setDiffModel(DiffInfo diffInfo, int[] versions) {
    if (versions[0] != model1.document.version()
        || versions[1] != model2.document.version()) return;
    LineDiff.replaceEdited(diffInfo.lineDiffsL, DiffTypes.DELETED);
    LineDiff.replaceEdited(diffInfo.lineDiffsR, DiffTypes.INSERTED);
    this.diffInfo = diffInfo;
    model1.diffModel = diffInfo.lineDiffsL;
    model2.diffModel = diffInfo.lineDiffsR;
    lineNumbers1.setColors(LineDiff.colors(model1.diffModel));
    lineNumbers2.setColors(LineDiff.colors(model2.diffModel));
    rebuildMapping();
    if (!firstDiffRevealed) revealFirstDiff();
    fireDocumentSizeChange();
    context.window.repaint();
  }

  private void rebuildMapping() {
    mapping = diffInfo == null
        ? new UnifiedDiffOp.Mapping()
        : UnifiedDiffOp.buildMapping(diffInfo, compactView);
    updateReviewButtons();
    clampScrollPositions();
    internalLayout();
  }

  private void updateReviewButtons() {
    if (diffInfo == null || mapping.actionLines.length == 0 || leftReadonly) {
      acceptButtons = Disposable.dispose(acceptButtons);
    } else {
      acceptButtons = ensureButtons(
          acceptButtons, MergeButtons.iconAcceptReject(true), true);
      acceptButtons.setModel(actions(true), mapping.actionLines);
      acceptButtons.setColors(mapping.viewLineTypes);
    }

    if (diffInfo == null || mapping.actionLines.length == 0 || rightReadonly) {
      rejectButtons = Disposable.dispose(rejectButtons);
    } else {
      rejectButtons = ensureButtons(
          rejectButtons, MergeButtons.iconAcceptReject(false), false);
      rejectButtons.setModel(actions(false), mapping.actionLines);
      rejectButtons.setColors(mapping.viewLineTypes);
    }
    updateButtonsFont();
  }

  private MergeButtons ensureButtons(MergeButtons buttons, char icon, boolean accept) {
    if (buttons == null) {
      buttons = new MergeButtons();
      buttons.setIcon(icon);
    }
    return buttons;
  }

  private Runnable[] actions(boolean accept) {
    Runnable[] actions = new Runnable[mapping.actionRanges.length];
    for (int i = 0; i < actions.length; i++) {
      DiffRange range = mapping.actionRanges[i];
      actions[i] = () -> applyReviewAction(range, accept);
    }
    return actions;
  }

  private void applyReviewAction(DiffRange range, boolean accept) {
    if (UnifiedDiffOp.applyReviewAction(model1, model2, range, accept)) {
      sendToDiff(true);
      context.window.repaint();
    }
  }

  private CodeLine codeLine(int i) {
    Model model = mapping.rightSide[i] ? model2 : model1;
    return model.document.lines[mapping.docLines[i]];
  }

  private CodeLines docWrapper() {
    return new CodeLines() {
      public CodeLine line(int i) {
        return codeLine(i);
      }
    };
  }

  @Override
  public void draw(WglGraphics g) {
    if (colors == null || lrContext.lineHeight == 0) {
      super.draw(g);
      return;
    }

    frameId++;
    g.drawRect(pos.x, pos.y, size, colors.editor.bg);
    drawVLine(g);

    int nLines = getNumLines();
    if (nLines == 0) {
      layoutScrollbars();
      drawScrollBars(g);
      return;
    }

    int lineHeight = lrContext.lineHeight;
    firstViewLine = Math.min(vScrollPos / lineHeight, nLines - 1);
    lastViewLine = Math.min((vScrollPos + size.y - 1) / lineHeight + 1, nLines);

    int cacheLines = Numbers.iDivRoundUp(size.y, lineHeight) + EditorConst.MIN_CACHE_LINES;
    if (lines.length < cacheLines) {
      lines = CodeLineRenderer.allocRenderLines(
          cacheLines, lines, lrContext,
          firstViewLine, lastViewLine, docWrapper);
    }

    drawLineNumbers(g, firstViewLine, lastViewLine);
    drawCodeLines(g, firstViewLine, lastViewLine);
    drawReviewButtons(g, firstViewLine, lastViewLine);
    clampScrollPositions();
    layoutScrollbars();
    drawScrollBars(g);
  }

  private void drawCodeLines(WglGraphics g, int firstLine, int lastLine) {
    int lineHeight = lrContext.lineHeight;
    int rightPadding = toPx(EditorConst.RIGHT_PADDING);
    int measuredWidth = 0;
    tmpSize.set(textViewWidth, size.y);
    g.enableScissor(pos.x + textBaseX, pos.y, tmpSize);
    for (int i = firstLine; i < lastLine; i++) {
      Model model = mapping.rightSide[i] ? model2 : model1;
      LineDiff[] diffModel = model.diffModel;
      int docLine = mapping.docLines[i];
      CodeLine cLine = model.document.lines[docLine];
      CodeLineRenderer line = lineRenderer(i);
      int y = lineHeight * i - vScrollPos;
      int lineMeasure = line.updateTexture(
          cLine, g, lineHeight, textViewWidth, hScrollPos, i, i % lines.length);
      measuredWidth = Math.max(measuredWidth, lineMeasure + rightPadding);
      drawLineBackground(g, y, mapping.viewLineTypes[i]);
      LineDiff diff = diffModel == null || docLine >= diffModel.length
          ? null : diffModel[docLine];
      line.draw(
          pos.y + y, pos.x + textBaseX, g,
          textViewWidth, lineHeight, hScrollPos,
          codeLineColors, null, model.definition, model.usages,
          false, null, null, diff);
    }
    g.disableScissor();
    fullWidth = measuredWidth;
  }

  private void drawLineBackground(WglGraphics g, int y, int type) {
    tmpSize.set(textViewWidth, lrContext.lineHeight);
    Color bg = colors.codeDiffBg.getDiffColor(type, colors.editor.bg);
    g.drawRect(pos.x + textBaseX, pos.y + y, tmpSize, bg);
  }

  private void drawReviewButtons(WglGraphics g, int firstLine, int lastLine) {
    if (mergeButtonColors == null) return;
    int inclusiveLast = lastLine - 1;
    if (acceptButtons != null) {
      acceptButtons.setScrollPos(vScrollPos);
      acceptButtons.draw(
          firstLine, inclusiveLast, -1,
          g, mergeButtonColors, lrContext, null);
    }
    if (rejectButtons != null) {
      rejectButtons.setScrollPos(vScrollPos);
      rejectButtons.draw(
          firstLine, inclusiveLast, -1,
          g, mergeButtonColors, lrContext, null);
    }
  }

  private void drawVLine(WglGraphics g) {
    tmpSize.y = size.y;
    tmpSize.x = vLineW;
    g.drawRect(vLineX, pos.y, tmpSize, colors.editor.numbersVLine);
  }

  private void drawLineNumbers(WglGraphics g, int firstLine, int lastLine) {
    drawLineNumbers(g, firstLine, lastLine, lineNumbers1, mapping.leftLineNumbers,
        colors.codeDiffBg.insertedColor);
    drawLineNumbers(g, firstLine, lastLine, lineNumbers2, mapping.rightLineNumbers,
        colors.codeDiffBg.deletedColor);
  }

  private void drawLineNumbers(
      WglGraphics g, int firstLine, int lastLine,
      LineNumbersComponent lineNumbers, int[] values, Color bg
  ) {
    int lineHeight = lrContext.lineHeight;
    lineNumbers.beginDraw(g, frameId);
    for (int i = firstLine; i < lastLine; i++) {
      int y = lineHeight * i - vScrollPos;
      if (values[i] >= 0) {
        lineNumbers.drawRange(y, values[i], values[i] + 1, g, colors);
      } else {
        lineNumbers.drawEmptyLines(y, y + lineHeight, g, bg);
      }
    }
    int bottom = lastLine * lineHeight - vScrollPos;
    if (bottom < lineNumbers.size.y)
      lineNumbers.drawEmptyLines(bottom, lineNumbers.size.y, g, colors.editor.bg);
    lineNumbers.endDraw(g);
  }

  private CodeLineRenderer lineRenderer(int i) {
    return lines[i % lines.length];
  }

  private void layoutScrollbars() {
    vScroll.layoutVertical(
        vScrollPos,
        pos.y,
        size.y,
        virtualHeight(),
        pos.x + size.x,
        scrollBarWidth);
    hScroll.layoutHorizontal(
        hScrollPos,
        pos.x + textBaseX,
        textViewWidth,
        Math.max(fullWidth, textViewWidth),
        pos.y + size.y,
        scrollBarWidth);
  }

  private void drawScrollBars(WglGraphics g) {
    boolean vv = vScroll.visible();
    boolean hv = hScroll.visible();
    if (vv || hv) {
      g.enableBlend(true);
      if (vv) vScroll.drawBg(g);
      if (hv) hScroll.drawBg(g);
      if (vv) vScroll.drawButton(g);
      if (hv) hScroll.drawButton(g);
      g.enableBlend(false);
    }
  }

  private int virtualHeight() {
    return getNumLines() * lrContext.lineHeight;
  }

  private int maxVScrollPos() {
    return Math.max(virtualHeight() - size.y, 0);
  }

  private int maxHScrollPos() {
    return Math.max(fullWidth - textViewWidth, 0);
  }

  private void setScrollPosY(int pos) {
    if (setVScrollPosSilent(pos)) context.window.repaint();
  }

  private void setScrollPosX(int pos) {
    if (setHScrollPosSilent(pos)) context.window.repaint();
  }

  private boolean setVScrollPosSilent(int pos) {
    int newPos = clampScrollPos(pos, maxVScrollPos());
    boolean changed = newPos != vScrollPos;
    if (changed) vScrollPos = newPos;
    return changed;
  }

  private boolean setHScrollPosSilent(int pos) {
    int newPos = clampScrollPos(pos, maxHScrollPos());
    boolean changed = newPos != hScrollPos;
    if (changed) hScrollPos = newPos;
    return changed;
  }

  private void clampScrollPositions() {
    setVScrollPosSilent(vScrollPos);
    setHScrollPosSilent(hScrollPos);
  }

  static int clampScrollPos(int pos, int maxScrollPos) {
    return Math.min(Math.max(0, pos), maxScrollPos);
  }

  Consumer<ScrollBar.Event> vScrollHandler =
      event -> setScrollPosY(event.getPosition(maxVScrollPos()));

  Consumer<ScrollBar.Event> hScrollHandler =
      event -> setScrollPosX(event.getPosition(maxHScrollPos()));

  @Override
  protected boolean onScroll(MouseEvent event, float dX, float dY) {
    int changeY = Numbers.iRnd(lrContext.lineHeight * 4 * dY / 150);
    int changeX = Numbers.iRnd(dX);
    if (changeY != 0) setScrollPosY(vScrollPos + changeY);
    if (changeX != 0) setScrollPosX(hScrollPos + changeX);
    return true;
  }

  @Override
  protected Consumer<MouseEvent> onMouseDown(MouseEvent event, int button) {
    if (button != MouseListener.MOUSE_BUTTON_LEFT) return MouseListener.Static.emptyConsumer;
    if (!context.isFocused(this)) context.setFocus(this);
    Consumer<MouseEvent> lock;
    if (acceptButtons != null) {
      lock = acceptButtons.onMouseDown(event, button, context.windowCursor);
      if (lock != null) return lock;
    }
    if (rejectButtons != null) {
      lock = rejectButtons.onMouseDown(event, button, context.windowCursor);
      if (lock != null) return lock;
    }
    lock = vScroll.onMouseDown(event.position, vScrollHandler, true);
    if (lock != null) return lock;
    lock = hScroll.onMouseDown(event.position, hScrollHandler, false);
    return lock != null ? lock : MouseListener.Static.emptyConsumer;
  }

  @Override
  protected boolean onMouseUp(MouseEvent event, int button) {
    boolean result = false;
    if (acceptButtons != null) result |= acceptButtons.onMouseUp(event, button);
    if (rejectButtons != null) result |= rejectButtons.onMouseUp(event, button);
    return result;
  }

  @Override
  public void onMouseMove(MouseEvent event, SetCursor setCursor) {
    boolean handled = vScroll.onMouseMove(event.position, setCursor)
        | hScroll.onMouseMove(event.position, setCursor);
    if (acceptButtons != null)
      handled |= acceptButtons.onMouseMove(event, setCursor);
    if (rejectButtons != null)
      handled |= rejectButtons.onMouseMove(event, setCursor);
    if (!handled && hitTest(event.position)) {
      setCursor.set(Cursor.text);
    }
  }

  @Override
  protected void onMouseLeaveWindow() {
    if (acceptButtons != null) acceptButtons.onMouseLeave();
    if (rejectButtons != null) rejectButtons.onMouseLeave();
  }

  @Override
  public boolean onKeyPress(KeyEvent event) {
    if (event.keyCode == KeyCode.F7 && event.singlePress()) {
      if (event.shift) navigateUp();
      else navigateDown();
      return true;
    }
    return false;
  }

  public boolean canNavigateDown() {
    if (diffInfo == null) return false;
    int from = currentRangeIndex < 0 ? -1 : currentRangeIndex;
    for (int i = from + 1; i < diffInfo.ranges.length; i++)
      if (diffInfo.ranges[i].type != DiffTypes.DEFAULT)
        return true;
    return false;
  }

  public void navigateDown() {
    if (diffInfo == null) return;
    int from = currentRangeIndex < 0 ? -1 : currentRangeIndex;
    for (int i = from + 1; i < diffInfo.ranges.length; i++) {
      if (diffInfo.ranges[i].type != DiffTypes.DEFAULT) {
        setCurrentRange(i);
        return;
      }
    }
  }

  public boolean canNavigateUp() {
    if (diffInfo == null || currentRangeIndex < 0) return false;
    for (int i = currentRangeIndex - 1; i >= 0; i--)
      if (diffInfo.ranges[i].type != DiffTypes.DEFAULT)
        return true;
    return false;
  }

  public void navigateUp() {
    if (diffInfo == null) return;
    int from = currentRangeIndex < 0 ? diffInfo.ranges.length : currentRangeIndex;
    for (int i = from - 1; i >= 0; i--) {
      if (diffInfo.ranges[i].type != DiffTypes.DEFAULT) {
        setCurrentRange(i);
        return;
      }
    }
  }

  private void revealFirstDiff() {
    if (diffInfo == null) return;
    for (int i = 0; i < diffInfo.ranges.length; i++) {
      if (diffInfo.ranges[i].type != DiffTypes.DEFAULT) {
        setCurrentRange(i);
        firstDiffRevealed = true;
        return;
      }
    }
  }

  private void setCurrentRange(int rangeIndex) {
    currentRangeIndex = rangeIndex;
    revealCurrentRange();
  }

  private void revealCurrentRange() {
    if (currentRangeIndex < 0 || diffInfo == null || lrContext.lineHeight == 0) return;
    int viewLine = viewLineForRange(diffInfo.ranges[currentRangeIndex]);
    if (viewLine < 0) return;
    int pos = lrContext.lineHeight * (viewLine - size.y / (lrContext.lineHeight * 2));
    setScrollPosY(pos);
    context.window.repaint();
  }

  private int viewLineForRange(DiffRange range) {
    for (int i = 0; i < mapping.actionRanges.length; i++)
      if (mapping.actionRanges[i] == range)
        return mapping.actionLines[i];
    return -1;
  }

  @Override
  public void onFocusGain() {
    hasFocus = true;
  }

  @Override
  public void onFocusLost() {
    hasFocus = false;
  }

  @Override
  public void useDocumentHighlightProvider(int line, int column) {}

  @Override
  public void fireFileLexed() {
    sendToDiff(false);
  }

  @Override
  public void fireFileIterativeParsed(int start, int stop) {
    sendToDiff(false);
  }

  @Override
  public void updateModelOnDiff(Diff diff, boolean isUndo) {}

  @Override
  public void onDiffMade() {
    clearDiffModel();
    sendToDiff(false);
    context.window.repaint();
  }

  @Override
  public boolean isDisableParser() {
    return disableParser;
  }

  @Override
  public CodeLineColorScheme getColorScheme() {
    return codeLineColors;
  }

  @Override
  public UndoBuffer getUndoBuffer() {
    return null;
  }

  @Override
  public void syncEditing(CpxDiff diffs, boolean isUndo) {}

  private void attachModel(Model model) {
    model.setEditor(this, context.window.worker());
  }

  private void detachModels(Model next1, Model next2) {
    detachModelIfUnused(model1, next1, next2);
    detachModelIfUnused(model2, next1, next2);
  }

  private void detachModelIfUnused(Model model, Model next1, Model next2) {
    if (model != null && model != next1 && model != next2)
      model.setEditor(null, null);
  }

  private void fireDocumentSizeChange() {
    if (onDocumentSizeChange != null) onDocumentSizeChange.run();
  }
}
