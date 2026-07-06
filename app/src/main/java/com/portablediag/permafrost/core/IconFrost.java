package com.portablediag.permafrost.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Turns an app's launcher icon into a "frosted" version — the original icon
 * cooled to blue, dusted with a translucent frost sheen, and badged with a
 * snowflake — so a home-screen shortcut clearly reads as "dormant".
 */
public class IconFrost {

    private static final int SIZE = 192;

    /** The app's real launcher icon as a bitmap, or null if unavailable. */
    public static Bitmap appIcon(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d = pm.getApplicationIcon(
                    pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES));
            return toBitmap(d);
        } catch (Exception e) {
            return null;
        }
    }

    /** Build the frosted bitmap from an already-obtained source icon. */
    public static Bitmap frost(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // 1. Draw the source icon, cooled: lowered saturation, shifted toward blue.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.55f);
        ColorMatrix cool = new ColorMatrix(new float[]{
                1, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1, 0, 25,   // nudge blue up
                0, 0, 0, 1, 0
        });
        cm.postConcat(cool);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(Bitmap.createScaledBitmap(src, SIZE, SIZE, true), 0, 0, p);

        // 2. Frost sheen: a soft white-to-transparent diagonal wash.
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, 0, SIZE, SIZE,
                new int[]{0x66FFFFFF, 0x11CFE8FF, 0x44BFE0FF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        sheen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        c.drawRect(0, 0, SIZE, SIZE, sheen);

        // 3. Snowflake badge, bottom-right.
        drawSnowflakeBadge(c);
        return out;
    }

    public static Bitmap frostedIcon(Context ctx, String pkg) {
        Bitmap src = appIcon(ctx, pkg);
        if (src == null) return null;
        return frost(src);
    }

    /** Cache a frosted icon to disk so it survives the target being uninstalled. */
    public static File cacheFrosted(Context ctx, String pkg, Bitmap frosted) {
        File dir = new File(ctx.getFilesDir(), "icons");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, pkg + ".png");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            frosted.compress(Bitmap.CompressFormat.PNG, 100, fos);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap loadCached(Context ctx, String pkg) {
        File f = new File(new File(ctx.getFilesDir(), "icons"), pkg + ".png");
        if (!f.exists()) return null;
        return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private static void drawSnowflakeBadge(Canvas c) {
        float r = SIZE * 0.20f;
        float cx = SIZE - r - 6;
        float cy = SIZE - r - 6;

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(0xE61E88E5); // frost blue
        c.drawCircle(cx, cy, r, circle);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(SIZE * 0.02f);
        ring.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, r, ring);

        Paint flake = new Paint(Paint.ANTI_ALIAS_FLAG);
        flake.setColor(Color.WHITE);
        flake.setStrokeWidth(SIZE * 0.022f);
        flake.setStrokeCap(Paint.Cap.ROUND);
        float arm = r * 0.72f;
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(i * 60);
            float ex = (float) (cx + Math.cos(a) * arm);
            float ey = (float) (cy + Math.sin(a) * arm);
            c.drawLine(cx, cy, ex, ey, flake);
            // little branches
            double b1 = a + Math.toRadians(30);
            double b2 = a - Math.toRadians(30);
            float mx = (float) (cx + Math.cos(a) * arm * 0.6);
            float my = (float) (cy + Math.sin(a) * arm * 0.6);
            float bl = arm * 0.30f;
            c.drawLine(mx, my, (float) (mx + Math.cos(b1) * bl), (float) (my + Math.sin(b1) * bl), flake);
            c.drawLine(mx, my, (float) (mx + Math.cos(b2) * bl), (float) (my + Math.sin(b2) * bl), flake);
        }
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, c.getWidth(), c.getHeight());
        d.draw(c);
        return b;
    }
}
