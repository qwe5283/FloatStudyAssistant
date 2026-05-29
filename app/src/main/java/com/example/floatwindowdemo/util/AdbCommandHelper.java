package com.example.floatwindowdemo.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.floatwindowdemo.ExecResult;
import com.example.floatwindowdemo.IUserService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class AdbCommandHelper {
    private static final long BIND_TIMEOUT_SECONDS = 5;

    private ShizukuServiceManager serviceManager;
    private IUserService userService;
    private final Context context;

    public AdbCommandHelper(Context context) throws UserNotAuthenticatedException {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                this.serviceManager = new ShizukuServiceManager();
            } else {
                Log.d("TAG", "SecureCounter: Shizuku未授权");
                throw new UserNotAuthenticatedException();
            }
            this.context = context;
            if (!bindUserService(context)) {
                throw new UserNotAuthenticatedException();
            }
        } else {
            Log.d("TAG", "SecureCounter: Shizuku未启动");
            throw new UserNotAuthenticatedException();
        }
    }

    public int GetOuterSecureWindowCount() throws RemoteException, UserNotAuthenticatedException {
        if (!ensureUserService()) {
            throw new UserNotAuthenticatedException();
        }
        ExecResult result = userService.exec("dumpsys window windows");
        return StringCounter.countLinesStartingAndContaining(result.stdout);
    }

    public ExecResult TryExecuteCommand(String cmd){
        try {
            if (!ensureUserService()) return null;
            return userService.exec(cmd);
        }catch (RemoteException e){
            e.printStackTrace();
        }
        return null;
    }

    private boolean ensureUserService() {
        return userService != null || bindUserService(context);
    }

    public boolean bindUserService(Context context){ // 绑定时需要传入context获取包名
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] bindSuccess = {false};
        serviceManager.bind(context, new ShizukuServiceManager.OnBindResultCallback() {
            @Override
            public void onResult(boolean success, @Nullable String errorMessage, @Nullable IUserService service) {
                if (success) {
                    userService = service;
                    bindSuccess[0] = true;
                    Log.d("TAG", "Shizuku用户服务绑定成功");
                } else {
                    Log.d("TAG", "Shizuku用户服务绑定失败");
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.d("TAG", "Shizuku用户服务绑定超时");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return bindSuccess[0];
    }
}
