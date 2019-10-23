package com.nvidia;

import android.content.Context;
import android.net.ProxyInfo;
import android.util.Log;
import com.android.internal.R;
import org.json.JSONArray;
import org.json.JSONException;

public class NvWhitelistService {
    private static final String TAG = "NvWhitelistService";
    private Context mContext;
    private JSONArray mWhiteListArray;

    public NvWhitelistService(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            mContext = context;
        } else {
            mContext = appContext;
        }
        //mWhiteListArray = parseXml(mContext.getResources().getXml(R.xml.tv_launhcer_app_white_list));
    }

    public boolean isWhiteApp(String pkgName) {
        boolean isWhite = false;
        for (int index = 0; index < mWhiteListArray.length(); index++) {
            try {
                isWhite = pkgName.equals(mWhiteListArray.getJSONObject(index).getString("packageName"));
            } catch (JSONException ex) {
                Log.w(TAG, ex.getMessage());
            }
            if (isWhite) {
                break;
            }
        }
        return isWhite;
    }

    public boolean isTvGame(String pkgName) {
        boolean isGame = false;
        for (int index = 0; index < mWhiteListArray.length(); index++) {
            try {
                isGame = mWhiteListArray.getJSONObject(index).getString("packageName").equals(pkgName) ? mWhiteListArray.getJSONObject(index).getString("isGame").equals("true") : false;
            } catch (JSONException ex) {
                Log.w(TAG, ex.getMessage());
            }
            if (isGame) {
                break;
            }
        }
        return isGame;
    }

    public String getBannerName(String pkgName) {
        String bannerName = ProxyInfo.LOCAL_EXCL_LIST;
        int index = 0;
        while (index < mWhiteListArray.length()) {
            String curPkgNameValue = ProxyInfo.LOCAL_EXCL_LIST;
            try {
                if (mWhiteListArray.getJSONObject(index).getString("packageName").equals(pkgName)) {
                    return mWhiteListArray.getJSONObject(index).getString("bannerName");
                }
                index++;
            } catch (JSONException ex) {
                Log.w(TAG, ex.getMessage());
            }
        }
        return bannerName;
    }
}
