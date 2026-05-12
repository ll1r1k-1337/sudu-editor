package org.sudu.experiments.diff;

import org.sudu.experiments.SceneApi;
import org.sudu.experiments.editor.EditorConst;
import org.sudu.experiments.editor.WindowScene;
import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.fonts.Fonts;
import org.sudu.experiments.math.V2i;

public class InlineDiff extends WindowScene {

  protected UnifiedDiffWindow w;

  public InlineDiff(SceneApi api) {
    this(api, EditorConst.DEFAULT_DISABLE_PARSER);
  }

  public InlineDiff(SceneApi api, boolean disableParser) {
    super(api);
    var theme = EditorColorScheme.darkIdeaColorScheme();
    w = new UnifiedDiffWindow(windowManager, theme, this::menuFonts, disableParser);
    w.processEsc = false;
    w.canSelectFiles = false;
  }

  public String[] menuFonts() {
    return Fonts.editorFonts(true);
  }

  @Override
  public void onResize(V2i newSize, float newDpr) {
    boolean init = windowManager.uiContext.dpr == 0;
    super.onResize(newSize, newDpr);
    if (init) {
      w.window.fullscreen();
    }
  }
}
