package org.sudu.experiments.diff;

import org.sudu.experiments.Debug;
import org.sudu.experiments.FileHandle;
import org.sudu.experiments.SplitInfo;
import org.sudu.experiments.editor.EditorConst;
import org.sudu.experiments.editor.UnifiedDiffView;
import org.sudu.experiments.editor.Model;
import org.sudu.experiments.editor.Uri;
import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.editor.worker.FsWorkerJobs;
import org.sudu.experiments.input.KeyCode;
import org.sudu.experiments.input.KeyEvent;
import org.sudu.experiments.math.ArrayOp;
import org.sudu.experiments.math.V2i;
import org.sudu.experiments.text.SplitText;
import org.sudu.experiments.ui.Focusable;
import org.sudu.experiments.ui.ToolWindow0;
import org.sudu.experiments.ui.ToolbarItem;
import org.sudu.experiments.ui.window.Window;
import org.sudu.experiments.ui.window.WindowManager;

import java.util.function.Supplier;

public class UnifiedDiffWindow extends ToolWindow0
    implements Focusable
{
  UnifiedDiffView rootView;
  Window window;
  Focusable focusSave;
  boolean processEsc = true;
  boolean canSelectFiles = true;

  public UnifiedDiffWindow(
      WindowManager wm,
      EditorColorScheme theme,
      Supplier<String[]> fonts
  ) {
    this(wm, theme, fonts, EditorConst.DEFAULT_DISABLE_PARSER);
  }

  public UnifiedDiffWindow(
      WindowManager wm,
      EditorColorScheme theme,
      Supplier<String[]> fonts,
      boolean disableParser
  ) {
    super(wm, theme, fonts);
    rootView = new UnifiedDiffView(wm.uiContext, disableParser);
    rootView.setTheme(theme);
    window = createWindow(rootView, 40);
    window.onFocus(this::onFocus);
    window.onBlur(this::onBlur);
    windowManager.addWindow(window);

    windowManager.uiContext.setFocus(this);
  }

  private void onBlur() {
    var f = windowManager.uiContext.focused();
    focusSave = isMyFocus(f) ? f : null;
  }

  private boolean isMyFocus(Focusable f) {
    return this == f || rootView == f;
  }

  private void onFocus() {
    windowManager.uiContext.setFocus(focusSave != null ? focusSave : this);
  }

  private boolean isMyFocus() {
    return isMyFocus(windowManager.uiContext.focused());
  }

  @Override
  public void applyTheme(EditorColorScheme theme) {
    super.applyTheme(theme);
    window.setTheme(theme.dialogItem);
    rootView.setTheme(theme);
  }

  public void open(FileHandle f, boolean left) {
    Debug.consoleInfo("opening file " + f.getName());
    var uiContext = windowManager.uiContext;

    FsWorkerJobs.readTextFile(uiContext.window.worker(), f,
        (text, encoding) -> {
          SplitInfo splitInfo = SplitText.splitInfo(text);
          var model = new Model(splitInfo.lines, new Uri(f.getFullPath()));
          model.setEncoding(encoding);
          rootView.setModel(model, left ? 0 : 1);
        }, System.err::println);
  }

  public void setModel(Model left, Model right) {
    rootView.setModel(left, right);
  }

  public Model getLeftModel() {
    return rootView.getLeftModel();
  }

  public Model getRightModel() {
    return rootView.getRightModel();
  }

  public void setReadonly(boolean leftReadonly, boolean rightReadonly) {
    rootView.setReadonly(leftReadonly, rightReadonly);
  }

  protected void dispose() {
    if (isMyFocus()) {
      windowManager.uiContext.setFocus(null);
    }

    window = null;
    rootView = null;
  }

  protected Supplier<ToolbarItem[]> popupActions(V2i pos) {
    if (!canSelectFiles) return null;
    return ArrayOp.supplier(
        opener("open left", true),
        opener("open right", false)
    );
  }

  private ToolbarItem opener(String t, boolean left) {
    return new ToolbarItem(() -> selectFile(left), t);
  }

  private void selectFile(boolean left) {
    windowManager.uiContext.window.showOpenFilePicker(
        windowManager.hidePopupMenuThen(f -> open(f, left))
    );
  }

  @Override
  public boolean onKeyPress(KeyEvent event) {
    if (processEsc && event.keyCode == KeyCode.ESC) {
      if (event.noMods()) window.close();
      else windowManager.nextWindow();
      return true;
    }

    if (event.keyCode == KeyCode.F7 && event.singlePress()) {
      if (event.shift) {
        navigateUp();
      } else {
        navigateDown();
      }
    }

    return false;
  }

  public boolean canNavigateDown() {
    return rootView.canNavigateDown();
  }

  public void navigateDown() {
    rootView.navigateDown();
  }

  public boolean canNavigateUp() {
    return rootView.canNavigateUp();
  }

  public void navigateUp() {
    rootView.navigateUp();
  }
}
