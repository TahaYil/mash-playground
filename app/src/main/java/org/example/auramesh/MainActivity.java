package org.example.auramesh;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AuraMeshPrefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // İzinler verilmiş ve profil kaydedilmişse doğrudan HomeActivity'ye git
        if (isPermissionGrantedAndProfileSaved()) {
            startActivity(new Intent(MainActivity.this, HomeActivity.class));
            finish();
            return;
        }

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PermissionActivity.class);
            startActivity(intent);
        });
    }

    private boolean isPermissionGrantedAndProfileSaved() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean permissionsGranted = prefs.getBoolean("permissions_granted", false);
        boolean profileSaved = prefs.getBoolean("profile_saved", false);
        return permissionsGranted && profileSaved;
    }
}
