package org.sudu.experiments.diff;

import org.teavm.jso.core.JSString;

public interface JsInlineDiffViewController extends JsViewController {
  void setCompactView(boolean compact);

  // returns 'inlineDiff'
  JSString getViewType();
}
