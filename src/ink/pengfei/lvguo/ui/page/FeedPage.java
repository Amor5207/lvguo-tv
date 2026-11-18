package ink.pengfei.lvguo.ui.page;

import android.view.View;
import android.view.ViewGroup;
import ink.pengfei.lvguo.ui.Page;

/** The shared player lives inside the main shell; navigating never starts another Activity. */
public final class FeedPage extends Page {
    @Override protected View build(){
        View player=host.feedView();
        if(player.getParent() instanceof ViewGroup)((ViewGroup)player.getParent()).removeView(player);
        return player;
    }
    @Override public void onShow(){host.showFeed();}
    @Override public void onHide(){host.hideFeed();}
    @Override public void focusFirst(){host.focusFeed();}
    @Override public boolean returnToTop(){return false;}
}
