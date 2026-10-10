package dev.nofocus.folderplayer;

import android.app.*;
import android.content.res.Configuration;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Shared compact PC controls: status beside actions in landscape, above them in portrait. */
final class PcPage {
    final Activity activity;
    final LinearLayout actions;
    final TextView status;
    final boolean landscape;
    PcPage(Activity activity, String title) {
        this.activity=activity;landscape=activity.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root=column();root.setBackgroundColor(android.graphics.Color.rgb(242,247,245));
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(s.left+dp(16),s.top+dp(8),s.right+dp(16),s.bottom+dp(8));}
            else v.setPadding(insets.getSystemWindowInsetLeft()+dp(16),insets.getSystemWindowInsetTop()+dp(8),insets.getSystemWindowInsetRight()+dp(16),insets.getSystemWindowInsetBottom()+dp(8));
            return insets;
        });
        LinearLayout header=new LinearLayout(activity);
        Button back=new Button(activity);back.setText("Back");back.setAllCaps(false);back.setMinHeight(dp(48));back.setOnClickListener(v->activity.finish());header.addView(back);
        TextView heading=new TextView(activity);heading.setText(title);heading.setTextSize(20);heading.setSingleLine(true);heading.setEllipsize(TextUtils.TruncateAt.END);
        header.setGravity(Gravity.CENTER_VERTICAL);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        LinearLayout content=new LinearLayout(activity);content.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(activity);status.setTextSize(18);status.setGravity(Gravity.CENTER);status.setPadding(dp(4),dp(4),dp(8),dp(4));
        status.setMaxLines(5);status.setEllipsize(TextUtils.TruncateAt.END);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setOnClickListener(v->new AlertDialog.Builder(activity).setMessage(status.getText()).setPositiveButton("OK",null).show());
        content.addView(status,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));
        actions=column();actions.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(actions,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,-2));
        activity.setContentView(root);root.requestApplyInsets();
    }
    Button button(String label,String compact,View.OnClickListener action){
        Button b=new Button(activity);b.setText(landscape?compact:label);b.setContentDescription(label);b.setAllCaps(false);
        b.setTextSize(landscape?16:17);b.setMinHeight(dp(48));b.setPadding(dp(4),0,dp(4),0);b.setOnClickListener(action);
        actions.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
