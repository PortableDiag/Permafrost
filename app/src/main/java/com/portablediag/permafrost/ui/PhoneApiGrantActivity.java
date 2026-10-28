package com.portablediag.permafrost.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.portablediag.permafrost.R;
import com.portablediag.permafrost.api.ApiPrefs;
import com.portablediag.permafrost.api.PhoneApi;
import com.portablediag.permafrost.api.PhoneApiAccess;

/**
 * PhoneAPI asks for a local API key ({@code com.phoneapi.action.GRANT_KEY},
 * provider contract §3). Only PhoneAPI's release build, started for a result,
 * gets past the first check; then the owner picks what the key may do, on
 * Permafrost's own screen and behind the app lock. The key and the proof key
 * leave only as this activity's result.
 */
public class PhoneApiGrantActivity extends LockedActivity {

    private static final String TAG = "PermafrostApi";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        String caller = getCallingPackage();
        if (caller == null || !PhoneApi.callerTrusted(caller, PhoneApiAccess.certs(this, caller))) {
            Log.w(TAG, "phoneapi: key request refused ("
                    + (caller == null ? "not started for a result" : caller + " is not PhoneAPI's release build") + ")");
            finish();
            return;
        }
        int eventPort = getIntent().getIntExtra(PhoneApi.EXTRA_EVENT_PORT, 0);
        String eventKey = getIntent().getStringExtra(PhoneApi.EXTRA_EVENT_KEY);

        setContentView(R.layout.activity_phoneapi_grant);
        SystemBars.pad(findViewById(R.id.root));

        StringBuilder body = new StringBuilder(getString(R.string.phoneapi_body));
        if (!ApiPrefs.enabled(this)) body.append(' ').append(getString(R.string.phoneapi_turns_on));
        if (PhoneApiAccess.grant(this) != null) body.append(' ').append(getString(R.string.phoneapi_replaces));
        ((TextView) findViewById(R.id.body)).setText(body);

        CheckBox see = findViewById(R.id.see);
        CheckBox change = findViewById(R.id.change);
        MaterialButton allow = findViewById(R.id.allow);
        // Everything starts off, and a key that may do nothing isn't worth minting.
        Runnable sync = () -> allow.setEnabled(see.isChecked() || change.isChecked());
        see.setOnCheckedChangeListener((b, on) -> sync.run());
        change.setOnCheckedChangeListener((b, on) -> sync.run());

        findViewById(R.id.deny).setOnClickListener(v -> {
            Log.i(TAG, "phoneapi: key request declined");
            finish();
        });
        allow.setOnClickListener(v -> {
            PhoneApi.Grant g = PhoneApiAccess.create(this, see.isChecked(), change.isChecked(), eventPort, eventKey);
            setResult(RESULT_OK, new Intent()
                    .putExtra("api_key", g.key)
                    .putExtra("proof_key", PhoneApi.b64url(g.proofKey))
                    .putExtra("port", ApiPrefs.port(this)));
            finish();
        });
    }
}
