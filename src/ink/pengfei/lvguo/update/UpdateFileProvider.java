package ink.pengfei.lvguo.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只把「更新下载目录」里的 .apk 以只读方式交给系统安装器（targetSdk ≥ 24 不能再直接给 file:// 地址）。
 * 不依赖 androidx，路径只认下载目录下的单层文件名，防目录穿越。
 */
public final class UpdateFileProvider extends ContentProvider {
    static final String AUTHORITY_SUFFIX = ".updates";

    static Uri uriFor(android.content.Context c, File f) {
        return new Uri.Builder().scheme("content").authority(c.getPackageName() + AUTHORITY_SUFFIX).appendPath(f.getName()).build();
    }

    /** 只认下载目录下的单层 .apk 文件名；其它（含目录穿越）一律返回 null。 */
    static File safeFile(File dir, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || !name.endsWith(".apk")) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        File f = safeFile(UpdateDownloader.dir(getContext()), uri.getLastPathSegment());
        if (f == null) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
