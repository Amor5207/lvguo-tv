package ink.pengfei.lvguo.ui.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/** Scrolls vertically focused content into view with an animation. */
public class FocusScrollView extends ScrollView {
    public FocusScrollView(Context context) {
        super(context);
        setSmoothScrollingEnabled(true);
    }

    /** Subclasses can keep their own focus alignment behavior for selected children. */
    protected boolean shouldScrollOnFocus(View focused) {
        return true;
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        // ScrollView normally jumps immediately when a descendant gains focus.
        // requestChildFocus below performs the same visibility check smoothly.
        return 0;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (focused != null && shouldScrollOnFocus(focused)) {
            post(() -> scrollFocusedChildIntoView(focused));
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (child == null || rectangle == null) return false;
        Rect rect = new Rect(rectangle);
        offsetDescendantRectToMyCoords(child, rect);
        int delta = super.computeScrollDeltaToGetChildRectOnScreen(rect);
        if (delta == 0) return false;
        if (immediate) scrollBy(0, delta);
        else smoothScrollBy(0, delta);
        return true;
    }

    public void scrollFocusedChildIntoView(View focused) {
        if (focused == null || !shouldScrollOnFocus(focused) || !isDescendant(focused)) return;
        Rect rect = new Rect(0, 0, focused.getWidth(), focused.getHeight());
        offsetDescendantRectToMyCoords(focused, rect);
        int delta = super.computeScrollDeltaToGetChildRectOnScreen(rect);
        if (delta != 0) smoothScrollBy(0, delta);
    }

    private boolean isDescendant(View view) {
        View current = view;
        while (current != null && current != this) {
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return current == this;
    }
}
