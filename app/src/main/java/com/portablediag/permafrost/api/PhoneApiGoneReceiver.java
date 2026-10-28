package com.portablediag.permafrost.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** PhoneAPI was uninstalled: drop its key, so whatever next takes the package name doesn't inherit it. */
public class PhoneApiGoneReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null) return;
        if (PhoneApi.PKG.equals(intent.getData().getSchemeSpecificPart())) {
            PhoneApiAccess.revoke(context);
        }
    }
}
