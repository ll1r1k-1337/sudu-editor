package org.sudu.experiments.diff;

import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSString;

public class JsInlineDiffViewController0 implements JsInlineDiffViewController {

  final UnifiedDiffWindow w;

  public JsInlineDiffViewController0(UnifiedDiffWindow w) {
    this.w = w;
  }

  @Override
  public JSString getViewType() {
    return JSString.valueOf("inlineDiff");
  }

  @Override
  public JsDiffSelection getSelection() {
    return JSObjects.undefined().cast();
  }

  @Override
  public boolean canNavigateUp() {
    return w.canNavigateUp();
  }

  @Override
  public void navigateUp() {
    w.navigateUp();
  }

  @Override
  public boolean canNavigateDown() {
    return w.canNavigateDown();
  }

  @Override
  public void navigateDown() {
    w.navigateDown();
  }

  @Override
  public void refresh() {
    w.rootView.refresh();
  }

  @Override
  public void setCompactView(boolean compact) {
    w.rootView.setCompactView(compact);
  }
}
