package com.example.floatwindowdemo.service;

import static com.example.floatwindowdemo.config.MyConfig.*;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.floatwindowdemo.CustomView.RectangleDrawableConstraintLayout;
import com.example.floatwindowdemo.util.HttpUtils;
import com.example.floatwindowdemo.R;
import com.example.floatwindowdemo.util.AdbCommandHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class FloatingWidgetService extends Service {
    private static final long SECURE_COUNT_POLL_INTERVAL_MS = 500L;
    private WindowManager windowManager;

    private int modelIndex, promptIndex = 0;
    private boolean usingStream = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean forbid_capture; // 是否开启防截屏保护
    // 屏幕大小
    int screenWidth, screenHeight;
    //设置项
    SharedPreferences sharedPreferences;
    //小白点控件
    View floatLogo;

    private int outerSecureWindowCount; // 隐私窗口数量

    public static boolean isActive; // 悬浮窗已启动

    //<editor-fold desc="窗口参数">

    // 小白点窗口参数
    final WindowManager.LayoutParams floatToggleBtnParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, //| WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
    final WindowManager.LayoutParams popupMenuViewParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
    // 小白点窗口与切换菜单
    private View floatToggleBtnView, popupMenuView;
    private boolean isPopupMenuShow;

    // AI问问窗口参数
    final WindowManager.LayoutParams askViewParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
    private View askContainerView;
    private boolean isAskViewShow;
    // 网页浏览窗口参数
    final WindowManager.LayoutParams webViewParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
    private View webContainerView;
    private boolean isWebViewShow;
    // 提取文本窗口参数
    final WindowManager.LayoutParams searchTextViewParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
    private View searchTextView;
    private boolean isSearchTextViewShow;
    // 选区窗口参数
    final WindowManager.LayoutParams selectZoneViewParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
    private View selectZoneView;
    private boolean isSelectZoneViewShow;

    //</editor-fold>

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //初始化防截屏
        sharedPreferences = getSharedPreferences("PrefsConfig", MODE_PRIVATE);
        forbid_capture = sharedPreferences.getBoolean("forbid_capture", true);
        if (!forbid_capture) {
            // floatToggleBtnParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE; // 初始小白点不再隐藏
            popupMenuViewParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            askViewParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            webViewParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            searchTextViewParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            selectZoneViewParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
        } else {
            LauncherSecureCountListener();
        }
        //初始化小白点及菜单
        InitToggleBtnView();
        //初始化AI问问
        InitAskView();
        //初始化网页浏览
        InitWebView();
        //初始化提取文本
        //InitSearchTextView();
        //初始化选区
        InitSelectZoneView();
        isActive = true;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        HideAllWindows();
        ShutdownSecureCountListener();
        if (floatToggleBtnView != null && floatToggleBtnView.getParent() != null) windowManager.removeView(floatToggleBtnView);
        isActive = false;
    }

    //<editor-fold desc="初始化窗口">

    private void InitToggleBtnView() {

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // Specify the view position
        floatToggleBtnParams.gravity = Gravity.TOP | Gravity.START;
        popupMenuViewParams.gravity = Gravity.TOP | Gravity.START;
        floatToggleBtnParams.x = 50;
        floatToggleBtnParams.y = 200;

        floatToggleBtnView = LayoutInflater.from(this).inflate(R.layout.toggle_float_btn, null);
        popupMenuView = LayoutInflater.from(this).inflate(R.layout.pop_menu, null);
        floatLogo = floatToggleBtnView.findViewById(R.id.shape_circle);

        // 添加小白点悬浮窗
        windowManager.addView(floatToggleBtnView, floatToggleBtnParams);

        // 触摸
        floatToggleBtnView.findViewById(R.id.root_container).setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long initialTime = System.currentTimeMillis();
            private long durationTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        durationTime = System.currentTimeMillis() - initialTime;
                        if(durationTime < 500L){//双击隐藏所有窗口
                            HideAllWindows();
                            return true;
                        }
                        initialX = floatToggleBtnParams.x;
                        initialY = floatToggleBtnParams.y;
                        initialTime = System.currentTimeMillis();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP://抬起
                        if(System.currentTimeMillis() - initialTime > 2000L && Math.abs(initialTouchX - event.getRawX()) < 50 && Math.abs(initialTouchY - event.getRawY()) < 50){ // 长按大于3秒抬起
                            try {
                                // 解除隐匿
                                ExitHideMode();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            return true;
                        }

                        if(durationTime < 500L  && Math.abs(initialTouchX - event.getRawX()) < 10 && Math.abs(initialTouchY - event.getRawY()) < 10)
                            return true;//已经隐藏所有窗口，不进行后续操作

                        durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L && Math.abs(initialTouchX - event.getRawX()) < 10 && Math.abs(initialTouchY - event.getRawY()) < 10) {//短按
                            showPopupMenu();
                            return v.performClick();
                        } else {//长按
                            if(sharedPreferences.getBoolean("edge_attach",true)){//设置了吸边
                                if (isFloatingBallOnLeftSide()) {
                                    floatToggleBtnParams.x = 60;
                                    if(isPopupMenuShow) popupMenuViewParams.x = floatToggleBtnParams.x + 140;
                                } else {
                                    floatToggleBtnParams.x = screenWidth - floatToggleBtnView.getWidth() - 60;
                                    if(isPopupMenuShow) popupMenuViewParams.x = floatToggleBtnParams.x - floatToggleBtnView.getWidth() - 140;
                                }
                                windowManager.updateViewLayout(floatToggleBtnView, floatToggleBtnParams);
                                if(isPopupMenuShow) windowManager.updateViewLayout(popupMenuView, popupMenuViewParams);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE://移动
                        //移动小白点
                        floatToggleBtnParams.x = Math.min(Math.max(initialX + (int) (event.getRawX() - initialTouchX), 0), screenWidth - floatToggleBtnView.getWidth());
                        floatToggleBtnParams.y = Math.min(Math.max(initialY + (int) (event.getRawY() - initialTouchY), 0), screenHeight - floatToggleBtnView.getHeight());
                        windowManager.updateViewLayout(floatToggleBtnView, floatToggleBtnParams);
                        //移动菜单
                        if (isPopupMenuShow) {
                            if (isFloatingBallOnLeftSide()) {
                                popupMenuViewParams.x = floatToggleBtnParams.x + 140;
                            } else {
                                popupMenuViewParams.x = floatToggleBtnParams.x - floatToggleBtnView.getWidth() - 140;
                            }
                            popupMenuViewParams.y = floatToggleBtnParams.y;
                            windowManager.updateViewLayout(popupMenuView, popupMenuViewParams);
                        }
                        return true;
                }
                return false;
            }
        });
        // 选区事件绑定
        popupMenuView.findViewById(R.id.select_zone_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSelectZoneView();
                showPopupMenu();
            }
        });
        // 提取文本事件绑定
//        popupMenuView.findViewById(R.id.get_text_btn).setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                showSearchTextView();
//                showPopupMenu();
//            }
//        });
        // AI问问选项事件绑定
        popupMenuView.findViewById(R.id.ask_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAskView();
                showPopupMenu();
            }
        });
        // 网页浏览选项事件绑定
        popupMenuView.findViewById(R.id.web_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWebView();
                showPopupMenu();
            }
        });
        // 退出选项
        popupMenuView.findViewById(R.id.close_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(FloatingWidgetService.this.getApplicationContext(), FloatingWidgetService.class));
                onDestroy();
            }
        });
        // 藏匿选项
        popupMenuView.findViewById(R.id.hide_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LauncherSecureCountListener();
                TextView hideBtn = popupMenuView.findViewById(R.id.hide_btn);
                hideBtn.setVisibility(View.GONE);
            }
        });
    }


    Thread thread = null;
    @SuppressLint("ClickableViewAccessibility")
    private void InitAskView() {
        askViewParams.gravity = Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
        askViewParams.x = 0;
        askViewParams.y = 0;
        askContainerView = LayoutInflater.from(this).inflate(R.layout.completion_widget, null);

        //初始化模型名
        TextView titleTextView = askContainerView.findViewById(R.id.title_text);
        titleTextView.setText(MODEL_PROVIDER[modelIndex].displayName);

        //关闭事件
        askContainerView.findViewById(R.id.close_btn).setOnClickListener(v -> {
            isAskViewShow = false;
            if (askContainerView != null) windowManager.removeView(askContainerView);
            //关闭线程
            if(thread != null && thread.isAlive()){
                thread.interrupt();
                SetSubmitBtnStatus(true);
            }
        });

        //提交事件
        askContainerView.findViewById(R.id.submit_btn).setOnClickListener(v -> {
            // 获取用户输入
            EditText editText = askContainerView.findViewById(R.id.contentInput);
            String userInput = editText.getText().toString();
            if (userInput.isEmpty()) return;
            // 清空输入框
            TextView promptTextView = askContainerView.findViewById(R.id.userPrompt);
            TextView thinkTextView = askContainerView.findViewById(R.id.thinkOutput);
            TextView errorTextView = askContainerView.findViewById(R.id.errorOutput);
            TextView textView = askContainerView.findViewById(R.id.contentOutput);
            thinkTextView.setText("");
            errorTextView.setText("");
            textView.setText("");
            editText.setText("");
            // 切换控件显示
            if(promptTextView.getVisibility() == View.GONE)
                promptTextView.setVisibility(View.VISIBLE); // 显示用户输入
            if(thinkTextView.getVisibility() == View.VISIBLE)
                thinkTextView.setVisibility(View.GONE); // 隐藏思考
            if(errorTextView.getVisibility() == View.VISIBLE)
                errorTextView.setVisibility(View.GONE); // 隐藏错误信息
            promptTextView.setText(userInput);
            // 窗口失焦
            askViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            askViewParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            windowManager.updateViewLayout(askContainerView, askViewParams);
            // 指令
            if (userInput.equals("/stream")){
                usingStream = !usingStream;
                textView.setText("当前流输出状态为: " + (usingStream ? "开" : "关"));
                return;
            }else if(userInput.startsWith("/title")){
                String[] commandParams = userInput.split(" ");
                if(commandParams.length < 2){
                    textView.setText("请提供title参数");
                }else{
                    askViewParams.setTitle(commandParams[1]);
                    windowManager.updateViewLayout(askContainerView, askViewParams);
                    textView.setText("窗口标题已设置为"+askViewParams.getTitle());
                }
                return;
            }else if(userInput.startsWith("/role")){
                if (++promptIndex == SYSTEM_PROMPT.length) {
                    promptIndex = 0;
                }
                textView.setText("[当前系统提示词]\n"+SYSTEM_PROMPT[promptIndex]);
                return;
            }
            // 禁用提交按钮
            SetSubmitBtnStatus(false);

            try {
                thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (usingStream) {
                            HttpUtils.sendPostRequestStream(MODEL_PROVIDER[modelIndex].requestUrl, MODEL_PROVIDER[modelIndex].modelId, userInput, MODEL_PROVIDER[modelIndex].apiKey, SYSTEM_PROMPT[promptIndex] , handler, textView, thinkTextView, errorTextView, FloatingWidgetService.this , (ScrollView) textView.getParent().getParent(), MODEL_PROVIDER[modelIndex].extraBodyEnableThinking);
                        } else {
                            // 发起请求
                            String resText = HttpUtils.sendPostRequest(MODEL_PROVIDER[modelIndex].requestUrl, MODEL_PROVIDER[modelIndex].modelId, userInput, MODEL_PROVIDER[modelIndex].apiKey, SYSTEM_PROMPT[promptIndex], MODEL_PROVIDER[modelIndex].extraBodyEnableThinking);
                            if (resText == null) return;
                            // 解析JSON获取回复
                            try {
                                JSONObject resJson = new JSONObject(resText);
                                JSONArray choicesArray = resJson.getJSONArray("choices");
                                JSONObject firstChoice = choicesArray.getJSONObject(0);
                                JSONObject messageObject = firstChoice.getJSONObject("message");
                                String content = messageObject.getString("content");
                                handler.post(() -> {
                                    // 更新UI
                                    TextView textView = askContainerView.findViewById(R.id.contentOutput);
                                    textView.setText(content);
                                });
                            } catch (JSONException e) {
                                // 更新UI
                                handler.post(() -> {
                                    TextView textView = askContainerView.findViewById(R.id.contentOutput);
                                    textView.setText("Error parse JSON Content:\n" + resText);
                                });
                            } finally {
                                handler.post(() -> {
                                    // 更新UI
                                    SetSubmitBtnStatus(true);
                                });
                            }
                        }
                        Log.d("FloatWidgetService", "Thread End!");
                    }
                });
                thread.start();
            } catch (Exception e) {
                e.printStackTrace();
                SetSubmitBtnStatus(true);
            }

        });

        //停止事件
        askContainerView.findViewById(R.id.stop_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //关闭线程
                if(thread != null && thread.isAlive()){
                    thread.interrupt();
                    SetSubmitBtnStatus(true);
                }
            }
        });

        //复制事件
        askContainerView.findViewById(R.id.copy_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                TextView textView = askContainerView.findViewById(R.id.contentOutput);
                String data = textView.getText().toString();
                ClipData clip = ClipData.newPlainText("content", data);
                clipboard.setPrimaryClip(clip);
                // 窗口失焦
                askViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                askViewParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                windowManager.updateViewLayout(askContainerView, askViewParams);
            }
        });

        //输入框
        askContainerView.findViewById(R.id.contentInput).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {//按下输入框
                    //允许窗口获得焦点
                    askViewParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    askViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                    windowManager.updateViewLayout(askContainerView, askViewParams);
                }
                return false;
            }
        });

        //移动窗口
        askContainerView.findViewById(R.id.title_bar).setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long initialTime = System.currentTimeMillis();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        initialX = askViewParams.x;
                        initialY = askViewParams.y;
                        initialTime = System.currentTimeMillis();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP://抬起
                        long durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L && Math.abs(initialTouchX - event.getRawX()) < 10 && Math.abs(initialTouchY - event.getRawY()) < 10) {//单击
                            //单击时切换模型
                            if (++modelIndex == MODEL_PROVIDER.length) {
                                modelIndex = 0;
                            }
                            Log.d("modelIndex", String.valueOf(modelIndex));
                            //UI更新模型名
                            TextView titleTextView = askContainerView.findViewById(R.id.title_text);
                            titleTextView.setText(MODEL_PROVIDER[modelIndex].displayName);
                            return v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE://移动
                        //移动窗口
                        askViewParams.x = Math.min(Math.max(initialX + (int) (event.getRawX() - initialTouchX), -(screenWidth - askContainerView.getWidth()) / 2), (screenWidth - askContainerView.getWidth()) / 2);
                        askViewParams.y = Math.min(Math.max(initialY + (int) (event.getRawY() - initialTouchY), -(screenHeight - askContainerView.getHeight()) / 2), (screenHeight - askContainerView.getHeight()) / 2);
                        Log.d("xy", "x:" + askViewParams.x + "y:" + askViewParams.y);
                        windowManager.updateViewLayout(askContainerView, askViewParams);
                        return true;
                }
                return false;
            }
        });

        //切换模型
        titleTextView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long initialTime = System.currentTimeMillis();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        initialX = askViewParams.x;
                        initialY = askViewParams.y;
                        initialTime = System.currentTimeMillis();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return false;
                    case MotionEvent.ACTION_UP://抬起
                        Log.d("ACTION_UP", String.valueOf(modelIndex));
                        long durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L && Math.abs(initialTouchX - event.getRawX()) < 10 && Math.abs(initialTouchY - event.getRawY()) < 10) {//单击
                            titleTextView.performClick();
                        }
                        return false;
                }
                return false;
            }
        });

    }

    @SuppressLint("SetJavaScriptEnabled")
    private void InitWebView() {
        webViewParams.gravity = Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
        webViewParams.x = 0;
        webViewParams.y = 0;
        webContainerView = LayoutInflater.from(this).inflate(R.layout.web_view_widget, null);

        WebView webView = webContainerView.findViewById(R.id.web_view);
        webView.setInitialScale(200);//默认缩放
        WebSettings webSettings = webView.getSettings();
        // webSettings.setBuiltInZoomControls(true); // 启用内置缩放控件
        webSettings.setJavaScriptEnabled(true); // 启用JavaScript
        webSettings.setUseWideViewPort(false); //不允许页面宽度超过设备宽度
        webSettings.setLoadWithOverviewMode(true); // 缩放至屏幕的大小
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        String homePageUrl = "https://cn.bing.com";
        webView.loadUrl(homePageUrl);

        //切换页面
        webView.setWebViewClient(new WebViewClient() {
            final EditText urlBox = webContainerView.findViewById(R.id.url_box);
            final TextView titleBar = webContainerView.findViewById(R.id.title_bar);

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {// 当点击链接等行为发生时调用
                String newUrl = request.getUrl().toString();
                // 更新URL显示
                urlBox.setText(newUrl);
                // 返回false表示由WebView自行处理加载
                return false;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // 更新URL显示
                urlBox.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 当页面加载完成时触发
                String title = view.getTitle(); // 获取网页标题
                // 更新Title显示
                titleBar.setText(title);
                // 更新URL显示
                urlBox.setText(url);
            }
        });

        //同步网页标签页标题
        webView.setWebChromeClient(new WebChromeClient() {
            // 更新Title显示
            final TextView titleBar = webContainerView.findViewById(R.id.title_bar);

            @Override
            public void onReceivedTitle(WebView view, String title) {// 当接收到网页标题时调用
                super.onReceivedTitle(view, title);
                titleBar.setText(title);
            }
        });

        //后退
        webContainerView.findViewById(R.id.go_back_btn).setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        EditText editText = webContainerView.findViewById(R.id.url_box);
        //URL输入框回车键导航
        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    webContainerView.findViewById(R.id.go_to_btn).performClick();
                    return true;
                }
                return false;
            }
        });

        //导航
        webContainerView.findViewById(R.id.go_to_btn).setOnClickListener(v -> {
            EditText urlBox = webContainerView.findViewById(R.id.url_box);
            if (urlBox.getText().toString().isEmpty()) {
                webView.loadUrl(homePageUrl);
            } else {
                String inputUrl = urlBox.getText().toString();
                if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                    //输入非域名触发搜索
                    inputUrl = "https://cn.bing.com/search?q=" + inputUrl;
                }
                webView.loadUrl(inputUrl);
            }
        });

        //关闭
        webContainerView.findViewById(R.id.close_btn).setOnClickListener(v -> {
            showWebView();
        });

        //移动窗口
        webContainerView.findViewById(R.id.title_bar).setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long initialTime = System.currentTimeMillis();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        initialX = webViewParams.x;
                        initialY = webViewParams.y;
                        initialTime = System.currentTimeMillis();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP://抬起
                        long durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L) {
                            return v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE://移动
                        //移动窗口
                        webViewParams.x = Math.min(Math.max(initialX + (int) (event.getRawX() - initialTouchX), -(screenWidth - webView.getWidth()) / 2), (screenWidth - webView.getWidth()) / 2);
                        webViewParams.y = Math.min(Math.max(initialY + (int) (event.getRawY() - initialTouchY), -(screenHeight - webView.getHeight()) / 2), (screenHeight - webView.getHeight()) / 2);
                        windowManager.updateViewLayout(webContainerView, webViewParams);
                        return true;
                }
                return false;
            }
        });
    }

    private void InitSearchTextView() {
        searchTextViewParams.gravity = Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
        searchTextViewParams.x = 0;
        searchTextViewParams.y = 0;
        searchTextView = LayoutInflater.from(this).inflate(R.layout.search_node_main_widget, null);

        //关闭
        searchTextView.findViewById(R.id.close_btn).setOnClickListener(v -> {
            showSearchTextView();
        });

        //提交
        searchTextView.findViewById(R.id.submit_btn).setOnClickListener(v -> {
            EditText keywordEditText = searchTextView.findViewById(R.id.contentInput);
            if (keywordEditText.getText().toString().isEmpty()) return;
            if (MyAccessibilityService.getMyAccessibilityServiceInstance() != null) {
                // 更新UI
                ((TextView) searchTextView.findViewById(R.id.title_text)).setText("Filter By: " + keywordEditText.getText().toString());
                TextView outputContentView = searchTextView.findViewById(R.id.contentOutput);
                // 窗口失焦
                searchTextViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                windowManager.updateViewLayout(searchTextView, searchTextViewParams);
                // 执行搜索
                String keyword = keywordEditText.getText().toString();
                MyAccessibilityService.SetTextView(outputContentView);
                MyAccessibilityService.SetKeyword(keyword);
                // 清空输入框
                keywordEditText.setText("");
            } else {
                // 设置关键字
                TextView outputContentView = searchTextView.findViewById(R.id.contentOutput);
                outputContentView.setText("筛选关键字设置失败，请检查无障碍服务是否被授权启用");
            }
        });

        //复制
        searchTextView.findViewById(R.id.copy_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                TextView textView = searchTextView.findViewById(R.id.contentOutput);
                String data = textView.getText().toString();
                ClipData clip = ClipData.newPlainText("content", data);
                clipboard.setPrimaryClip(clip);
            }
        });

        //移动窗口
        searchTextView.findViewById(R.id.title_bar).setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long initialTime = System.currentTimeMillis();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        initialX = searchTextViewParams.x;
                        initialY = searchTextViewParams.y;
                        initialTime = System.currentTimeMillis();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP://抬起
                        long durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L) {
                            return v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE://移动
                        //移动窗口
                        searchTextViewParams.x = Math.min(Math.max(initialX + (int) (event.getRawX() - initialTouchX), -(screenWidth - searchTextView.getWidth()) / 2), (screenWidth - searchTextView.getWidth()) / 2);
                        searchTextViewParams.y = Math.min(Math.max(initialY + (int) (event.getRawY() - initialTouchY), -(screenHeight - searchTextView.getHeight()) / 2), (screenHeight - searchTextView.getHeight()) / 2);
                        windowManager.updateViewLayout(searchTextView, searchTextViewParams);
                        return true;
                }
                return false;
            }
        });

        //输入
        searchTextView.findViewById(R.id.contentInput).setOnTouchListener(new View.OnTouchListener() {
            private long initialTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        initialTime = System.currentTimeMillis();
                        //取消窗口失焦
                        searchTextViewParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        windowManager.updateViewLayout(searchTextView, searchTextViewParams);
                        ((TextView) searchTextView.findViewById(R.id.title_text)).setText("Filter Off");
                        MyAccessibilityService.SetKeyword(null);
                        return false;
                    case MotionEvent.ACTION_UP://抬起
                        long durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L) {
                            return v.performClick();
                        }
                        return true;
                }
                return false;
            }
        });

        //问AI
        searchTextView.findViewById(R.id.ask_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText askInput = askContainerView.findViewById(R.id.contentInput);
                TextView searchResult = searchTextView.findViewById(R.id.contentOutput);
                if (!isAskViewShow) showAskView();
                askInput.setText(searchResult.getText());
                // 窗口失焦
                searchTextViewParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                windowManager.updateViewLayout(searchTextView, searchTextViewParams);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void InitSelectZoneView() {
        selectZoneViewParams.gravity = Gravity.TOP | Gravity.START;;
        selectZoneViewParams.x = 0;
        selectZoneViewParams.y = 200;
        selectZoneView = LayoutInflater.from(this).inflate(R.layout.select_zone_widget, null);

        RectangleDrawableConstraintLayout container = selectZoneView.findViewById(R.id.root_container);

        int resourceId = selectZoneView.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusHeight = resourceId > 0 ? selectZoneView.getResources().getDimensionPixelSize(resourceId) : 0;

        //移动窗口
        container.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long initialTime = System.currentTimeMillis();
            private boolean resize = false;//按住角落调整面板大小
            private int holdingCorner = 0;//0左上 1左下 2右上 3右下
            final int cornerDistance = 100;//按住角落调整面板大小判定区到面板边缘的距离
            final RectangleDrawableConstraintLayout layout = selectZoneView.findViewById(R.id.root_container);
            final TextView contentDisplay = selectZoneView.findViewById(R.id.contentOutput);
            final int[] deltaOffset = new int[2];
            int rightEdge,bottomEdge;
            int statusBarHeight = statusHeight;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getPointerCount() > 1) return false;//不处理多指事件

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN://按下
                        initialX = selectZoneViewParams.x;
                        initialY = selectZoneViewParams.y;
                        initialTime = System.currentTimeMillis();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();

                        // 检查是否在角落
                        isInCorner(event,deltaOffset);

                        //双指缩放完需要抬起双指才能再次进行移动操作
                        container.isResizingDown = false;
                        container.blueLine = resize;

                        //隐藏按钮
                        for (int i = 0; i < layout.getChildCount(); i++) {
                            View view = layout.getChildAt(i);
                            if (view instanceof Button) {
                                view.setVisibility(View.INVISIBLE);
                            }
                        }
                        //清除矩形绘图
                        layout.ClearRectangleDraw();
                        contentDisplay.setText("扫描中...单击获取结果");

                        return false;
                    case MotionEvent.ACTION_UP://抬起
                        resize = false;
                        if(container.blueLine) {
                            container.blueLine = false;
                            container.invalidate();
                        }
                        long durationTime = System.currentTimeMillis() - initialTime;
                        if (durationTime < 200L && Math.abs(initialTouchX - event.getRawX()) < 10 && Math.abs(initialTouchY - event.getRawY()) < 10) {
                            //触发重绘
                            StringBuilder resText = layout.UpdateRectangleDraw();
                            //显示按钮
                            for (int i = 0; i < layout.getChildCount(); i++) {
                                View view = layout.getChildAt(i);
                                if (view instanceof Button) {
                                    view.setVisibility(View.VISIBLE);
                                }
                            }
                            //更新UI
                            if (resText.toString().isEmpty()) {
                                contentDisplay.setText("什么都木有...");
                            } else {
                                contentDisplay.setText(resText);
                            }

                            return v.performClick();
                        }
                        return false;
                    case MotionEvent.ACTION_MOVE://移动
                        if(resize){
                            //调整窗口大小
                            UpdateSizeFromCorner(event, deltaOffset);
                            return true;
                        }else if(!container.isResizingDown){
                            //移动窗口
                            selectZoneViewParams.x = Math.min(Math.max(initialX + (int) (event.getRawX() - initialTouchX), 0), screenWidth - selectZoneView.getWidth());
                            selectZoneViewParams.y = Math.min(Math.max(initialY + (int) (event.getRawY() - initialTouchY), 0), screenHeight - selectZoneView.getHeight());
                            windowManager.updateViewLayout(selectZoneView, selectZoneViewParams);
                        }
                        return true;
                }
                return false;
            }
            private void isInCorner(MotionEvent event, int[] offset) {
                int[] location = new int[2];
                selectZoneView.getLocationOnScreen(location);
                rightEdge = location[0] + selectZoneView.getWidth();
                bottomEdge = location[1] + selectZoneView.getHeight();//原点在屏幕左上角，包括状态栏的高度

                if(event.getRawX() < location[0] + cornerDistance && event.getRawY() < location[1] + cornerDistance){
                    //左上角
                    resize = true;
                    holdingCorner = 1;
                }else if(event.getRawX() < location[0] + cornerDistance && event.getRawY() > bottomEdge - cornerDistance){
                    //左下角
                    resize = true;
                    holdingCorner = 2;
                }else if(event.getRawX() > rightEdge - cornerDistance && event.getRawY() < location[1] + cornerDistance){
                    //右上角
                    resize = true;
                    holdingCorner = 3;
                }else if(event.getRawX() > rightEdge - cornerDistance && event.getRawY() > bottomEdge - cornerDistance){
                    //右下角
                    resize = true;
                    holdingCorner = 4;
                }else{
                    resize = false;
                }
                //返回从窗口左上角到触控点的向量
                offset[0] = (int)event.getRawX() - location[0];
                offset[1] = (int)event.getRawY() - location[1];
                Log.d("TAG", "windowHeight" + container.getHeight() + " BottomEdge" + bottomEdge + " location" + Arrays.toString(location) + " paramx" + selectZoneViewParams.x + " barheight" + statusHeight);
                Log.d("TGA", "resize" + resize + "corner" + holdingCorner);
                Log.d("TGA", "offset" + Arrays.toString(offset));
            }
            private void UpdateSizeFromCorner(MotionEvent event, int[] deltaOffset){
                int[] location = new int[2];
                selectZoneView.getLocationOnScreen(location);

                ViewGroup.LayoutParams params = layout.getLayoutParams();

                if(holdingCorner == 1){
                    //左上角
                    selectZoneViewParams.x = (int) event.getRawX() - deltaOffset[0];
                    params.width = rightEdge - selectZoneViewParams.x;
                    selectZoneViewParams.y = (int) event.getRawY() - deltaOffset[1] - statusHeight;
                    params.height = bottomEdge - selectZoneViewParams.y - statusHeight;
                } else if (holdingCorner == 2) {
                    //左下角
                    selectZoneViewParams.x = (int) event.getRawX() - deltaOffset[0];
                    params.width = rightEdge - selectZoneViewParams.x;
                    params.height = (int) event.getRawY() - location[1] + (bottomEdge - location[1]) - deltaOffset[1];
                } else if (holdingCorner == 3) {
                    //右上角
                    params.width = (int) event.getRawX() - location[0] + (rightEdge - location[0]) - deltaOffset[0];
                    selectZoneViewParams.y = (int) event.getRawY() - deltaOffset[1] - statusHeight;
                    params.height = bottomEdge - selectZoneViewParams.y - statusHeight;
                } else if (holdingCorner == 4) {
                    //右下角
                    params.width = (int) event.getRawX() - location[0] + (rightEdge - location[0]) - deltaOffset[0];
                    params.height = (int) event.getRawY() - location[1] + (bottomEdge - location[1]) - deltaOffset[1];
                }
                //应用窗口位置
                windowManager.updateViewLayout(selectZoneView, selectZoneViewParams);
                //应用窗口大小
                params.width = Math.min(params.width, screenWidth);
                params.height = Math.min(params.height, screenHeight);
                selectZoneView.setLayoutParams(params);
            }
        });

        //关闭
        selectZoneView.findViewById(R.id.close_btn).setOnClickListener(v -> {
            showSelectZoneView();
        });

        //复制
        selectZoneView.findViewById(R.id.copy_btn).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            TextView textView = selectZoneView.findViewById(R.id.contentOutput);
            String data = textView.getText().toString();
            ClipData clip = ClipData.newPlainText("content", data);
            clipboard.setPrimaryClip(clip);
        });

        //问AI
        selectZoneView.findViewById(R.id.ask_btn).setOnClickListener(new View.OnClickListener() {
            final RectangleDrawableConstraintLayout layout = selectZoneView.findViewById(R.id.root_container);
            final TextView contentDisplay = selectZoneView.findViewById(R.id.contentOutput);
            @Override
            public void onClick(View v) {
                EditText askInput = askContainerView.findViewById(R.id.contentInput);
                TextView textView = selectZoneView.findViewById(R.id.contentOutput);
                // showSelectZoneView();
                if (!isAskViewShow) showAskView();
                askInput.setText(textView.getText());
                //隐藏按钮
                for (int i = 0; i < layout.getChildCount(); i++) {
                    View view = layout.getChildAt(i);
                    if (view instanceof Button) {
                        view.setVisibility(View.INVISIBLE);
                    }
                }
                //清除矩形绘图
                layout.ClearRectangleDraw();
                contentDisplay.setText("扫描中...单击获取结果");
            }
        });
    }

    //</editor-fold>

    //<editor-fold desc="窗口显隐">

    private boolean isFloatingBallOnLeftSide() {
        // 获取悬浮窗位置
        int[] location = new int[2];
        floatToggleBtnView.getLocationOnScreen(location);
        int viewX = location[0];
        // 比较viewX和屏幕宽度的一半
        return viewX < screenWidth / 2;
    }

    private void showSearchTextView() {
        if (!isSearchTextViewShow) {
            isSearchTextViewShow = true;
            windowManager.addView(searchTextView, searchTextViewParams);
        } else {
            if (searchTextView.getWindowToken() != null && searchTextView.isAttachedToWindow()) {
                isSearchTextViewShow = false;
                windowManager.removeView(searchTextView);
            }
        }
    }

    private void showPopupMenu() {
        if (!isPopupMenuShow) {
            if (secureCountListenerThread!=null && secureCountListenerThread.isAlive() && outerSecureWindowCount == 0)
                return; // 藏匿模式不允许光明正大的打开窗口
            // 定位
            if (isFloatingBallOnLeftSide()) {
                popupMenuViewParams.x = floatToggleBtnParams.x + 140;
            } else {
                popupMenuViewParams.x = floatToggleBtnParams.x - 220;
            }
            popupMenuViewParams.y = floatToggleBtnParams.y;
            // 添加小白点悬浮窗
            isPopupMenuShow = true;
            windowManager.addView(popupMenuView, popupMenuViewParams);
        } else {
            // 如果已经显示，则隐藏
            if (popupMenuView.getWindowToken() != null && popupMenuView.isAttachedToWindow()){
                isPopupMenuShow = false;
                windowManager.removeView(popupMenuView);
            }
        }
    }

    private void showAskView() {
        if (!isAskViewShow) {
            isAskViewShow = true;
            windowManager.addView(askContainerView, askViewParams);
        } else {
            if (askContainerView.getWindowToken() != null && askContainerView.isAttachedToWindow()){
                isAskViewShow = false;
                windowManager.removeView(askContainerView);
            }
        }
    }

    private void showWebView() {
        WebView webView = webContainerView.findViewById(R.id.web_view);
        if (!isWebViewShow) {
            isWebViewShow = true;
            windowManager.addView(webContainerView, webViewParams);
            webView.onResume();
        } else {
            if (webContainerView.getWindowToken() != null && webContainerView.isAttachedToWindow()) {
                isWebViewShow = false;
                windowManager.removeView(webContainerView);
                webView.onPause();
            }
        }
    }

    private void showSelectZoneView() {
        if (!isSelectZoneViewShow) {
            isSelectZoneViewShow = true;
            windowManager.addView(selectZoneView, selectZoneViewParams);
        } else {
            if (selectZoneView.getWindowToken() != null && selectZoneView.isAttachedToWindow()){
                isSelectZoneViewShow = false;
                windowManager.removeView(selectZoneView);
            }
        }
    }

    private void HideAllWindows(){
        if(isPopupMenuShow) showPopupMenu();
        if(isAskViewShow) showAskView();
        if(isSearchTextViewShow) showSearchTextView();
        if(isWebViewShow) showWebView();
        if(isSelectZoneViewShow) showSelectZoneView();
    }

    //</editor-fold>

    public void setOuterSecureWindowCount(int num) throws InterruptedException {
        if(forbid_capture) {
            outerSecureWindowCount = num;
            if (outerSecureWindowCount == 0){ // 外部没有隐私窗口
                // 表明平台解除FLAG_SECURE窗口，隐匿自身
                HideAllWindows();
                floatToggleBtnParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE; // 删掉标志
                handler.post(() -> {
                    windowManager.updateViewLayout(floatToggleBtnView, floatToggleBtnParams);
                    floatLogo.setBackgroundResource(R.drawable.rounded_circle);
                });
                floatToggleBtnView.setAlpha(0.3f);
            } else if (outerSecureWindowCount > 0){
                // 重新显示
                floatToggleBtnParams.flags |= WindowManager.LayoutParams.FLAG_SECURE; // 添加标志
                handler.post(() -> {
                    windowManager.updateViewLayout(floatToggleBtnView, floatToggleBtnParams);
                    floatLogo.setBackgroundResource(R.drawable.rounded_circle_green);
                });
                floatToggleBtnView.setAlpha(0.6f);
            }
        }
    }

    private void ExitHideMode() throws InterruptedException {
        ShutdownSecureCountListener();
        // 显示按钮
        TextView hideBtn = popupMenuView.findViewById(R.id.hide_btn);
        hideBtn.setVisibility(View.VISIBLE);
        // 删掉标志
        floatToggleBtnParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
        windowManager.updateViewLayout(floatToggleBtnView, floatToggleBtnParams);
        handler.post(()->{ // 填入消息队列最后处理
            floatLogo.setBackgroundResource(R.drawable.rounded_circle);
        });
        floatToggleBtnView.setAlpha(0.6f);
    }

    public void SetSubmitBtnStatus(boolean active){
        Button submit = askContainerView.findViewById(R.id.submit_btn);
        Button stop = askContainerView.findViewById(R.id.stop_btn);
        submit.setEnabled(active);
        stop.setEnabled(!active);
        if (active){
            submit.setVisibility(View.VISIBLE);
            stop.setVisibility(View.GONE);
        } else {
            submit.setVisibility(View.GONE);
            stop.setVisibility(View.VISIBLE);
        }
    }

    volatile boolean secureCounterRunning = false;
    Thread secureCountListenerThread;

    private void LauncherSecureCountListener(){
        if (secureCountListenerThread != null && secureCountListenerThread.isAlive()) {
            return;
        }
        secureCounterRunning = true;
        secureCountListenerThread = new Thread(() -> {
            Log.d("TAG", "启动监听进程");
            try {
                AdbCommandHelper commandHelper = new AdbCommandHelper(this);
                while (secureCounterRunning) {
                    setOuterSecureWindowCount(commandHelper.GetOuterSecureWindowCount());
                    Thread.sleep(SECURE_COUNT_POLL_INTERVAL_MS);
                }
            }catch (RemoteException e){
                Log.d("TAG", "Shizuku调用出现错误");
                e.printStackTrace();
            } catch (UserNotAuthenticatedException e) {
                ShutdownSecureCountListener();
                handler.post(() -> {
                    TextView t = askContainerView.findViewById(R.id.contentOutput);
                    t.setText("Shizuku授权失败");
                });
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d("TAG", "隐私窗口统计线程结束");
        });
        secureCountListenerThread.start();
    }

    private void ShutdownSecureCountListener(){
        secureCounterRunning = false;
        if (secureCountListenerThread != null) {
            Thread listenerThread = secureCountListenerThread;
            listenerThread.interrupt();
            if (Thread.currentThread() != listenerThread) {
                try {
                    listenerThread.join(SECURE_COUNT_POLL_INTERVAL_MS * 2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!listenerThread.isAlive()) {
                secureCountListenerThread = null;
            }
        }
    }

}
