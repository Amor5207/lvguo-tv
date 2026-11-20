package ink.pengfei.lvguo.ui;

/** 首页/登录页首帧信号：启动页据此决定是否需要展示开屏动画。 */
final class StartupGate {
    interface Listener { void onDrawn(); }
    private static boolean drawn;
    private static Listener listener;
    private StartupGate(){}
    static void reset(){
        synchronized(StartupGate.class){drawn=false;listener=null;}
    }
    static void watch(Listener l){
        boolean notify;
        synchronized(StartupGate.class){
            listener=l;notify=drawn;
            if(notify)listener=null;
        }
        if(notify)l.onDrawn();
    }
    static void clear(Listener l){
        synchronized(StartupGate.class){if(listener==l)listener=null;}
    }
    static void drawn(){
        Listener l;
        synchronized(StartupGate.class){
            if(drawn)return;
            drawn=true;l=listener;listener=null;
        }
        if(l!=null)l.onDrawn();
    }
}
