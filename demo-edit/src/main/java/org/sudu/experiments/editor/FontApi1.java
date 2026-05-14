package org.sudu.experiments.editor;

import org.sudu.experiments.ui.UiContext;

public class FontApi1 implements EditorUi.FontApi {
  final EditorComponent editor;
  final UiContext uiContext;

  public FontApi1(EditorComponent editor, UiContext uiContext) {
    this.editor = editor;
    this.uiContext = uiContext;
  }

  @Override public void increaseFont() { editor.increaseFont(); }
  @Override public void decreaseFont() { editor.decreaseFont(); }
  @Override public void changeFont(String f) { editor.changeFont(f); }
  @Override public void setFontPow(float p) { uiContext.setFontPow(p); }
}
