package vn.r1.geminibot;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Chép model Vosk từ assets ra bộ nhớ trong (chỉ lần đầu / khi cập nhật app). */
final class ModelInstaller {
    private static final String ASSET_DIR = "model-vn";

    private ModelInstaller() {}

    static File ensure(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), ASSET_DIR);
        long version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        File marker = new File(dir, ".installed-" + version);
        if (marker.exists()) return dir;

        deleteRecursive(dir);
        copy(ctx.getAssets(), ASSET_DIR, dir);
        if (!marker.createNewFile()) throw new IOException("Không ghi được " + marker);
        return dir;
    }

    private static void copy(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            dest.getParentFile().mkdirs();
            InputStream in = am.open(path);
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return;
        }
        dest.mkdirs();
        for (String c : children) copy(am, path + "/" + c, new File(dest, c));
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
