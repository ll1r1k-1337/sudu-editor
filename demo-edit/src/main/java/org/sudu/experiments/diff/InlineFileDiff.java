package org.sudu.experiments.diff;

import org.sudu.experiments.SceneApi;
import org.sudu.experiments.editor.EditorComponent;
import org.sudu.experiments.editor.EditorConst;
import org.sudu.experiments.editor.Model;
import org.sudu.experiments.editor.WindowScene;
import org.sudu.experiments.editor.ui.colors.EditorColorScheme;
import org.sudu.experiments.fonts.Fonts;
import org.sudu.experiments.math.V2i;

public class InlineFileDiff extends WindowScene {

  protected InlineDiffWindow w;

  // No-arg ctor is used by the desktop TestSceneSelector — load the hardcoded
  // sample so the scene is visually verifiable without a file picker.
  public InlineFileDiff(SceneApi api) {
    this(api, EditorConst.DEFAULT_DISABLE_PARSER);
    loadSampleModel();
  }

  // Two-arg ctor is used by the JS bridge, which supplies models via setModel.
  // Loading sample content here would race with the caller's setModel and apply
  // stale DiffRanges from the sample to the user-supplied (smaller) models.
  public InlineFileDiff(SceneApi api, boolean disableParser) {
    super(api);
    var theme = EditorColorScheme.darkIdeaColorScheme();
    w = new InlineDiffWindow(windowManager, theme, this::menuFonts, disableParser);
    w.processEsc = false;
  }

  private void loadSampleModel() {
    String left =
        "This is an experimental project\n" +
        "to write a portable (Web + Desktop)\n" +
        "editor in java and kotlin\n" +
        "with synchronized scroll and merge\n" +
        "and inline diff rendering\n" +
        "unchanged tail line one\n" +
        "unchanged tail line two\n" +
        "unchanged tail line three\n" +
        "unchanged tail line four\n" +
        "unchanged tail line five";
    String right =
        "This is a experimental project\n" +
        "to write an portable (Web + Desktop)\n" +
        "editor in kotlin and java\n" +
        "with synchronized scroll and merge\n" +
        "and inline diff rendering\n" +
        "plus a freshly inserted line\n" +
        "and another inserted line\n" +
        "unchanged tail line one\n" +
        "unchanged tail line two\n" +
        "unchanged tail line three\n" +
        "unchanged tail line four\n" +
        "unchanged tail line five";
    w.rootView.setModel(new Model(left, null), new Model(right, null));
  }

  public String[] menuFonts() {
    return Fonts.editorFonts(true);
  }

  protected EditorComponent editor() {
    return w.rootView.editor;
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
