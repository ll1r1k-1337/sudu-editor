package org.sudu.experiments.diff;

import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.input.KeyCode;
import org.sudu.experiments.input.KeyEvent;
import org.sudu.experiments.math.V2i;
import org.sudu.experiments.ui.Focusable;
import org.sudu.experiments.ui.ToolWindow0;
import org.sudu.experiments.ui.ToolbarItem;
import org.sudu.experiments.ui.window.Window;
import org.sudu.experiments.ui.window.WindowManager;

import java.util.function.Supplier;

public class InlineDiffWindow extends ToolWindow0 implements Focusable {

  InlineDiffRootView rootView;
  Window window;
  Focusable focusSave;
  boolean processEsc = true;

  public InlineDiffWindow(
      WindowManager wm,
      EditorColorScheme theme,
      Supplier<String[]> fonts,
      boolean disableParser
  ) {
    super(wm, theme, fonts);
    rootView = new InlineDiffRootView(windowManager, disableParser);
    rootView.applyTheme(this.theme);
    window = createWindow(rootView, 30);
    window.onFocus(this::onFocus);
    window.onBlur(this::onBlur);
    windowManager.addWindow(window);

    rootView.editor.onKey(this);
    windowManager.uiContext.setFocus(this);
  }

  @Override
  protected String defaultTitle() {
    return "Code review (inline)";
  }

  private boolean isMyFocus(Focusable f) {
    return rootView.editor == f || this == f;
  }

  private boolean isMyFocus() {
    return isMyFocus(windowManager.uiContext.focused());
  }

  private void onBlur() {
    var f = windowManager.uiContext.focused();
    focusSave = isMyFocus(f) ? f : null;
  }

  private void onFocus() {
    windowManager.uiContext.setFocus(focusSave != null ? focusSave : rootView.editor);
  }

  @Override
  public void applyTheme(EditorColorScheme theme) {
    super.applyTheme(theme);
    window.setTheme(theme.dialogItem);
    rootView.applyTheme(theme);
  }

  protected Supplier<ToolbarItem[]> popupActions(V2i pos) {
    return null;
  }

  protected void dispose() {
    if (isMyFocus()) {
      windowManager.uiContext.setFocus(null);
    }
    window = null;
    rootView = null;
  }

  @Override
  public boolean onKeyPress(KeyEvent event) {
    if (processEsc && event.keyCode == KeyCode.ESC) {
      if (event.noMods()) window.close();
      else windowManager.nextWindow();
      return true;
    }
    if (event.keyCode == KeyCode.F7 && event.singlePress()) {
      if (event.shift) navigateUp();
      else navigateDown();
      return true;
    }
    if (event.keyCode == KeyCode.F1 && event.singlePress()) {
      rootView.setCompactView(!rootView.isCompactedView());
      return true;
    }
    return false;
  }

  public boolean canNavigateDown() { return rootView != null && rootView.canNavigateDown(); }
  public void navigateDown() { if (rootView != null) rootView.navigateDown(); }
  public boolean canNavigateUp() { return rootView != null && rootView.canNavigateUp(); }
  public void navigateUp() { if (rootView != null) rootView.navigateUp(); }
}
