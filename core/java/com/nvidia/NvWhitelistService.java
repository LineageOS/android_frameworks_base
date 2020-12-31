package com.nvidia;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;

import com.android.internal.R;

import java.util.ArrayList;
import java.io.IOException;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.xmlpull.v1.XmlPullParserException;

public class NvWhitelistService {
    private static final String TAG = "NvWhitelistService";
    private JSONArray mWhiteListArray;
    private Context mContext;

    public NvWhitelistService(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext != null ? appContext : context;
        mWhiteListArray =
                parseXml(mContext.getResources().getXml(R.xml.tv_launcher_app_white_list));
    }

    public boolean isWhiteApp(String pkgName) {
        if (mWhiteListArray == null) return false;

        for (int i = 0; i < mWhiteListArray.length(); i++) {
            try {
                if (pkgName.equals(mWhiteListArray.getJSONObject(i).getString("packageName"))) {
                    return true;
                }
            } catch (JSONException ex) {
                Log.w(TAG, ex.getMessage());
            }
        }

        return false;
    }

    public boolean isTvGame(String pkgName) {
        if (mWhiteListArray == null) return false;

        for (int i = 0; i < mWhiteListArray.length(); i++) {
            try {
                if (mWhiteListArray.getJSONObject(i).getString("packageName").equals(pkgName) &&
                        mWhiteListArray.getJSONObject(i).getString("isGame").equals("true")) {
                    return true;
                }
            } catch (JSONException ex) {
                Log.w(TAG, ex.getMessage());
            }
        }

        return false;
    }

    public String getBannerName(String pkgName) {
        for (int i = 0; i < mWhiteListArray.length(); i++) {
            try {
                if (mWhiteListArray.getJSONObject(i).getString("packageName").equals(pkgName)) {
                    return mWhiteListArray.getJSONObject(i).getString("bannerName");
                }
            } catch (JSONException ex) {
                Log.w(TAG, ex.getMessage());
            }
        }

        return "";
    }

    private JSONArray parseXml(XmlResourceParser xmlParser) {
        if (xmlParser == null) return null;

        JSONObject jsonObj = null;
        ArrayList<JSONObject> widgetConfigs = new ArrayList<>();
        try {
            int type = xmlParser.getEventType();
            while (type != XmlResourceParser.END_DOCUMENT) {
                switch (type) {
                    case XmlResourceParser.START_TAG:
                        if (xmlParser.getName().equals("app")) {
                            jsonObj = new JSONObject();
                            for (int i = 0; i < xmlParser.getAttributeCount(); i++) {
                                jsonObj.put(xmlParser.getAttributeName(i),
                                        xmlParser.getAttributeValue(i));
                            }
                            break;
                        }
                        break;
                    case XmlResourceParser.END_TAG:
                        if (xmlParser.getName().equals("app") && jsonObj != null) {
                            widgetConfigs.add(jsonObj);
                            break;
                        }
                        break;
                    default:
                        break;
                }
                type = xmlParser.next();
            }
        } catch (IOException | JSONException | XmlPullParserException e) {
            e.printStackTrace();
        }

        return new JSONArray(widgetConfigs);
    }
}
