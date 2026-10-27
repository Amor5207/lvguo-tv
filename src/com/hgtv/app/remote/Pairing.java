package com.hgtv.app.remote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.hgtv.app.ui.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.util.*;

public final class Pairing {
    private static java.lang.ref.WeakReference<Dialog> active=new java.lang.ref.WeakReference<>(null);
    public static void dismiss(){Dialog dialog=active.get();if(dialog!=null)dialog.dismiss();active.clear();}
    public static void show(Activity host) {
        dismiss();
        host.startService(new Intent(host,LocalService.class));
        Dialog dialog=new Dialog(host);
        Ui.closeOnBack(dialog);
        active=new java.lang.ref.WeakReference<>(dialog);
        LinearLayout body=Ui.column(host);body.setGravity(Gravity.CENTER);Ui.padding(body,28,28,28,24);
        body.setBackground(Ui.round(host,Ui.SURFACE,16));
        TextView title=Ui.text(host,"手机选剧 · 电视播放",22,Ui.TEXT,true);title.setGravity(Gravity.CENTER);body.addView(title,Ui.lp(-1,-2));
        TextView hint=Ui.text(host,"连接同一 Wi-Fi，扫码选剧",13,Ui.TEXT_2,false);hint.setGravity(Gravity.CENTER);Ui.padding(hint,0,10,0,22);body.addView(hint,Ui.lp(-1,-2));
        LinearLayout code=Ui.column(host);code.setGravity(Gravity.CENTER);body.addView(code,Ui.lp(-1,Ui.dp(host,260)));
        TextView waiting=Ui.text(host,"正在开启手机控制…",14,Ui.TEXT_2,false);waiting.setGravity(Gravity.CENTER);waiting.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);code.addView(waiting,Ui.lp(-1,-2));
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable refresh=new Runnable(){int attempts;public void run(){
        if(!dialog.isShowing() || host.isFinishing())return;
        String url=LocalService.address();
        if(url==null){if(++attempts<40)handler.postDelayed(this,250);else waiting.setText("未找到局域网地址，请连接 Wi-Fi 后重新打开");return;}
        code.removeAllViews();try {
            Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,2);
            BitMatrix matrix=new MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,400,400,hints);
            int[] pixels=new int[160000];for(int y=0;y<400;y++)for(int x=0;x<400;x++)pixels[y*400+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
            ImageView qr=new ImageView(host);qr.setImageBitmap(Bitmap.createBitmap(pixels,400,400,Bitmap.Config.ARGB_8888));
            code.addView(qr,Ui.lp(Ui.dp(host,208),Ui.dp(host,208)));
            TextView address=Ui.text(host,url.split("\\?",2)[0],13,Ui.PRIMARY,true);address.setGravity(Gravity.CENTER);address.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);Ui.padding(address,0,14,0,12);code.addView(address);
        }catch(Exception e){
            code.removeAllViews();
            TextView failure=Ui.text(host,"二维码加载失败，请重新打开",14,Ui.TEXT_2,false);
            failure.setGravity(Gravity.CENTER);failure.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            LinearLayout.LayoutParams failureLp=Ui.lp(-1,-2);failureLp.topMargin=Ui.dp(host,18);code.addView(failure,failureLp);
            android.util.Log.w("TVPairing","QR presentation failed",e);
        }
        }};
        dialog.setOnDismissListener(d->handler.removeCallbacksAndMessages(null));
        dialog.setCanceledOnTouchOutside(true);
        dialog.setContentView(body);dialog.show();Window w=dialog.getWindow();if(w!=null){w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);w.getDecorView().setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Math.min(Ui.dp(host,420),host.getResources().getDisplayMetrics().widthPixels-Ui.dp(host,32)),-2);}handler.post(refresh);
    }
}
