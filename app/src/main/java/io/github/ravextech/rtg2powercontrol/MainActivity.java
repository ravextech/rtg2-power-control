package io.github.ravextech.rtg2powercontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int WHITE = Color.rgb(238,244,250), MUTED = Color.rgb(164,181,199), MINT = Color.rgb(103,232,197);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private BootModeController controller;
    private BootModeController.Mode mode;
    private boolean busy;
    private TextView status, detail, device, notice;
    private LinearLayout normalCard, quietCard;
    private Button refresh;
    private float uiScale=1f;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        float heightDp=getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density;
        uiScale=Math.max(0.65f,Math.min(1f,heightDp/720f));
        // Launch intents never select a mode: every write requires a GUI confirmation.
        buildScreen();
    }
    @Override public void onResume() { super.onResume(); if (!busy) refreshState(true); }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * uiScale * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size*uiScale); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
    private GradientDrawable panel(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private void buildScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(52),dp(36),dp(52),dp(30)); scroll.addView(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_launcher);
        header.addView(icon,new LinearLayout.LayoutParams(dp(56),dp(56)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1); titlesParams.leftMargin=dp(20);
        titles.addView(text(getString(R.string.app_name),30,WHITE,true));
        titles.addView(text(getString(R.string.tagline),16,MUTED,false));header.addView(titles,titlesParams);
        TextView version=text("v1.1.0",14,MUTED,false);header.addView(version);root.addView(header);

        LinearLayout card = new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(28),dp(22),dp(28),dp(22));card.setBackground(panel(Color.rgb(23,37,49),18));
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(26);root.addView(card,cardParams);
        card.addView(text(getString(R.string.current_mode),13,MINT,true));
        status=text(getString(R.string.checking),28,WHITE,true);card.addView(status);
        detail=text("",18,MUTED,false);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=dp(8);card.addView(detail,detailParams);
        device=text("",14,MUTED,false);LinearLayout.LayoutParams deviceParams=new LinearLayout.LayoutParams(-1,-2);deviceParams.topMargin=dp(14);card.addView(device,deviceParams);

        TextView choose=text(getString(R.string.choose_mode),18,WHITE,true);LinearLayout.LayoutParams chooseParams=new LinearLayout.LayoutParams(-1,-2);chooseParams.topMargin=dp(24);chooseParams.bottomMargin=dp(12);root.addView(choose,chooseParams);
        LinearLayout choices=new LinearLayout(this);
        quietCard=modeCard(getString(R.string.quiet_title),getString(R.string.quiet_card));quietCard.setId(101);
        normalCard=modeCard(getString(R.string.normal_title),getString(R.string.normal_card));normalCard.setId(102);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(138),1);left.rightMargin=dp(8);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(138),1);right.leftMargin=dp(8);
        choices.addView(quietCard,left);choices.addView(normalCard,right);root.addView(choices);
        quietCard.setOnClickListener(v -> ask(BootModeController.Mode.QUIET));
        normalCard.setOnClickListener(v -> ask(BootModeController.Mode.NORMAL));

        notice=text(getString(R.string.no_immediate_reboot),15,MUTED,false);LinearLayout.LayoutParams noticeParams=new LinearLayout.LayoutParams(-1,-2);noticeParams.topMargin=dp(16);root.addView(notice,noticeParams);
        LinearLayout footer=new LinearLayout(this);LinearLayout.LayoutParams footerParams=new LinearLayout.LayoutParams(-1,-2);footerParams.topMargin=dp(20);root.addView(footer,footerParams);
        refresh=button(getString(R.string.refresh));refresh.setId(103);footer.addView(refresh,new LinearLayout.LayoutParams(dp(254),dp(48)));
        Button about=button(getString(R.string.about));about.setId(104);LinearLayout.LayoutParams aboutParams=new LinearLayout.LayoutParams(dp(190),dp(48));aboutParams.leftMargin=dp(12);footer.addView(about,aboutParams);
        refresh.setOnClickListener(v -> refreshState(false));about.setOnClickListener(v -> showAbout());
        quietCard.setNextFocusRightId(102);normalCard.setNextFocusLeftId(101);
        quietCard.setNextFocusDownId(103);normalCard.setNextFocusDownId(104);
        refresh.setNextFocusUpId(101);about.setNextFocusUpId(102);
        setContentView(scroll);
    }
    private LinearLayout modeCard(String title,String body) {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24),dp(16),dp(24),dp(16));card.setBackgroundResource(R.drawable.mode_card);
        card.setFocusable(true);card.setClickable(true);
        card.setContentDescription(title+". "+body);
        card.addView(text(title,24,WHITE,true));TextView description=text(body,16,MUTED,false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);card.addView(description,p);
        return card;
    }
    private Button button(String title) {
        Button b=new Button(this);b.setText(title);b.setTextSize(16*uiScale);b.setTextColor(WHITE);b.setAllCaps(false);b.setBackgroundResource(R.drawable.secondary_button);return b;
    }
    private void setBusy(boolean value) {
        busy=value;
        // Keep the refresh button focusable while its asynchronous read is running.
        // Repeated presses are ignored by refreshState's busy guard.
        refresh.setEnabled(true);
        boolean supported=mode==BootModeController.Mode.NORMAL||mode==BootModeController.Mode.QUIET;
        quietCard.setEnabled(!value&&supported);normalCard.setEnabled(!value&&supported);
    }
    private BootModeController controller() throws Exception {
        if(controller==null) controller=new BootModeController(new VendorEnvironment(),this::backupBeforeWrite);
        return controller;
    }
    private void refreshState(final boolean focus) {
        if(busy)return;setBusy(true);
        worker.execute(() -> {
            try { BootModeController.Mode next=controller().readMode();ui(() -> {render(next);if(focus)(next==BootModeController.Mode.QUIET?quietCard:next==BootModeController.Mode.NORMAL?normalCard:refresh).requestFocus();}); }
            catch(Exception e) { ui(() -> showFailure(e)); }
        });
    }
    private void render(BootModeController.Mode next) {
        mode=next;device.setText(Build.MODEL+"  ·  Android "+Build.VERSION.RELEASE+"  ·  "+getString(R.string.build)+" "+Build.DISPLAY);
        quietCard.setSelected(next==BootModeController.Mode.QUIET);normalCard.setSelected(next==BootModeController.Mode.NORMAL);
        int title,description;
        if(next==BootModeController.Mode.QUIET){title=R.string.quiet_title;description=R.string.quiet_detail;}
        else if(next==BootModeController.Mode.NORMAL){title=R.string.normal_title;description=R.string.normal_detail;}
        else{title=R.string.unknown_title;description=R.string.unknown_detail;}
        status.setText(title);detail.setText(description);setBusy(false);
    }
    private void ask(final BootModeController.Mode desired) {
        if(busy)return;
        if(desired==mode){Toast.makeText(this,R.string.already_selected,Toast.LENGTH_SHORT).show();return;}
        if(mode!=BootModeController.Mode.NORMAL&&mode!=BootModeController.Mode.QUIET)return;
        String confirmation=getString(desired==BootModeController.Mode.QUIET?R.string.confirm_quiet_body:R.string.confirm_normal_body);
        if(!BootModeController.TESTED_FIRMWARE.equals(Build.FINGERPRINT))confirmation+="\n\n"+getString(R.string.other_firmware_confirmation);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(desired==BootModeController.Mode.QUIET?R.string.confirm_quiet:R.string.confirm_normal)
            .setMessage(confirmation)
            .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.apply,(d,w)->apply(desired)).create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_NEGATIVE).requestFocus());dialog.show();
    }
    private void apply(final BootModeController.Mode desired) {
        setBusy(true);
        worker.execute(() -> {
            try { BootModeController.Mode next=controller().changeMode(desired);ui(() -> {render(next);Toast.makeText(this,R.string.saved,Toast.LENGTH_LONG).show();(next==BootModeController.Mode.QUIET?quietCard:normalCard).requestFocus();}); }
            catch(Exception e){ui(()->showFailure(e));}
        });
    }
    private void showFailure(Exception error) {
        mode=null;status.setText(R.string.error_title);detail.setText(R.string.error_detail);setBusy(false);refresh.requestFocus();
        String message=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
        Throwable cause=error.getCause();if(cause!=null)message+="\n"+cause.getClass().getSimpleName()+": "+cause.getMessage();
        new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(getString(R.string.error_detail)+"\n\n"+message).setPositiveButton(android.R.string.ok,null).show();
    }
    private void showAbout() {
        String info=getString(R.string.about_body)+"\n\n"+getString(R.string.supported_build)+"\n"+BootModeController.TESTED_FIRMWARE+"\n\nhttps://github.com/ravextech/rtg2-power-control";
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)+" · 1.1.0").setMessage(info).setPositiveButton(android.R.string.ok,null).show();
    }
    private void ui(Runnable task) {runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())task.run();});}
    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),"UTF-8");
    }
    private void backupBeforeWrite(String value) throws Exception {
        File directory=new File(getFilesDir(),"bootenv-backups");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not create backup directory");
        String name=BootModeController.sha256(value);
        File backup=new File(directory,name+".txt");
        if(backup.exists()){try(InputStream in=new FileInputStream(backup)){if(!value.equals(read(in)))throw new IOException("Existing backup differs");}return;}
        File temporary=new File(directory,name+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.getBytes("UTF-8"));out.getFD().sync();}
        if(!temporary.renameTo(backup))throw new IOException("Could not save the previous boot script");
    }
}
